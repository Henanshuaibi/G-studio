package cn.g.studio;
import android.app.Application;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.*;

public final class StudioApp extends Application {
    interface Listener {void changed(boolean structure);default void creativeChanged(){}}
    final Handler main=new Handler(Looper.getMainLooper());
    final ExecutorService network=Executors.newFixedThreadPool(2);
    final ExecutorService files=Executors.newFixedThreadPool(2);
    CreativeController creative;MemoryStore memory;LocalStore store;LocalStore.Config config;Models.Chat chat;Listener listener;
    String fatal="",notice="";
    Run run;Models.Provider editing;boolean importing;
    static final class Run {
        Models.Chat chat;Models.Message message;Models.Provider provider;ApiClient.Call call=new ApiClient.Call();
        volatile String text="",reasoning="";long saved;
    }
    @Override public void onCreate(){
        super.onCreate();
        try{
            store=new LocalStore(getFilesDir(),new SecureKey());config=store.loadConfig();memory=new MemoryStore(getFilesDir());
            if(!config.current.isEmpty()){
                try{chat=store.loadChat(config.current);}catch(java.io.FileNotFoundException e){chat=new Models.Chat();}
            }else chat=new Models.Chat();
            if(chat.providerId.isEmpty())chat.providerId=config.selected;
            try{store.pruneAttachments();}catch(Exception ignored){}
            creative=new CreativeController(new java.io.File(getFilesDir(),"creative"),main,network,id->{for(Models.Provider p:config.providers)if(p.id.equals(id))return p;return null;},()->{if(listener!=null)listener.creativeChanged();});
        }catch(Exception e){fatal="本地数据读取失败，原文件已保留："+e.getClass().getSimpleName();}
    }
    void emit(boolean structure){if(listener!=null)listener.changed(structure);}
    boolean busy(){return run!=null;}
    Models.Provider provider(){
        if(config==null||chat==null)return null;
        for(Models.Provider p:config.providers)if(p.id.equals(chat.providerId))return p;return null;
    }
    void persist()throws Exception{store.saveChat(chat);config.current=chat.id;store.saveConfig(config);}
    void saveDraft(String text){if(chat!=null)chat.draft=text;}
    void saveQuiet(){try{if(fatal.isEmpty()){persist();if(creative!=null)creative.saveDraft();}}catch(Exception e){notice="保存失败："+e.getMessage();}}
    void newChat()throws Exception{
        if(busy()||importing)throw new IllegalStateException("请等待附件处理完成，或先停止生成");persist();
        chat=new Models.Chat();chat.providerId=config.selected;persist();emit(true);
    }
    void selectChat(String id)throws Exception{
        if(busy()||importing)throw new IllegalStateException("请等待附件处理完成，或先停止生成");persist();
        Models.Chat next=store.loadChat(id);chat=next;persist();emit(true);
    }
    void selectProvider(String id)throws Exception{
        if(busy()||importing)throw new IllegalStateException("请等待附件处理完成，或先停止生成");
        chat.providerId=id;config.selected=id;persist();emit(true);
    }
    void saveProvider(Models.Provider p)throws Exception{
        if(busy()||importing)throw new IllegalStateException("请等待附件处理完成，或先停止生成");
        ArrayList<Models.Provider> before=new ArrayList<>(config.providers);String old=config.selected;
        int idx=-1;for(int i=0;i<config.providers.size();i++)if(config.providers.get(i).id.equals(p.id))idx=i;
        if(idx<0)config.providers.add(p);else config.providers.set(idx,p);
        config.selected=p.id;
        try{store.saveConfig(config);}catch(Exception e){config.providers=before;config.selected=old;throw e;}
        chat.providerId=p.id;persist();emit(true);
    }
    void deleteProvider(String id)throws Exception{
        if(busy()||importing)throw new IllegalStateException("请等待附件处理完成，或先停止生成");
        ArrayList<Models.Provider> before=new ArrayList<>(config.providers);String old=config.selected;
        config.providers.removeIf(p->p.id.equals(id));if(config.selected.equals(id))config.selected="";
        try{store.saveConfig(config);}catch(Exception e){config.providers=before;config.selected=old;throw e;}
        emit(true);
    }
    void send(String text,boolean retry)throws Exception{
        if(busy()||importing)throw new IllegalStateException("正在回复或处理附件中");
        Models.Provider source=provider();if(source==null)throw new IllegalArgumentException("请先添加并选择一个 API 服务");
        final String memoryContext=memory.context();
        Models.Provider p=source.copy();Endpoint.chat(p.base,p.allowHttp);
        if(!retry&&text.trim().isEmpty()&&chat.draftAttachments.isEmpty())return;
        AttachmentStore.validate(chat.draftAttachments);ApiClient.request(p,Collections.emptyList());
        if(text.length()>24000)throw new IllegalArgumentException("单条消息最多 24000 字符");
        ArrayList<Models.Message> previous=new ArrayList<>(chat.messages);ArrayList<Models.Attachment> oldAttachments=new ArrayList<>(chat.draftAttachments);String oldDraft=chat.draft,oldTitle=chat.title;
        if(retry){
            int index=chat.messages.size()-1;
            while(index>=0&&!chat.messages.get(index).role.equals("user"))index--;
            if(index<0)throw new IllegalArgumentException("没有可以重新生成的提问");
            while(chat.messages.size()>index+1)chat.messages.remove(chat.messages.size()-1);
        }else{
            Models.Message user=new Models.Message("user",text.trim());user.attachments.addAll(chat.draftAttachments);
            chat.messages.add(user);chat.draft="";chat.draftAttachments.clear();
            String title=text.trim().isEmpty()?user.attachments.get(0).name:text.trim().replace('\n',' ');
            if(chat.title.equals("新对话"))chat.title=title.substring(0,Math.min(24,title.length()));
        }
        ArrayList<Models.Message> requestHistory;
        Run r=new Run();r.chat=chat;r.provider=p;r.message=new Models.Message("assistant","");
        r.message.status="streaming";r.message.model=p.model;
        try{
            requestHistory=new ArrayList<>(chat.messages);chat.messages.add(r.message);chat.updated=System.currentTimeMillis();persist();
        }catch(Exception e){chat.messages=previous;chat.draft=oldDraft;chat.draftAttachments=oldAttachments;chat.title=oldTitle;throw e;}
        run=r;emit(true);
        main.post(new Runnable(){public void run(){
            if(run!=r)return;
            r.message.text=r.text;r.message.reasoning=r.reasoning;
            if(System.currentTimeMillis()-r.saved>2000){
                r.saved=System.currentTimeMillis();try{store.saveChat(r.chat);}catch(Exception e){notice="保存失败："+e.getMessage();}
            }
            emit(false);main.postDelayed(this,120);
        }});
        network.execute(()->{
            try{
                ApiClient.Result result=ApiClient.chat(p,ApiClient.request(p,requestHistory,store.attachments,memoryContext),r.call,(answer,reason)->{r.text=answer;r.reasoning=reason;});
                main.post(()->{
                    if(run!=r)return;
                    r.message.text=result.text;r.message.reasoning=result.reasoning;r.message.usage=result.usage;r.message.status="complete";
                    if(result.finish.equals("length"))r.message.error="达到服务输出长度限制，可以继续追问";
                    finish(r);
                });
            }catch(Exception e){
                String error=ApiClient.sanitize(e.getMessage(),p.key);
                main.post(()->{
                    if(run!=r)return;r.message.text=r.text;r.message.reasoning=r.reasoning;
                    r.message.status=r.call.cancelled?"stopped":"error";r.message.error=error;finish(r);
                });
            }
        });
    }
    void importAttachments(ContentResolver resolver,ArrayList<Uri> uris)throws Exception{
        if(busy()||importing)throw new IllegalStateException("请先完成当前操作");
        if(uris.isEmpty())return;
        if(chat.draftAttachments.size()+uris.size()>AttachmentStore.MAX_COUNT)throw new IllegalArgumentException("每条消息最多添加 6 个附件");
        importing=true;Models.Chat target=chat;ArrayList<Models.Attachment> previous=new ArrayList<>(target.draftAttachments);emit(true);
        files.execute(()->{
            ArrayList<Models.Attachment> added=new ArrayList<>();String failure="";
            try{
                for(Uri uri:uris){
                    added.add(store.attachments.importUri(resolver,uri));ArrayList<Models.Attachment> all=new ArrayList<>(previous);all.addAll(added);AttachmentStore.validate(all);
                }
            }catch(Exception e){failure=e.getMessage()==null?"附件读取失败":e.getMessage();}
            final String problem=failure;
            main.post(()->{
                importing=false;
                if(problem.isEmpty()){
                    target.draftAttachments.addAll(added);
                    try{store.saveChat(target);notice="已添加 "+added.size()+" 个附件";}
                    catch(Exception e){target.draftAttachments=previous;for(Models.Attachment a:added)store.attachments.delete(a);notice="附件保存失败："+e.getMessage();}
                }else{for(Models.Attachment a:added)store.attachments.delete(a);notice=problem;}
                emit(true);
            });
        });
    }
    void removeAttachment(Models.Attachment a)throws Exception{
        if(busy()||importing)throw new IllegalStateException("请先完成当前操作");
        int index=chat.draftAttachments.indexOf(a);if(index<0)return;chat.draftAttachments.remove(index);
        try{store.saveChat(chat);}catch(Exception e){chat.draftAttachments.add(index,a);throw e;}
        store.attachments.delete(a);emit(true);
    }
    void finish(Run r){
        if(run!=r)return;run=null;r.chat.updated=System.currentTimeMillis();
        try{store.saveChat(r.chat);}catch(Exception e){notice="保存失败："+e.getMessage();}
        emit(true);
    }
    void stop(){
        Run r=run;if(r==null)return;
        r.call.cancelled=true;r.message.text=r.text;r.message.reasoning=r.reasoning;
        r.message.status="stopped";r.message.error="已停止，可重新生成";finish(r);
        new Thread(r.call::cancel,"g-studio-cancel").start();
    }
}

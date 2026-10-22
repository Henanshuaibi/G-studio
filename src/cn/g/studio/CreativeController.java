package cn.g.studio;
import android.os.Handler;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

final class CreativeController {
    final CreativeStore store;final Handler main;final ExecutorService network;final Function<String,Models.Provider> providers;final Runnable changed;
    final ArrayList<Creative.Task> tasks=new ArrayList<>();Creative.Draft draft=new Creative.Draft();String loadError="";Run run;
    static final class Run {Creative.Task task;Models.Provider provider;ApiClient.Call call=new ApiClient.Call();volatile Thread thread;boolean stopping;}
    CreativeController(File root,Handler main,ExecutorService network,Function<String,Models.Provider> providers,Runnable changed)throws IOException{
        store=new CreativeStore(root);this.main=main;this.network=network;this.providers=providers;this.changed=changed;
        try{draft=store.loadDraft();tasks.addAll(store.list());
            for(Creative.Task t:tasks)if(!t.state.equals("completed")&&!t.state.equals("failed")&&!t.state.equals("paused")&&!t.state.equals("unknown")){
                t.state=canResume(t)?"paused":"unknown";t.error=canResume(t)?"上次等待已中断，可以继续查询或保存":"上次提交结果未确认，请先在服务商处核对任务，避免重复提交";store.save(t);
            }
            File[] partial=store.media.listFiles((dir,name)->name.endsWith(".part"));if(partial!=null)for(File f:partial)f.delete();
        }catch(Exception e){loadError="创作记录读取失败，原文件已保留："+e.getClass().getSimpleName();}
    }
    boolean busy(){return run!=null;}
    boolean active(Creative.Task t){return run!=null&&run.task.id.equals(t.id);}
    boolean canResume(Creative.Task t){return t.kind.equals("video")?(!t.apiId.isEmpty()||!t.url.isEmpty()):store.hasResult(t.id);}
    void saveDraft()throws Exception{store.saveDraft(draft);}
    void start(Models.Provider source)throws Exception{
        if(busy())throw new IllegalStateException("请先等待当前创作完成，或暂停等待");if(!loadError.isEmpty())throw new IOException(loadError);
        CreativeApi.validate(draft,source);saveDraft();
        Creative.Task t=new Creative.Task();t.kind=draft.kind;t.providerId=source.id;t.base=Endpoint.normalize(source.base,source.allowHttp);t.imageProtocol=t.kind.equals("image")?CreativeApi.imageProtocol(source,draft.model):"images";t.toolModel=source.imageToolModel;t.path=t.kind.equals("image")?CreativeApi.imagePath(source,draft.model):source.videoPath;t.jsonVideo=source.videoJson;
        t.model=draft.model.trim();t.prompt=draft.prompt.trim();t.size=draft.size.trim();t.seconds=draft.seconds.trim();t.quality=draft.quality.trim();store.save(t);tasks.add(0,t);launch(t,source);
    }
    void resume(Creative.Task task)throws Exception{
        if(busy())throw new IllegalStateException("请先完成当前等待");if(!canResume(task))throw new IOException("没有可恢复的任务 ID 或已缓存结果，请先在服务商处核对记录");
        Models.Provider p=providers.apply(task.providerId);if(p==null)throw new IOException("原服务配置已删除，请恢复该服务配置后再继续");
        if(!Endpoint.normalize(p.base,p.allowHttp).equals(task.base))throw new IOException("原服务地址已变更，请恢复原地址后再继续此任务");launch(task,p);
    }
    private void launch(Creative.Task task,Models.Provider source)throws Exception{
        Run r=new Run();r.task=Creative.Task.from(task.json());r.provider=source.copy();r.task.error="";run=r;changed.run();
        network.execute(()->work(r));
    }
    private void publish(Run r)throws Exception{
        store.save(r.task);Creative.Task copy=Creative.Task.from(r.task.json());
        main.post(()->{for(int i=0;i<tasks.size();i++)if(tasks.get(i).id.equals(copy.id)){tasks.set(i,copy);break;}changed.run();});
    }
    void acceptImageResult(Run r,JSONObject response)throws Exception{
        Creative.Task t=r.task;if(t.imageProtocol.equals("responses"))t.apiId=response.optString("id");
        response=CreativeApi.imageResult(response,t,r.provider.key);
        JSONArray data=response.optJSONArray("data");if(data==null||data.length()==0)throw new CreativeApi.Rejected("服务未返回图片列表，请检查是否使用了生图接口");
        // A pause must not discard a result that has already reached the device.
        store.saveResult(t.id,response);r.call.check();
    }
    private void images(Run r)throws Exception{
        Creative.Task t=r.task;
        if(!store.hasResult(t.id)){
            t.state="submitting";publish(r);acceptImageResult(r,CreativeApi.create(r.provider,t,r.call));
        }
        t.state="downloading";publish(r);JSONArray items=store.loadResult(t.id).getJSONArray("data");
        if(items.length()>8)throw new IOException("服务返回的图片数量超过本版限制，结果已缓存");
        for(int i=0;i<items.length();i++){
            r.call.check();boolean saved=false;for(Creative.Asset a:t.assets)if(a.index==i&&store.asset(a.file).isFile())saved=true;
            if(saved)continue;Creative.Asset a=CreativeApi.saveImage(store,t,items.getJSONObject(i),i,r.provider,r.call);t.assets.add(a);publish(r);
        }
        t.state="completed";t.error="";t.progress=100;publish(r);new android.util.AtomicFile(store.result(t.id)).delete();
    }
    private void videos(Run r)throws Exception{
        Creative.Task t=r.task;
        if(t.apiId.isEmpty()&&t.url.isEmpty()){
            t.state="submitting";publish(r);JSONObject response=CreativeApi.create(r.provider,t,r.call);CreativeApi.applyVideo(t,response,r.provider.key);publish(r);
        }else if(!t.url.isEmpty())t.state="downloading";
        else{CreativeApi.applyVideo(t,CreativeApi.videoStatus(r.provider,t,r.call),r.provider.key);publish(r);}
        for(int poll=0;!t.state.equals("downloading");poll++){
            r.call.check();if(poll>=120)throw new IOException("等待时间较长，可稍后继续查询");Thread.sleep(5000);r.call.check();
            CreativeApi.applyVideo(t,CreativeApi.videoStatus(r.provider,t,r.call),r.provider.key);publish(r);
        }
        r.call.check();boolean saved=false;for(Creative.Asset a:t.assets)if(store.asset(a.file).isFile())saved=true;
        if(!saved){t.assets.add(CreativeApi.saveVideo(store,t,r.provider,r.call));publish(r);}
        t.state="completed";t.error="";t.progress=100;publish(r);
    }
    private void work(Run r){
        r.thread=Thread.currentThread();
        try{if(r.task.kind.equals("image"))images(r);else videos(r);}
        catch(Exception e){
            String error=ApiClient.sanitize(e.getMessage(),r.provider.key);boolean recoverable=canResume(r.task);
            if(r.task.state.equals("failed"))r.task.error=error;
            else if(recoverable){r.task.state="paused";r.task.error=r.call.cancelled?"本机等待已暂停，远端任务仍会继续；可稍后继续查询或保存":error;}
            else{r.task.state=e instanceof CreativeApi.Rejected||error.contains("HTTP 400")||error.contains("HTTP 401")||error.contains("HTTP 403")||error.contains("HTTP 404")||error.contains("HTTP 422")?"failed":"unknown";r.task.error=(r.call.cancelled?"本机等待已暂停":error)+(r.task.state.equals("unknown")?"\n提交结果未确认，请先在服务商处核对，重新生成会创建新任务":"");}
            try{publish(r);}catch(Exception saveError){main.post(()->{loadError="创作记录保存失败："+saveError.getMessage();changed.run();});}
        }finally{r.thread=null;Thread.interrupted();main.post(()->{if(run==r)run=null;changed.run();});}
    }
    void pause(){
        Run r=run;if(r==null||r.stopping)return;r.stopping=true;r.call.cancelled=true;
        Thread worker=r.thread;if(worker!=null)worker.interrupt();new Thread(r.call::cancel,"g-studio-pause-creation").start();changed.run();
    }
    void delete(Creative.Task t)throws Exception{if(active(t))throw new IOException("请先暂停并等待本机操作结束");store.delete(t);tasks.removeIf(item->item.id.equals(t.id));changed.run();}
}

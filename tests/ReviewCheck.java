package cn.g.studio;
import static cn.g.studio.CoreCheck.check;
import android.os.*;
import android.graphics.*;
import android.util.Base64;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

final class ReviewCheck {
    static void backupOnly(File file)throws Exception{Files.move(file.toPath(),new File(file.getPath()+".bak").toPath());}
    static LocalStore local(File root)throws Exception{return new LocalStore(root,new LocalStore.Vault(){public String seal(String s){return s;}public String open(String s){return s;}});}
    static void run(File dir)throws Exception{
        LocalStore local=local(new File(dir,"backup-chats-"+Models.id()));Models.Chat chat=new Models.Chat();chat.title="备份中的会话";chat.messages.add(new Models.Message("user","请保留"));local.saveChat(chat);
        File chatFile=new File(local.chats,chat.id+".json");backupOnly(chatFile);
        check(local.listChats().size()==1&&local.loadChat(chat.id).title.equals(chat.title),"backup-only conversation is listed and recovered");
        backupOnly(chatFile);check(local.recentChats(10).size()==1,"backup-only conversation appears in recent list");
        Models.Chat duplicate=new Models.Chat();duplicate.title="只出现一次";duplicate.messages.add(new Models.Message("user","both files"));local.saveChat(duplicate);
        File both=new File(local.chats,duplicate.id+".json");Files.copy(both.toPath(),new File(both.getPath()+".bak").toPath());check(local.listChats().size()==2,"base and backup enumerate once");check(local.recentChats(0).isEmpty(),"zero recent count returns no entries");
        CreativeStore creative=new CreativeStore(new File(dir,"backup-creative-"+Models.id()));Creative.Task task=new Creative.Task();task.model="gpt-6.1-sol";task.state="queued";task.kind="video";task.apiId="video_backup";creative.save(task);
        backupOnly(new File(creative.tasks,task.id+".json"));check(creative.list().size()==1&&creative.load(task.id).apiId.equals(task.apiId),"backup-only generation task keeps remote ID");
        Creative.Draft draft=new Creative.Draft();draft.prompt="未发出的画面描述";draft.model="gpt-6.1-sol";creative.saveDraft(draft);backupOnly(new File(creative.root,"draft.json"));check(creative.loadDraft().prompt.equals(draft.prompt),"backup-only creative draft recovers");
        Models.Attachment attachment=local.attachments.importFile("memory.txt","text/plain",new ByteArrayInputStream("保留附件".getBytes("UTF-8")));chat.messages.get(0).attachments.add(attachment);local.saveChat(chat);backupOnly(chatFile);local.pruneAttachments();check(local.attachments.file(attachment.id).isFile(),"attachment retained for backup-only chat");
        cancelledResults(dir);
    }
    static void cancelledResults(File dir)throws Exception{
        HandlerThread thread=new HandlerThread("creation-pause-result-test");thread.start();Handler main=new Handler(thread.getLooper());ExecutorService worker=Executors.newSingleThreadExecutor();AtomicReference<CreativeController> owner=new AtomicReference<>();
        try{
            Models.Provider p=CoreCheck.provider();p.base="http://127.0.0.1:9/v1";
            byte[] png;Bitmap bitmap=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888);try{ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,out);png=out.toByteArray();}finally{bitmap.recycle();}
            String encoded=Base64.encodeToString(png,Base64.NO_WRAP);
            for(String protocol:new String[]{"images","responses"}){
                File root=new File(dir,"paused-result-"+protocol+"-"+Models.id());CountDownLatch done=new CountDownLatch(1);
                CreativeController c=CreativeCheck.on(main,()->new CreativeController(root,main,worker,id->p,()->{if(owner.get()!=null&&!owner.get().busy())done.countDown();}));owner.set(c);
                CreativeController.Run run=new CreativeController.Run();run.task=CreativeCheck.task(p,"image");run.task.imageProtocol=protocol;run.provider=p;run.call.cancelled=true;c.store.save(run.task);
                JSONObject response=protocol.equals("images")?new JSONObject().put("data",new JSONArray().put(new JSONObject().put("b64_json",encoded))):new JSONObject().put("id","resp_pause").put("status","completed").put("output",new JSONArray().put(new JSONObject().put("type","image_generation_call").put("result",encoded)));
                CoreCheck.fails(()->c.acceptImageResult(run,response),"已停止");
                check(c.store.hasResult(run.task.id),"already received "+protocol+" image survives cancellation boundary");
                CreativeController restored=CreativeCheck.on(main,()->new CreativeController(root,main,worker,id->p,()->{if(owner.get()!=null&&!owner.get().busy())done.countDown();}));owner.set(restored);
                check(CreativeCheck.on(main,()->restored.tasks.get(0).state.equals("paused")&&restored.canResume(restored.tasks.get(0))),"paused "+protocol+" image recognized after restart");
                CreativeCheck.on(main,()->{restored.resume(restored.tasks.get(0));return null;});check(done.await(6,TimeUnit.SECONDS),"paused "+protocol+" image resumed without server");
                Creative.Task result=CreativeCheck.on(main,()->restored.tasks.get(0));check(result.state.equals("completed")&&result.assets.size()==1&&Arrays.equals(Files.readAllBytes(restored.store.asset(result.assets.get(0).file).toPath()),png),"paused "+protocol+" image saves original bytes without POST");
            }
        }finally{worker.shutdownNow();worker.awaitTermination(3,TimeUnit.SECONDS);thread.quitSafely();thread.join(1000);}
    }

}

package cn.g.studio;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class CreativeStore {
    final File root,tasks,media,cache;
    CreativeStore(File root)throws IOException{
        this.root=root;tasks=new File(root,"tasks");media=new File(root,"media");cache=new File(root,"results");
        for(File f:new File[]{root,tasks,media,cache})if(!f.isDirectory()&&!f.mkdirs())throw new IOException("无法创建创作目录");
    }
    private String id(String id)throws IOException{if(id==null||!id.matches("[a-f0-9-]{36}"))throw new IOException("创作编号不正确");return id;}
    File asset(String name)throws IOException{if(name==null||!name.matches("[a-f0-9-]{36}\\.(png|jpg|webp|gif|mp4)"))throw new IOException("结果文件名不正确");return new File(media,name);}
    File result(String taskId)throws IOException{return new File(cache,id(taskId)+".json");}
    synchronized void save(Creative.Task t)throws Exception{LocalStore.write(new File(tasks,id(t.id)+".json"),t.json());}
    synchronized Creative.Task load(String id)throws Exception{return Creative.Task.from(LocalStore.read(new File(tasks,id(id)+".json")));}
    synchronized ArrayList<Creative.Task> list()throws Exception{
        ArrayList<Creative.Task> list=new ArrayList<>();for(File f:LocalStore.jsonRecords(tasks))list.add(Creative.Task.from(LocalStore.read(f)));
        Collections.sort(list,(a,b)->Long.compare(b.created,a.created));return list;
    }
    Creative.Draft loadDraft()throws Exception{File file=new File(root,"draft.json");return LocalStore.hasRecord(file)?Creative.Draft.from(LocalStore.read(file)):new Creative.Draft();}
    void saveDraft(Creative.Draft draft)throws Exception{LocalStore.write(new File(root,"draft.json"),draft.json());}
    void saveResult(String id,JSONObject result)throws Exception{
        byte[] bytes=result.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>40*1048576)throw new IOException("生图结果过大");
        AtomicFile f=new AtomicFile(result(id));FileOutputStream out=null;
        try{out=f.startWrite();out.write(bytes);f.finishWrite(out);}catch(Exception e){if(out!=null)f.failWrite(out);throw e;}
    }
    JSONObject loadResult(String id)throws Exception{try(Reader in=new InputStreamReader(new AtomicFile(result(id)).openRead(),StandardCharsets.UTF_8)){return new JSONObject(ApiClient.read(in,40*1048576));}}
    boolean hasResult(String id){try{return result(id).isFile()||new File(result(id).getPath()+".bak").isFile();}catch(IOException e){return false;}}
    synchronized void delete(Creative.Task t)throws Exception{new AtomicFile(new File(tasks,id(t.id)+".json")).delete();new AtomicFile(result(t.id)).delete();for(Creative.Asset a:t.assets)asset(a.file).delete();}
}

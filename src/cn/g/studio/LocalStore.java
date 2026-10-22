package cn.g.studio;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class LocalStore {
    interface Vault {String seal(String value)throws Exception;String open(String value)throws Exception;}
    static final class Config {
        ArrayList<Models.Provider> providers=new ArrayList<>();
        String current="",selected="";
    }
    final File root,chats;final Vault vault;final AttachmentStore attachments;
    LocalStore(File root,Vault vault)throws IOException{
        this.root=root;this.chats=new File(root,"chats");this.vault=vault;attachments=new AttachmentStore(new File(root,"attachments"));
        if(!chats.isDirectory()&&!chats.mkdirs())throw new IOException("无法创建聊天目录");
    }
    private File chatFile(String id)throws IOException{
        if(!id.matches("[a-zA-Z0-9-]{1,80}"))throw new IOException("会话编号不正确");
        return new File(chats,id+".json");
    }
    static boolean hasRecord(File file){return file.isFile()||new File(file.getPath()+".bak").isFile();}
    static ArrayList<File> jsonRecords(File directory){
        LinkedHashMap<String,File> records=new LinkedHashMap<>();File[] files=directory.listFiles();
        if(files!=null)for(File file:files){String name=file.getName();if(name.endsWith(".json.bak"))name=name.substring(0,name.length()-4);else if(!name.endsWith(".json"))continue;records.put(name,new File(directory,name));}
        return new ArrayList<>(records.values());
    }
    private static long modified(File file){return Math.max(file.lastModified(),new File(file.getPath()+".bak").lastModified());}
    static JSONObject read(File file)throws Exception{
        AtomicFile f=new AtomicFile(file);
        try(InputStream in=f.openRead();Reader r=new InputStreamReader(in,StandardCharsets.UTF_8)){
            return new JSONObject(ApiClient.read(r,8*1048576));
        }
    }
    static void write(File file,JSONObject data)throws Exception{
        byte[] bytes=data.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>8*1048576)throw new IOException("会话过大，请开启新对话");
        AtomicFile f=new AtomicFile(file);FileOutputStream out=null;
        try{out=f.startWrite();out.write(bytes);f.finishWrite(out);}
        catch(Exception e){if(out!=null)f.failWrite(out);throw e;}
    }
    Config loadConfig()throws Exception{
        Config c=new Config();File f=new File(root,"config.json");if(!f.exists()&&!new File(root,"config.json.bak").exists())return c;
        JSONObject o=read(f);c.current=o.optString("current");c.selected=o.optString("selected");
        JSONArray a=o.optJSONArray("providers");if(a!=null)for(int i=0;i<a.length();i++){
            JSONObject v=a.getJSONObject(i);Models.Provider p=Models.Provider.from(v);
            p.key=vault.open(v.optString("secret"));c.providers.add(p);
        }return c;
    }
    void saveConfig(Config c)throws Exception{
        JSONArray a=new JSONArray();for(Models.Provider p:c.providers)a.put(p.json().put("secret",vault.seal(p.key)));
        write(new File(root,"config.json"),new JSONObject().put("version",1).put("providers",a).put("current",c.current).put("selected",c.selected));
    }
    synchronized Models.Chat loadChat(String id)throws Exception{return Models.Chat.from(read(chatFile(id)));}
    synchronized void saveChat(Models.Chat c)throws Exception{write(chatFile(c.id),c.json());}
    synchronized void deleteChat(String id)throws Exception{new AtomicFile(chatFile(id)).delete();}
    synchronized ArrayList<Models.Chat> listChats()throws Exception{
        ArrayList<Models.Chat> list=new ArrayList<>();
        for(File f:jsonRecords(chats)){
            Models.Chat c=Models.Chat.from(read(f));if(!c.messages.isEmpty()||!c.draft.isEmpty()||!c.draftAttachments.isEmpty())list.add(c);
        }
        Collections.sort(list,(a,b)->Long.compare(b.updated,a.updated));return list;
    }
    synchronized ArrayList<Models.Chat> recentChats(int limit)throws Exception{
        ArrayList<File> files=jsonRecords(chats);ArrayList<Models.Chat> list=new ArrayList<>();if(limit<=0)return list;
        Collections.sort(files,(a,b)->Long.compare(modified(b),modified(a)));
        for(File f:files){
            Models.Chat c=Models.Chat.from(read(f));if(!c.messages.isEmpty()||!c.draft.isEmpty()||!c.draftAttachments.isEmpty())list.add(c);
            if(list.size()>=limit)break;
        }return list;
    }
    synchronized void pruneAttachments()throws Exception{
        HashSet<String> retained=new HashSet<>();
        for(File f:jsonRecords(chats)){
            Models.Chat c=Models.Chat.from(read(f));for(Models.Attachment a:c.draftAttachments)retained.add(a.id);
            for(Models.Message m:c.messages)for(Models.Attachment a:m.attachments)retained.add(a.id);
        }
        attachments.prune(retained);
    }
}

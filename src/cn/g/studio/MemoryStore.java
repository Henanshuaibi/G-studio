package cn.g.studio;
import org.json.*;
import java.io.*;
import java.util.*;

final class MemoryStore {
    static final int MAX_ENTRIES=100,MAX_TEXT=3000,MAX_TOTAL=24000;
    static final class Entry {
        String id,text;long updated;
        Entry(String id,String text,long updated){this.id=id;this.text=text;this.updated=updated;}
        JSONObject json()throws Exception{return new JSONObject().put("id",id).put("text",text).put("updated",updated);}
    }
    private final File file;private boolean enabled=true;private ArrayList<Entry> entries=new ArrayList<>();
    MemoryStore(File root)throws Exception{
        file=new File(root,"memory.json");
        if(file.exists()||new File(root,"memory.json.bak").exists()){
            JSONObject o=LocalStore.read(file);enabled=o.optBoolean("enabled",true);JSONArray a=o.getJSONArray("entries");
            HashSet<String> ids=new HashSet<>();for(int i=0;i<a.length();i++){
                JSONObject item=a.getJSONObject(i);String id=item.getString("id");if(!id.matches("[a-zA-Z0-9-]{1,80}")||!ids.add(id))throw new IOException("记忆编号损坏，原文件已保留");
                entries.add(new Entry(id,item.getString("text"),item.optLong("updated")));
            }validate(entries);
        }
    }
    private static void validate(List<Entry> list){
        if(list.size()>MAX_ENTRIES)throw new IllegalArgumentException("最多保存 100 条记忆，请先整理旧记忆");int total=0;
        for(Entry e:list){if(e.text.trim().isEmpty())throw new IllegalArgumentException("请填写记忆内容");if(e.text.length()>MAX_TEXT)throw new IllegalArgumentException("单条记忆最多 3000 字符");total+=e.text.length();}
        if(total>MAX_TOTAL)throw new IllegalArgumentException("记忆总计最多 24000 字符，请先整理旧记忆");
    }
    private void commit(boolean use,ArrayList<Entry> next)throws Exception{
        validate(next);JSONArray a=new JSONArray();for(Entry e:next)a.put(e.json());
        LocalStore.write(file,new JSONObject().put("version",1).put("enabled",use).put("entries",a));enabled=use;entries=next;
    }
    synchronized boolean enabled(){return enabled;}
    synchronized ArrayList<Entry> list(){ArrayList<Entry> out=new ArrayList<>();for(Entry e:entries)out.add(new Entry(e.id,e.text,e.updated));return out;}
    synchronized void setEnabled(boolean use)throws Exception{commit(use,list());}
    synchronized void save(String id,String text)throws Exception{
        ArrayList<Entry> next=list();String clean=text.trim();long now=System.currentTimeMillis();
        if(id.isEmpty())next.add(0,new Entry(Models.id(),clean,now));
        else{boolean found=false;for(Entry e:next)if(e.id.equals(id)){e.text=clean;e.updated=now;found=true;break;}if(!found)throw new IOException("这条记忆已被删除，请重新添加");}
        commit(enabled,next);
    }
    synchronized void delete(String id)throws Exception{ArrayList<Entry> next=list();next.removeIf(e->e.id.equals(id));commit(enabled,next);}
    synchronized void clear()throws Exception{commit(enabled,new ArrayList<>());}
    synchronized String context()throws Exception{
        if(!enabled||entries.isEmpty())return "";JSONArray a=new JSONArray();for(Entry e:entries)a.put(e.text);
        return "以下是用户主动保存的长期记忆，用于理解用户的背景和偏好；信息可能过时，以用户当前消息为准：\n"+a.toString();
    }
}

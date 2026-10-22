package cn.g.studio;
import org.json.*;
import java.util.*;

final class Models {
    static String id(){return UUID.randomUUID().toString();}
    static final class Provider {
        String id=Models.id(),name="我的中转站",base="",key="",model="",system="",temperature="";
        String imageModel="",videoModel="",imagePath="/images/generations",videoPath="/videos";
        String imageProtocol="auto",responsesPath="/responses",imageToolModel="";
        boolean videoJson;
        boolean stream=true,allowHttp=false;
        int contextTurns=10;
        // Credentials are never included in ordinary serialization or exports.
        JSONObject json() throws Exception {
            return new JSONObject().put("id",id).put("name",name).put("base",base).put("model",model)
              .put("system",system).put("temperature",temperature).put("stream",stream)
              .put("allowHttp",allowHttp).put("contextTurns",contextTurns).put("imageModel",imageModel).put("videoModel",videoModel).put("imagePath",imagePath).put("videoPath",videoPath).put("videoJson",videoJson).put("imageProtocol",imageProtocol).put("responsesPath",responsesPath).put("imageToolModel",imageToolModel);
        }
        static Provider from(JSONObject o){
            Provider p=new Provider();p.id=o.optString("id",p.id);p.name=o.optString("name","我的中转站");
            p.imageModel=o.optString("imageModel");p.videoModel=o.optString("videoModel");p.imagePath=o.optString("imagePath","/images/generations");p.videoPath=o.optString("videoPath","/videos");p.videoJson=o.optBoolean("videoJson");
            p.imageProtocol=o.optString("imageProtocol","auto");p.responsesPath=o.optString("responsesPath","/responses");p.imageToolModel=o.optString("imageToolModel");
            p.base=o.optString("base");p.model=o.optString("model");p.system=o.optString("system");
            p.temperature=o.optString("temperature");p.stream=o.optBoolean("stream",true);
            p.allowHttp=o.optBoolean("allowHttp",false);p.contextTurns=Math.max(1,Math.min(50,o.optInt("contextTurns",10)));return p;
        }
        Provider copy() throws Exception {Provider p=from(json());p.key=key;return p;}
    }
    static final class Attachment {
        String id=Models.id(),name="附件",mime="",kind="",text="";long size;
        JSONObject json()throws Exception{return new JSONObject().put("id",id).put("name",name).put("mime",mime).put("kind",kind).put("text",text).put("size",size);}
        static Attachment from(JSONObject o){Attachment a=new Attachment();a.id=o.optString("id",a.id);a.name=o.optString("name","附件");a.mime=o.optString("mime");a.kind=o.optString("kind");a.text=o.optString("text");a.size=o.optLong("size");return a;}
        String label(){return (kind.equals("image")?"图片":kind.equals("pdf")?"PDF":"文档")+" · "+name+" · "+(size>=1048576?String.format(Locale.ROOT,"%.1f MB",size/1048576.0):Math.max(1,(size+1023)/1024)+" KB");}
    }
    static JSONArray attachmentsJson(List<Attachment> files)throws Exception{JSONArray a=new JSONArray();for(Attachment f:files)a.put(f.json());return a;}
    static ArrayList<Attachment> attachmentsFrom(JSONArray a){ArrayList<Attachment> files=new ArrayList<>();if(a!=null)for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null)files.add(Attachment.from(o));}return files;}
    static final class Message {
        String role,text,reasoning="",status="complete",error="",model="",usage="";
        ArrayList<Attachment> attachments=new ArrayList<>();
        Message(String role,String text){this.role=role;this.text=text;}
        JSONObject json() throws Exception {
            return new JSONObject().put("role",role).put("text",text).put("reasoning",reasoning)
              .put("status",status).put("error",error).put("model",model).put("usage",usage).put("attachments",attachmentsJson(attachments));
        }
        static Message from(JSONObject o){
            Message m=new Message(o.optString("role","user"),o.optString("text"));
            m.reasoning=o.optString("reasoning");m.status=o.optString("status","complete");
            m.attachments=attachmentsFrom(o.optJSONArray("attachments"));m.error=o.optString("error");m.model=o.optString("model");m.usage=o.optString("usage");
            if(m.status.equals("streaming")){m.status="stopped";m.error="上次回复未完成，可以重新生成";}return m;
        }
    }
    static final class Chat {
        String id=Models.id(),title="新对话",providerId="",draft="";
        long updated=System.currentTimeMillis();
        ArrayList<Message> messages=new ArrayList<>();
        ArrayList<Attachment> draftAttachments=new ArrayList<>();
        JSONObject json() throws Exception {
            JSONArray a=new JSONArray();for(Message m:messages)a.put(m.json());
            return new JSONObject().put("id",id).put("title",title).put("providerId",providerId)
              .put("draft",draft).put("draftAttachments",attachmentsJson(draftAttachments)).put("updated",updated).put("messages",a);
        }
        static Chat from(JSONObject o){
            Chat c=new Chat();c.id=o.optString("id",c.id);c.title=o.optString("title","新对话");
            c.providerId=o.optString("providerId");c.draft=o.optString("draft");c.draftAttachments=attachmentsFrom(o.optJSONArray("draftAttachments"));c.updated=o.optLong("updated",0);
            JSONArray a=o.optJSONArray("messages");if(a!=null)for(int i=0;i<a.length();i++){
                JSONObject v=a.optJSONObject(i);if(v!=null)c.messages.add(Message.from(v));
            }return c;
        }
    }
}

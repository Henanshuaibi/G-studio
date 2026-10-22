package cn.g.studio;
import org.json.*;
import java.util.*;

final class Creative {
    static final class Draft {
        String kind="image",providerId="",model="",prompt="",size="",seconds="",quality="";
        JSONObject json()throws Exception{return new JSONObject().put("kind",kind).put("providerId",providerId).put("model",model).put("prompt",prompt).put("size",size).put("seconds",seconds).put("quality",quality);}
        static Draft from(JSONObject o){Draft d=new Draft();if(o!=null){d.kind=o.optString("kind","image");d.providerId=o.optString("providerId");d.model=o.optString("model");d.prompt=o.optString("prompt");d.size=o.optString("size");d.seconds=o.optString("seconds");d.quality=o.optString("quality");}return d;}
    }
    static final class Asset {
        String file="",mime="",name="";long bytes;int index;
        JSONObject json()throws Exception{return new JSONObject().put("file",file).put("mime",mime).put("name",name).put("bytes",bytes).put("index",index);}
        static Asset from(JSONObject o){Asset a=new Asset();a.file=o.optString("file");a.mime=o.optString("mime");a.name=o.optString("name");a.bytes=o.optLong("bytes");a.index=o.optInt("index");return a;}
    }
    static final class Task {
        String id=Models.id(),kind="image",providerId="",base="",path="",model="",prompt="",size="",seconds="",quality="",state="submitting",apiId="",url="",error="";
        String imageProtocol="images",toolModel="";
        boolean jsonVideo;long created=System.currentTimeMillis();int progress;
        ArrayList<Asset> assets=new ArrayList<>();
        JSONObject json()throws Exception{
            JSONArray files=new JSONArray();for(Asset a:assets)files.put(a.json());
            return new JSONObject().put("id",id).put("kind",kind).put("providerId",providerId).put("base",base).put("path",path).put("model",model).put("prompt",prompt).put("size",size).put("seconds",seconds).put("quality",quality).put("state",state).put("apiId",apiId).put("url",url).put("error",error).put("imageProtocol",imageProtocol).put("toolModel",toolModel).put("jsonVideo",jsonVideo).put("created",created).put("progress",progress).put("assets",files);
        }
        static Task from(JSONObject o)throws Exception{
            Task t=new Task();t.id=o.optString("id",t.id);t.kind=o.optString("kind","image");t.providerId=o.optString("providerId");t.base=o.optString("base");t.path=o.optString("path");t.model=o.optString("model");t.prompt=o.optString("prompt");t.size=o.optString("size");t.seconds=o.optString("seconds");t.quality=o.optString("quality");t.state=o.optString("state","unknown");t.apiId=o.optString("apiId");t.url=o.optString("url");t.error=o.optString("error");t.imageProtocol=o.optString("imageProtocol","images");t.toolModel=o.optString("toolModel");t.jsonVideo=o.optBoolean("jsonVideo");t.created=o.optLong("created");t.progress=o.optInt("progress");
            JSONArray files=o.optJSONArray("assets");if(files!=null)for(int n=0;n<files.length();n++)t.assets.add(Asset.from(files.getJSONObject(n)));return t;
        }
        String status(){
            String label=state.equals("submitting")?"正在提交":state.equals("queued")?"排队中":state.equals("in_progress")?"正在生成":state.equals("downloading")?"正在保存结果":state.equals("completed")?"已完成":state.equals("paused")?"等待已暂停":state.equals("failed")?"生成失败":"提交结果未确认";
            if((state.equals("in_progress")||state.equals("queued"))&&progress>0)label+=" · "+progress+"%";
            return label+(error.isEmpty()?"":"\n"+error);
        }
    }
}

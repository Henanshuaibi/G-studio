package cn.g.studio;
import android.graphics.BitmapFactory;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class CreativeApi {
    static final class Rejected extends IOException {Rejected(String message){super(message);}}
    static String imageProtocol(Models.Provider p,String model){
        if(p.imageProtocol.equals("responses")||p.imageProtocol.equals("images"))return p.imageProtocol;
        if(!p.imageProtocol.equals("auto"))throw new IllegalArgumentException("请选择生图调用方式");
        return model.trim().equals("gpt-6.1-sol")?"responses":"images";
    }
    static String imagePath(Models.Provider p,String model){return imageProtocol(p,model).equals("responses")?p.responsesPath:p.imagePath;}
    static String protocolLabel(String value){return value.equals("responses")?"Responses 工具生图":value.equals("images")?"Images 直接生图":"自动选择";}
    static final int IMAGE_LIMIT=20*1048576,VIDEO_LIMIT=256*1048576;
    static String route(String base,String path,boolean http){
        String normalized=Endpoint.normalize(base,http);String clean=path.trim();
        if(clean.isEmpty()||!clean.startsWith("/")||clean.startsWith("//")||clean.contains("?")||clean.contains("#")||clean.contains("\\")||clean.contains(".."))throw new IllegalArgumentException("接口路径请填写 /images/generations 或 /videos 这样的路径");
        try{URI uri=new URI(clean);if(uri.getRawAuthority()!=null||uri.getScheme()!=null||uri.getRawPath()==null)throw new Exception();}catch(Exception e){throw new IllegalArgumentException("接口路径不正确");}
        while(clean.endsWith("/"))clean=clean.substring(0,clean.length()-1);if(clean.isEmpty())throw new IllegalArgumentException("接口路径不能为空");return normalized+clean;
    }
    static void validate(Creative.Draft d,Models.Provider p){
        if(!d.kind.equals("image")&&!d.kind.equals("video"))throw new IllegalArgumentException("请选择生图或视频");
        if(d.prompt.trim().isEmpty())throw new IllegalArgumentException("请先填写画面描述");if(d.prompt.length()>12000)throw new IllegalArgumentException("画面描述最多 12000 字符");
        if(d.model.trim().isEmpty())throw new IllegalArgumentException("请填写服务商提供的生成模型 ID");
        route(p.base,d.kind.equals("image")?imagePath(p,d.model):p.videoPath,p.allowHttp);
        if(!d.seconds.trim().isEmpty()&&d.kind.equals("video"))try{int n=Integer.parseInt(d.seconds);if(n<1||n>60)throw new Exception();}catch(Exception e){throw new IllegalArgumentException("视频秒数请填写 1～60 的整数，或留空");}
        if(!d.size.trim().isEmpty()&&!d.size.equals("auto")&&!d.size.matches("[0-9]{2,5}x[0-9]{2,5}"))throw new IllegalArgumentException("尺寸请填写 1024x1024 这样的格式，或留空");
    }
    static JSONObject payload(Creative.Task t)throws Exception{
        if(t.kind.equals("image")&&t.imageProtocol.equals("responses")){
            JSONObject tool=new JSONObject().put("type","image_generation");
            if(!t.toolModel.isEmpty())tool.put("model",t.toolModel);
            if(!t.size.isEmpty())tool.put("size",t.size);if(!t.quality.isEmpty())tool.put("quality",t.quality);
            return new JSONObject().put("model",t.model).put("input",t.prompt).put("tools",new JSONArray().put(tool)).put("tool_choice",new JSONObject().put("type","image_generation")).put("stream",false);
        }
        JSONObject o=new JSONObject().put("model",t.model).put("prompt",t.prompt);
        if(!t.size.isEmpty())o.put("size",t.size);
        if(t.kind.equals("video")){if(!t.seconds.isEmpty())o.put("seconds",t.seconds);}
        else if(!t.quality.isEmpty())o.put("quality",t.quality);
        return o;
    }
    static JSONObject imageResult(JSONObject response,Creative.Task t,String key)throws Exception{
        checkError(response,key);
        if(!t.imageProtocol.equals("responses"))return response;
        String status=response.optString("status");
        if(status.equals("failed")||status.equals("cancelled")||status.equals("incomplete"))throw new Rejected("Responses 生图未完成（"+status+"），请在服务商处核对结果");
        if(status.equals("queued")||status.equals("in_progress"))throw new Rejected("服务返回了异步 Responses 任务，本版需要同步生图结果；请在服务商处核对任务 "+ApiClient.sanitize(response.optString("id"),key));
        JSONArray output=response.optJSONArray("output"),data=new JSONArray();String detail="";
        if(output!=null)for(int i=0;i<output.length();i++){
            JSONObject item=output.optJSONObject(i);if(item==null)continue;
            if(item.optString("type").equals("image_generation_call")){
                String result=item.optString("result");if(item.optString("status").equals("failed")){checkError(item,key);throw new Rejected("图片生成工具执行失败");}
                if(!result.isEmpty())data.put(new JSONObject().put("b64_json",result));
            }else if(item.optString("type").equals("message")){
                JSONArray content=item.optJSONArray("content");if(content!=null)for(int j=0;j<content.length();j++){JSONObject part=content.optJSONObject(j);if(part!=null)detail+=part.optString("refusal",part.optString("text"))+" ";}
            }
        }
        if(data.length()==0)throw new Rejected("服务未返回生成图片，请确认中转站支持 Responses 的 image_generation 工具。"+(detail.trim().isEmpty()?"":"\n"+ApiClient.sanitize(detail,key)));
        return new JSONObject().put("id",response.optString("id")).put("data",data);
    }
    static byte[] multipart(JSONObject payload,String boundary)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();Iterator<String> keys=payload.keys();
        while(keys.hasNext()){String key=keys.next();out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+key+"\"\r\n\r\n"+payload.getString(key)+"\r\n").getBytes(StandardCharsets.UTF_8));}
        out.write(("--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));return out.toByteArray();
    }
    static JSONObject create(Models.Provider p,Creative.Task t,ApiClient.Call call)throws Exception{
        HttpURLConnection c=null;
        try{
            c=ApiClient.open(route(t.base,t.path,p.allowHttp),p,call);c.setReadTimeout(180000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Accept","application/json");
            JSONObject request=payload(t);byte[] bytes;
            if(t.kind.equals("video")&&!t.jsonVideo){String boundary="GStudio"+UUID.randomUUID().toString().replace("-","");c.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);bytes=multipart(request,boundary);}
            else{c.setRequestProperty("Content-Type","application/json; charset=utf-8");bytes=request.toString().getBytes(StandardCharsets.UTF_8);}
            c.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=c.getOutputStream()){out.write(bytes);}call.check();ApiClient.status(c,p);
            try(Reader in=new InputStreamReader(ApiClient.body(c,false),StandardCharsets.UTF_8)){JSONObject result=new JSONObject(ApiClient.read(in,t.kind.equals("image")?40*1048576:1048576));checkError(result,p.key);return result;}
        }finally{if(c!=null)c.disconnect();call.connection=null;}
    }
    static String videoRoute(Creative.Task t,Models.Provider p,boolean content)throws Exception{
        if(t.apiId.isEmpty()||t.apiId.length()>200)throw new IOException("服务没有返回可查询的视频任务 ID");
        String id=URLEncoder.encode(t.apiId,"UTF-8").replace("+","%20");return route(t.base,t.path,p.allowHttp)+"/"+id+(content?"/content":"");
    }
    static JSONObject videoStatus(Models.Provider p,Creative.Task t,ApiClient.Call call)throws Exception{
        HttpURLConnection c=null;try{
            c=ApiClient.open(videoRoute(t,p,false),p,call);c.setReadTimeout(30000);ApiClient.status(c,p);
            try(Reader in=new InputStreamReader(ApiClient.body(c,false),StandardCharsets.UTF_8)){JSONObject o=new JSONObject(ApiClient.read(in,1048576));return o;}
        }finally{if(c!=null)c.disconnect();call.connection=null;}
    }
    static void checkError(JSONObject o,String key)throws Exception{Object error=o.opt("error");if(error!=null&&error!=JSONObject.NULL)throw new Rejected(ApiClient.sanitize(error instanceof JSONObject?((JSONObject)error).optString("message","生成失败"):String.valueOf(error),key));}
    static void applyVideo(Creative.Task t,JSONObject response,String key)throws Exception{
        JSONObject o=response.optJSONObject("data");if(o==null)o=response;
        if(o.has("id"))t.apiId=o.optString("id");t.progress=Math.max(0,Math.min(100,o.optInt("progress",t.progress)));
        String url=o.optString("url",o.optString("video_url",t.url));if(!url.isEmpty())t.url=url;
        String status=o.optString("status",t.url.isEmpty()?"queued":"completed");
        if(status.equals("failed")||status.equals("cancelled")){t.state="failed";checkError(o,key);throw new IOException("视频任务"+(status.equals("failed")?"生成失败":"已被服务取消"));}
        checkError(o,key);
        if(status.equals("completed")||status.equals("succeeded")){t.state="downloading";t.progress=100;}
        else if(status.equals("queued")||status.equals("pending"))t.state="queued";
        else if(status.equals("in_progress")||status.equals("processing")||status.equals("running"))t.state="in_progress";
        else throw new IOException("服务返回了未支持的视频状态："+status);
        if(t.apiId.isEmpty()&&t.url.isEmpty())throw new IOException("服务没有返回视频任务 ID，请检查接口协议");
    }
    static boolean sameOrigin(String a,String b)throws Exception{
        URI x=new URI(a),y=new URI(b);int xp=x.getPort()<0?(x.getScheme().equalsIgnoreCase("https")?443:80):x.getPort(),yp=y.getPort()<0?(y.getScheme().equalsIgnoreCase("https")?443:80):y.getPort();
        return x.getScheme().equalsIgnoreCase(y.getScheme())&&x.getHost().equalsIgnoreCase(y.getHost())&&xp==yp;
    }
    static String mediaUrl(String url,String base,boolean http)throws Exception{
        URI u=new URI(base).resolve(url);String scheme=u.getScheme();if((!"https".equalsIgnoreCase(scheme)&&!"http".equalsIgnoreCase(scheme))||u.getHost()==null||u.getUserInfo()!=null||u.getFragment()!=null)throw new IOException("生成结果地址格式不正确");
        if("http".equalsIgnoreCase(scheme)&&!http)throw new IOException("结果使用 HTTP，请在服务配置中明确允许 HTTP 后再保存");return u.toASCIIString();
    }
    static void download(String url,String base,Models.Provider p,ApiClient.Call call,File output,int limit)throws Exception{
        String next=mediaUrl(url,base,p.allowHttp);boolean ok=false;HttpURLConnection c=null;
        try{
            for(int redirect=0;redirect<=4;redirect++){
                call.check();boolean auth=sameOrigin(next,base);Models.Provider safe=p.copy();if(!auth)safe.key="";
                c=ApiClient.open(next,safe,call);c.setReadTimeout(120000);c.setRequestProperty("Accept","*/*");int code=c.getResponseCode();
                if(code>=300&&code<400){String location=c.getHeaderField("Location");if(location==null||redirect==4)throw new IOException("结果下载重定向过多或没有目标地址");next=mediaUrl(location,next,p.allowHttp);c.disconnect();c=null;continue;}
                ApiClient.status(c,safe);long length=c.getContentLengthLong();if(length>limit)throw new IOException("生成结果文件超过大小限制");
                try(InputStream in=ApiClient.body(c,false);OutputStream out=new FileOutputStream(output)){
                    byte[] b=new byte[32768];int n;long count=0;while((n=in.read(b))!=-1){call.check();count+=n;if(count>limit)throw new IOException("生成结果文件超过大小限制");out.write(b,0,n);}if(count==0)throw new IOException("服务返回了空文件");
                }call.check();ok=true;return;
            }
        }finally{if(c!=null)c.disconnect();call.connection=null;if(!ok)output.delete();}
    }
    static Creative.Asset saveImage(CreativeStore store,Creative.Task t,JSONObject item,int index,Models.Provider p,ApiClient.Call call)throws Exception{
        File temp=new File(store.media,Models.id()+".part");boolean ok=false;
        try{
            String data=item.optString("b64_json");if(data.isEmpty()){String url=item.optString("url");if(url.startsWith("data:image/")){int comma=url.indexOf(',');if(comma<0)throw new IOException("图片数据格式错误");data=url.substring(comma+1);}else if(!url.isEmpty())download(url,t.base,p,call,temp,IMAGE_LIMIT);else throw new IOException("服务没有返回图片数据");}
            if(!data.isEmpty()){
                call.check();if(data.length()>28*1048576)throw new IOException("图片结果超过 20 MB");byte[] bytes=Base64.decode(data,Base64.DEFAULT);if(bytes.length>IMAGE_LIMIT)throw new IOException("图片结果超过 20 MB");try(OutputStream out=new FileOutputStream(temp)){out.write(bytes);}
            }
            BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(temp.getPath(),o);if(o.outWidth<1||o.outHeight<1)throw new IOException("服务返回的内容不是可读取的图片");
            String mime=o.outMimeType,ext="image/jpeg".equals(mime)?"jpg":"image/webp".equals(mime)?"webp":"image/gif".equals(mime)?"gif":"image/png".equals(mime)?"png":"";if(ext.isEmpty())throw new IOException("服务返回了暂不支持的图片格式");
            if((long)o.outWidth*o.outHeight>100000000L)throw new IOException("生成图片尺寸过大，无法在本机预览");
            o.inSampleSize=1;while(Math.max(o.outWidth,o.outHeight)/o.inSampleSize>512)o.inSampleSize*=2;o.inJustDecodeBounds=false;
            android.graphics.Bitmap decoded=BitmapFactory.decodeFile(temp.getPath(),o);if(decoded==null)throw new IOException("生成图片数据不完整或无法解码");decoded.recycle();
            call.check();Creative.Asset asset=new Creative.Asset();asset.file=Models.id()+"."+ext;asset.mime=mime;asset.index=index;asset.name="G-studio-"+t.id.substring(0,8)+"-"+(index+1)+"."+ext;asset.bytes=temp.length();if(!temp.renameTo(store.asset(asset.file)))throw new IOException("保存图片失败");ok=true;return asset;
        }finally{if(!ok)temp.delete();}
    }
    static Creative.Asset saveVideo(CreativeStore store,Creative.Task t,Models.Provider p,ApiClient.Call call)throws Exception{
        File temp=new File(store.media,Models.id()+".part");boolean ok=false;
        try{
            download(t.url.isEmpty()?videoRoute(t,p,true):t.url,t.base,p,call,temp,VIDEO_LIMIT);
            byte[] head=new byte[12];try(InputStream in=new FileInputStream(temp)){if(in.read(head)!=12||head[4]!='f'||head[5]!='t'||head[6]!='y'||head[7]!='p')throw new IOException("服务返回的结果不是 MP4 视频");}
            call.check();Creative.Asset asset=new Creative.Asset();asset.file=Models.id()+".mp4";asset.mime="video/mp4";asset.name="G-studio-"+t.id.substring(0,8)+".mp4";asset.bytes=temp.length();if(!temp.renameTo(store.asset(asset.file)))throw new IOException("保存视频失败");ok=true;return asset;
        }finally{if(!ok)temp.delete();}
    }
}

package cn.g.studio;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

final class ApiClient {
    interface Delta {void update(String text,String reasoning);}
    static final class Call {
        volatile boolean cancelled;volatile HttpURLConnection connection;
        void cancel(){cancelled=true;HttpURLConnection c=connection;if(c!=null)c.disconnect();}
        void check()throws IOException{if(cancelled)throw new IOException("已停止生成");}
    }
    static final class Result {String text="",reasoning="",usage="",finish="";boolean terminal;}
    static JSONObject request(Models.Provider p,List<Models.Message> history)throws Exception{
        return request(p,history,null);
    }
    static JSONObject request(Models.Provider p,List<Models.Message> history,AttachmentStore store)throws Exception{
        return request(p,history,store,"");
    }
    static JSONObject request(Models.Provider p,List<Models.Message> history,AttachmentStore store,String memory)throws Exception{
        if(p.model.trim().isEmpty())throw new IllegalArgumentException("请先填写或选择模型");
        JSONArray messages=new JSONArray();
        String system=p.system;if(!memory.isEmpty())system+=(system.trim().isEmpty()?"":"\n\n")+memory;
        if(!system.trim().isEmpty())messages.put(new JSONObject().put("role","system").put("content",system));
        int start=0,users=0;
        for(int i=history.size()-1;i>=0;i--)if(history.get(i).role.equals("user")&&++users>p.contextTurns){start=i+1;break;}
        while(start<history.size()&&!history.get(start).role.equals("user"))start++;
        long total=0;
        for(int i=start;i<history.size();i++)if(history.get(i).role.equals("user"))for(Models.Attachment a:history.get(i).attachments){
            total+=a.kind.equals("text")?a.text.length()*3L:a.size;
            if(total>AttachmentStore.MAX_CONTEXT)throw new IOException("上下文附件过大，请减少携带的对话轮数或开启新对话");
        }
        for(int i=start;i<history.size();i++){
            Models.Message m=history.get(i);
            if(m.role.equals("user")||(m.role.equals("assistant")&&m.status.equals("complete")&&!m.text.isEmpty()))
            {
                if(!m.attachments.isEmpty()&&store==null)throw new IOException("附件存储不可用");
                messages.put(new JSONObject().put("role",m.role).put("content",m.attachments.isEmpty()?m.text:store.content(m)));
            }
        }
        JSONObject o=new JSONObject().put("model",p.model.trim()).put("messages",messages).put("stream",p.stream);
        if(!p.temperature.trim().isEmpty()){
            double t=Double.parseDouble(p.temperature.trim());
            if(Double.isNaN(t)||Double.isInfinite(t)||t<0||t>2)throw new IllegalArgumentException("温度请填写 0～2，或留空");
            o.put("temperature",t);
        }return o;
    }
    static HttpURLConnection open(String url,Models.Provider p,Call call)throws Exception{
        call.check();HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();call.connection=c;
        c.setConnectTimeout(20000);c.setReadTimeout(90000);c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept","application/json, text/event-stream");c.setRequestProperty("Accept-Encoding","gzip");
        c.setRequestProperty("User-Agent","G-studio/0.9");
        String key=p.key.trim();if(key.indexOf('\n')>=0||key.indexOf('\r')>=0)throw new IllegalArgumentException("API Key 不能包含换行");
        if(!key.isEmpty())c.setRequestProperty("Authorization","Bearer "+key);
        call.check();return c;
    }
    static InputStream body(HttpURLConnection c,boolean error)throws Exception{
        InputStream in=error?c.getErrorStream():c.getInputStream();
        if(in==null)return new ByteArrayInputStream(new byte[0]);
        return "gzip".equalsIgnoreCase(c.getContentEncoding())?new GZIPInputStream(in):in;
    }
    static String read(Reader r,int max)throws Exception{
        StringBuilder s=new StringBuilder();char[] b=new char[2048];int n;
        while((n=r.read(b))!=-1){if(s.length()+n>max)throw new IOException("服务器返回内容过大");s.append(b,0,n);}return s.toString();
    }
    static String sanitize(String text,String key){
        if(text==null)return "未知错误";
        if(key!=null&&!key.isEmpty())text=text.replace(key,"[密钥已隐藏]");
        text=text.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+","Bearer [已隐藏]");
        return text.length()>600?text.substring(0,600)+"…":text;
    }
    static void status(HttpURLConnection c,Models.Provider p)throws Exception{
        int code=c.getResponseCode();if(code>=200&&code<300)return;
        if(code>=300&&code<400)throw new IOException("地址发生重定向（HTTP "+code+"），请填写最终 API 地址");
        String detail="";
        try(Reader r=new InputStreamReader(body(c,true),StandardCharsets.UTF_8)){
            char[] b=new char[4096];int n=r.read(b);if(n>0)detail=new String(b,0,n);
        }
        try{
            JSONObject o=new JSONObject(detail);Object e=o.opt("error");
            if(e instanceof JSONObject)detail=((JSONObject)e).optString("message",detail);
            else if(e instanceof String)detail=(String)e;else detail=o.optString("message",detail);
        }catch(Exception ignored){if(detail.trim().startsWith("<"))detail="服务器返回了网页，请检查 API 地址";}
        String hint=code==401?"密钥无效或没有权限":code==403?"访问被拒绝":code==404?"接口或模型不存在":code==429?"请求过多或额度不足":code>=500?"中转站或模型服务暂时异常":"请求失败";
        throw new IOException(hint+"（HTTP "+code+"）"+(detail.isEmpty()?"":"\n"+sanitize(detail,p.key)));
    }
    static String content(Object value){
        if(value instanceof String)return(String)value;
        if(value instanceof JSONArray){
            StringBuilder b=new StringBuilder();JSONArray a=(JSONArray)value;
            for(int i=0;i<a.length();i++){JSONObject v=a.optJSONObject(i);if(v!=null)b.append(v.optString("text",""));}
            return b.toString();
        }return "";
    }
    static void consume(JSONObject o,Result out,Delta callback,boolean streamed)throws Exception{
        if(o.has("error")&&!o.isNull("error")){Object e=o.get("error");throw new IOException(e instanceof JSONObject?((JSONObject)e).optString("message","服务返回错误"):String.valueOf(e));}
        JSONObject usage=o.optJSONObject("usage");
        if(usage!=null)out.usage="输入 "+usage.optLong("prompt_tokens",0)+" · 输出 "+usage.optLong("completion_tokens",0)+" tokens";
        JSONArray choices=o.optJSONArray("choices");if(choices==null||choices.length()==0)return;
        JSONObject choice=choices.optJSONObject(0);if(choice==null)return;
        JSONObject delta=choice.optJSONObject("delta");if(delta==null)delta=choice.optJSONObject("message");
        if(delta!=null){
            String text=content(delta.opt("content"));if(text.isEmpty())text=content(delta.opt("refusal"));
            String reason=content(delta.opt("reasoning_content"));if(reason.isEmpty())reason=content(delta.opt("reasoning"));
            if(out.text.length()+out.reasoning.length()+text.length()+reason.length()>120000)throw new IOException("回复过长，已保留收到的内容");
            out.text+=text;out.reasoning+=reason;
            if(!text.isEmpty()||!reason.isEmpty())callback.update(out.text,out.reasoning);
        }
        if(!choice.isNull("finish_reason")){out.finish=choice.optString("finish_reason");out.terminal=true;}
        if(!streamed)out.terminal=true;
    }
    static Result chat(Models.Provider p,JSONObject request,Call call,Delta callback)throws Exception{
        HttpURLConnection c=null;
        try{
            c=open(Endpoint.chat(p.base,p.allowHttp),p,call);c.setRequestMethod("POST");c.setDoOutput(true);
            c.setRequestProperty("Content-Type","application/json; charset=utf-8");
            byte[] bytes=request.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>20*1048576)throw new IOException("请求过大，请减少附件或携带的对话轮数");c.setFixedLengthStreamingMode(bytes.length);
            try(OutputStream out=c.getOutputStream()){out.write(bytes);}call.check();status(c,p);
            Result result=new Result();
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(body(c,false),StandardCharsets.UTF_8))){
                reader.mark(4096);int first;do{first=reader.read();}while(first!=-1&&(Character.isWhitespace(first)||first==0xfeff));reader.reset();
                if(first=='{')consume(new JSONObject(read(reader,1048576)),result,callback,false);
                else Sse.read(reader,data->{
                    call.check();if(data.trim().equals("[DONE]")){result.terminal=true;return false;}
                    consume(new JSONObject(data),result,callback,true);return true;
                });
            }
            call.check();
            if(!result.terminal)throw new IOException("连接提前结束，回复可能不完整，可重新生成");
            if(result.text.isEmpty())throw new IOException(result.reasoning.isEmpty()?"服务没有返回文字，请检查模型是否支持聊天":"只收到思考内容，没有收到最终回答");
            return result;
        }catch(Exception e){if(call.cancelled)throw new IOException("已停止生成");throw new IOException(sanitize(e.getMessage(),p.key),e);}
        finally{if(c!=null)c.disconnect();call.connection=null;}
    }
    static ArrayList<String> models(Models.Provider p,Call call)throws Exception{
        HttpURLConnection c=null;
        try{
            c=open(Endpoint.models(p.base,p.allowHttp),p,call);status(c,p);
            String json;try(Reader r=new InputStreamReader(body(c,false),StandardCharsets.UTF_8)){json=read(r,2*1048576);}
            JSONObject o=new JSONObject(json);JSONArray a=o.optJSONArray("data");if(a==null)a=o.optJSONArray("models");
            if(a==null)throw new IOException("接口没有返回模型列表，可手动填写模型 ID");
            TreeSet<String> ids=new TreeSet<>();
            for(int i=0;i<a.length();i++){Object v=a.opt(i);String id=v instanceof JSONObject?((JSONObject)v).optString("id"):v instanceof String?(String)v:"";if(!id.isEmpty())ids.add(id);}
            call.check();return new ArrayList<>(ids);
        }catch(Exception e){throw new IOException(sanitize(e.getMessage(),p.key),e);}
        finally{if(c!=null)c.disconnect();call.connection=null;}
    }
}

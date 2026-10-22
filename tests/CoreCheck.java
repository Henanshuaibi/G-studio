package cn.g.studio;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class CoreCheck {
    static int checks;
    static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    interface Attempt{void run()throws Exception;}
    static void fails(Attempt run,String contains)throws Exception{
        try{run.run();throw new AssertionError("expected failure: "+contains);}
        catch(AssertionError e){throw e;}
        catch(Exception e){check(e.getMessage()!=null&&e.getMessage().contains(contains),"error: "+contains+" actual: "+e);}
    }
    static final class Server implements AutoCloseable{
        final ServerSocket socket=new ServerSocket(0,5,InetAddress.getByName("127.0.0.1"));
        final CountDownLatch got=new CountDownLatch(1);
        volatile String request="",headers="";volatile Throwable failure;
        final Thread thread;
        Server(int status,String type,String body)throws Exception{
            thread=new Thread(()->{
                try(Socket c=socket.accept()){
                    c.setSoTimeout(5000);InputStream in=c.getInputStream();ByteArrayOutputStream h=new ByteArrayOutputStream();int b,state=0;
                    while((b=in.read())!=-1){
                        h.write(b);state=(state==0&&b==13)?1:(state==1&&b==10)?2:(state==2&&b==13)?3:(state==3&&b==10)?4:0;
                        if(state==4)break;
                    }
                    headers=new String(h.toByteArray(),StandardCharsets.UTF_8);
                    int length=0;for(String line:headers.split("\r\n"))if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());
                    byte[] data=new byte[length];int count=0,n;while(count<length&&(n=in.read(data,count,length-count))>0)count+=n;
                    request=new String(data,StandardCharsets.UTF_8);
                    byte[] reply=body.getBytes(StandardCharsets.UTF_8);
                    OutputStream out=c.getOutputStream();
                    out.write(("HTTP/1.1 "+status+" Test\r\nContent-Type: "+type+"\r\nContent-Length: "+reply.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    for(int i=0;i<reply.length;i+=7){out.write(reply,i,Math.min(7,reply.length-i));out.flush();}
                }catch(Throwable e){failure=e;}finally{got.countDown();}
            });thread.start();
        }
        String base(){return "http://127.0.0.1:"+socket.getLocalPort()+"/v1";}
        void await()throws Exception{check(got.await(6,TimeUnit.SECONDS),"server completed");if(failure!=null)throw new Exception(failure);}
        public void close()throws Exception{socket.close();thread.join(1000);}
    }
    public static void main(String[] args){
        try{run(new File(args[0]));AttachmentCheck.run(new File(args[0]));CreativeCheck.run(new File(args[0]));FeatureCheck.run(new File(args[0]));ReviewCheck.run(new File(args[0]));System.out.println("PASS: "+checks+" assertions (endpoints, SSE, real HTTP, context, persistence, encryption, attachments, image/video generation)");System.exit(0);}
        catch(Throwable e){e.printStackTrace();System.exit(1);}
    }
    static Models.Provider provider(){Models.Provider p=new Models.Provider();p.model="test-model";p.key="test-secret-not-real";p.allowHttp=true;return p;}
    static void run(File dir)throws Exception{
        check(Endpoint.chat("https://example.com/",false).equals("https://example.com/v1/chat/completions"),"root appends v1");
        check(Endpoint.chat("https://example.com/v1/",false).equals("https://example.com/v1/chat/completions"),"v1 once");
        check(Endpoint.models("https://example.com/gateway/v1/chat/completions",false).equals("https://example.com/gateway/v1/models"),"full endpoint retained prefix");
        check(Endpoint.chat("https://example.com/api",false).equals("https://example.com/api/chat/completions"),"custom path preserved");
        check(Endpoint.models("http://127.0.0.1:8080/v1",true).equals("http://127.0.0.1:8080/v1/models"),"local HTTP opt-in");
        fails(()->Endpoint.chat("http://example.com",false),"HTTP");
        fails(()->Endpoint.chat("https://a.example/?api_key=secret",false),"纯 API");
        fails(()->Endpoint.chat("https://user:secret@a.example",false),"纯 API");
        fails(()->Endpoint.chat("file:///tmp/a",true),"https://");
        ArrayList<String> events=new ArrayList<>();
        Sse.read(new StringReader("\ufeff: ping\r\nevent: message\r\ndata: one\r\ndata: two\r\n\r\ndata: [DONE]\n\ndata: ignored\n\n"),v->{events.add(v);return !v.equals("[DONE]");});
        check(events.equals(Arrays.asList("one\ntwo","[DONE]")),"SSE BOM, CRLF, multiline, done");
        events.clear();Sse.read(new StringReader("data: end"),v->{events.add(v);return true;});
        check(events.equals(Arrays.asList("end")),"final event without newline");
        Models.Provider p=provider();p.contextTurns=1;p.system="简短回答";
        ArrayList<Models.Message> history=new ArrayList<>();
        history.add(new Models.Message("user","old"));history.add(new Models.Message("assistant","old answer"));history.add(new Models.Message("user","你好"));
        JSONObject payload=ApiClient.request(p,history);JSONArray a=payload.getJSONArray("messages");
        check(a.length()==2&&a.getJSONObject(1).getString("content").equals("你好"),"context budget starts on user");
        check(!payload.has("temperature")&&!payload.has("stream_options"),"optional provider parameters omitted");
        check(!payload.toString().contains(p.key)&&!p.json().toString().contains(p.key),"key not serialized in requests or config data");
        p.temperature="NaN";fails(()->ApiClient.request(p,history),"温度");p.temperature="";
        String stream=": heartbeat\n\ndata: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}\n\n"
          +"data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"先想一想\"}}]}\n\n"
          +"data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好🌿\"},\"finish_reason\":null}]}\n\n"
          +"data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"，世界\"},\"finish_reason\":\"stop\"}]}\n\n"
          +"data: {\"choices\":[],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":9}}\n\ndata: [DONE]\n\n";
        try(Server server=new Server(200,"text/event-stream",stream)){
            p.base=server.base();ArrayList<String> partials=new ArrayList<>();
            ApiClient.Result r=ApiClient.chat(p,ApiClient.request(p,history),new ApiClient.Call(),(text,reason)->partials.add(text));
            server.await();
            check(r.text.equals("你好🌿，世界")&&r.reasoning.equals("先想一想"),"real streamed Unicode and reasoning");
            check(r.usage.contains("输出 9")&&r.terminal,"usage and completion");
            check(partials.size()>=2,"incremental callbacks");
            check(server.headers.startsWith("POST /v1/chat/completions"),"request route");
            check(server.headers.toLowerCase(Locale.ROOT).contains("authorization: bearer "+p.key),"bearer authentication");
            check(new JSONObject(server.request).getJSONArray("messages").length()==2,"HTTP request payload");
        }
        try(Server server=new Server(200,"application/json","{\"choices\":[{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"完整回答\"}]},\"finish_reason\":\"stop\"}]}")){
            p.base=server.base();ApiClient.Result r=ApiClient.chat(p,ApiClient.request(p,history),new ApiClient.Call(),(x,y)->{});
            check(r.text.equals("完整回答"),"JSON fallback when stream requested");server.await();
        }
        try(Server server=new Server(200,"application/json","{\"data\":[{\"id\":\"z\"},{\"id\":\"a\"},{\"id\":\"a\"}]}")){
            p.base=server.base();check(ApiClient.models(p,new ApiClient.Call()).equals(Arrays.asList("a","z")),"models sorted and deduplicated");server.await();
            check(server.headers.startsWith("GET /v1/models"),"models route");
        }
        try(Server server=new Server(401,"application/json","{\"error\":{\"message\":\"bad "+p.key+"\"}}")){
            p.base=server.base();try{ApiClient.models(p,new ApiClient.Call());throw new AssertionError("401 rejected");}
            catch(IOException e){check(e.getMessage().contains("401")&&!e.getMessage().contains(p.key),"401 descriptive, secret removed");}server.await();
        }
        try(Server server=new Server(302,"text/html","moved")){
            p.base=server.base();fails(()->ApiClient.models(p,new ApiClient.Call()),"重定向");server.await();
        }
        try(Server server=new Server(200,"text/event-stream","data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n")){
            p.base=server.base();fails(()->ApiClient.chat(p,ApiClient.request(p,history),new ApiClient.Call(),(x,y)->{}),"提前结束");server.await();
        }
        try(Server server=new Server(200,"text/event-stream","data: {\"error\":{\"message\":\"upstream failed\"}}\n\n")){
            p.base=server.base();fails(()->ApiClient.chat(p,ApiClient.request(p,history),new ApiClient.Call(),(x,y)->{}),"upstream failed");server.await();
        }
        try(Server server=new Server(200,"text/event-stream",stream)){
            p.base=server.base();ApiClient.Call call=new ApiClient.Call();
            fails(()->ApiClient.chat(p,ApiClient.request(p,history),call,(x,y)->call.cancelled=true),"已停止");server.await();
        }
        // app_process does not install providers through ActivityThread.bindApplication.
        Class.forName("android.security.keystore2.AndroidKeyStoreProvider").getMethod("install").invoke(null);
        String alias="g_studio_test_"+UUID.randomUUID();SecureKey vault=new SecureKey(alias);
        try{
            String encrypted=vault.seal(p.key);
            check(!encrypted.contains(p.key)&&vault.open(encrypted).equals(p.key),"real Android Keystore encryption roundtrip");
            check(!encrypted.equals(vault.seal(p.key)),"randomized encryption");
            LocalStore store=new LocalStore(new File(dir,"data-"+UUID.randomUUID()),vault);
            LocalStore.Config config=new LocalStore.Config();config.providers.add(p);config.selected=p.id;store.saveConfig(config);
            check(store.loadConfig().providers.get(0).key.equals(p.key),"provider credentials roundtrip");
            String configRaw=new String(Files.readAllBytes(new File(store.root,"config.json").toPath()),StandardCharsets.UTF_8);
            check(!configRaw.contains(p.key),"no plaintext key on disk");
            Models.Chat chat=new Models.Chat();chat.title="测试会话";chat.draft="未发送";chat.providerId=p.id;
            chat.messages.add(new Models.Message("user","hello"));
            Models.Message unfinished=new Models.Message("assistant","partial");unfinished.status="streaming";chat.messages.add(unfinished);
            store.saveChat(chat);Models.Chat restored=store.loadChat(chat.id);
            check(restored.messages.get(1).status.equals("stopped")&&!restored.messages.get(1).error.isEmpty(),"interrupted response recovered");
            check(restored.draft.equals("未发送")&&restored.providerId.equals(p.id),"draft and provider survive restart");
            check(store.listChats().size()==1,"history listing");
            fails(()->store.loadChat("../config"),"编号");
            store.deleteChat(chat.id);check(store.listChats().isEmpty(),"conversation deletion");
        }finally{java.security.KeyStore ks=java.security.KeyStore.getInstance("AndroidKeyStore");ks.load(null);ks.deleteEntry(alias);}
        check(Markdown.render("# 标题\n**粗体**").toString().equals("标题\n粗体"),"native Markdown formatting");
    }
}

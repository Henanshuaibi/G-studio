package cn.g.studio;
import static cn.g.studio.CoreCheck.check;
import static cn.g.studio.CoreCheck.fails;
import android.graphics.*;
import android.os.*;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

final class FeatureCheck {
    static void run(File dir)throws Exception{
        memory(dir);responses(dir);
    }
    static void memory(File dir)throws Exception{
        File root=new File(dir,"memory-check-"+Models.id());check(root.mkdirs(),"memory test root");MemoryStore store=new MemoryStore(root);
        check(store.enabled()&&store.context().isEmpty(),"old install has empty memory without changing requests");
        store.save("","我用 iQOO 13，回答用简洁中文");store.save("","所在地：中国大陆");
        MemoryStore copy=new MemoryStore(root);check(copy.list().size()==2&&copy.context().contains("iQOO 13"),"memory survives restart");
        String id=copy.list().get(0).id;copy.save(id,"所在地：天津");check(copy.list().size()==2&&copy.context().contains("天津")&&!copy.context().contains("所在地：中国大陆"),"memory edits replace saved content");
        copy.list().clear();check(copy.list().size()==2,"memory list cannot mutate store");
        Models.Provider p=CoreCheck.provider();p.system="你是一位助手";p.contextTurns=1;
        ArrayList<Models.Message> history=new ArrayList<>();history.add(new Models.Message("user","旧提问"));history.add(new Models.Message("assistant","旧回复"));history.add(new Models.Message("user","我用什么手机？"));
        JSONObject request=ApiClient.request(p,history,null,copy.context());JSONArray messages=request.getJSONArray("messages");
        check(messages.length()==2&&messages.getJSONObject(0).getString("role").equals("system")&&messages.getJSONObject(0).getString("content").contains(p.system)&&messages.getJSONObject(0).getString("content").contains("iQOO 13"),"memory and assistant settings survive context trimming");
        ArrayList<Models.Message> fresh=new ArrayList<>();fresh.add(new Models.Message("user","你好"));
        check(ApiClient.request(p,fresh,null,copy.context()).getJSONArray("messages").getJSONObject(0).getString("content").contains("天津"),"new conversation shares saved memory");
        try(CoreCheck.Server server=new CoreCheck.Server(200,"application/json","{\"choices\":[{\"message\":{\"content\":\"记得\"},\"finish_reason\":\"stop\"}]}")){
            p.base=server.base();ApiClient.chat(p,request,new ApiClient.Call(),(text,reason)->{});server.await();
            check(new JSONObject(server.request).getJSONArray("messages").getJSONObject(0).getString("content").contains("iQOO 13"),"memory reaches actual HTTP chat body");
        }
        copy.setEnabled(false);MemoryStore disabled=new MemoryStore(root);
        check(!disabled.enabled()&&disabled.context().isEmpty()&&disabled.list().size()==2,"disabled memory persists without deleting content");
        String disabledRequest=ApiClient.request(p,history,null,disabled.context()).toString();check(!disabledRequest.contains("iQOO 13")&&disabledRequest.contains(p.system),"disabled memory is absent from request");
        int before=disabled.list().size();fails(()->disabled.save("","   "),"填写");fails(()->disabled.save("",repeat('x',3001)),"3000");fails(()->disabled.save("missing","text"),"已被删除");
        check(disabled.list().size()==before,"invalid memory edits leave saved state intact");
        disabled.delete(id);check(!disabled.context().contains("天津")&&disabled.list().size()==1,"memory deletion persists");disabled.setEnabled(true);check(!disabled.context().isEmpty(),"reenabling restores remaining memory");
        disabled.clear();check(disabled.context().isEmpty()&&new MemoryStore(root).list().isEmpty(),"clearing memory removes disk entries");
        for(int i=0;i<8;i++)disabled.save("",repeat('x',3000));fails(()->disabled.save("","overflow"),"24000");check(new MemoryStore(root).list().size()==8,"memory total size bound is atomic");disabled.clear();
        for(int i=0;i<100;i++)disabled.save("","条目"+i);fails(()->disabled.save("","overflow"),"100");check(disabled.list().size()==100,"memory count bound");
        MemoryStore broken=new MemoryStore(root);Files.write(new File(root,"memory.json").toPath(),"broken".getBytes("UTF-8"));
        try{new MemoryStore(root);throw new AssertionError("corrupt memory rejected");}catch(org.json.JSONException expected){check(new String(Files.readAllBytes(new File(root,"memory.json").toPath()),"UTF-8").equals("broken"),"corrupt memory preserved for recovery");}
        File failureRoot=new File(dir,"memory-failure-"+Models.id());check(failureRoot.mkdirs(),"memory write failure root");MemoryStore failure=new MemoryStore(failureRoot);check(failureRoot.delete(),"remove empty memory test root");Files.write(failureRoot.toPath(),new byte[0]);
        try{failure.save("","must not commit");throw new AssertionError("write failure rejected");}catch(IOException expected){check(failure.list().isEmpty(),"failed memory disk write does not mutate live state");}
    }
    static String repeat(char c,int n){char[] chars=new char[n];Arrays.fill(chars,c);return new String(chars);}
    static void responses(File dir)throws Exception{
        Models.Provider p=CoreCheck.provider();p.base="http://127.0.0.1:9/v1";
        check(CreativeApi.imageProtocol(p,"gpt-6.1-sol").equals("responses")&&CreativeApi.imagePath(p,"gpt-6.1-sol").equals("/responses"),"gpt-6.1-sol automatically selects Responses before any POST");
        check(CreativeApi.imageProtocol(p,"gpt-image-1").equals("images"),"native image models keep Images protocol");p.imageProtocol="images";check(CreativeApi.imageProtocol(p,"gpt-6.1-sol").equals("images"),"explicit Images override retained");p.imageProtocol="responses";
        p.imageToolModel="mock-image-tool";p.responsesPath="/custom/responses";Models.Provider restored=Models.Provider.from(p.json());
        check(restored.imageProtocol.equals("responses")&&restored.responsesPath.equals(p.responsesPath)&&p.copy().imageToolModel.equals(p.imageToolModel),"Responses protocol paths and tool model persist");
        check(Endpoint.normalize("https://example.com/prefix/v1/responses",false).equals("https://example.com/prefix/v1"),"full Responses URL retains gateway prefix");
        Creative.Task t=CreativeCheck.task(p,"image");t.model="gpt-6.1-sol";t.path=p.responsesPath;t.imageProtocol="responses";t.size="1024x1024";t.quality="high";
        JSONObject request=CreativeApi.payload(t),tool=request.getJSONArray("tools").getJSONObject(0);
        check(request.getString("model").equals("gpt-6.1-sol")&&request.getString("input").equals(t.prompt)&&!request.has("prompt"),"Responses preserves exact requested main model and input");
        check(tool.getString("type").equals("image_generation")&&tool.getString("size").equals(t.size)&&tool.getString("quality").equals("high")&&!tool.has("model")&&!request.has("size"),"image options scoped to tool with provider default image model");
        check(!request.getBoolean("stream")&&request.getJSONObject("tool_choice").getString("type").equals("image_generation"),"Responses uses synchronous forced image tool");
        t.toolModel="mock-image-tool";check(CreativeApi.payload(t).getJSONArray("tools").getJSONObject(0).getString("model").equals(t.toolModel)&&CreativeApi.payload(t).getString("model").equals("gpt-6.1-sol"),"tool model never replaces selected main model");
        check(Creative.Task.from(t.json()).imageProtocol.equals("responses")&&Creative.Task.from(t.json()).toolModel.equals(t.toolModel),"in-flight task protocol survives restart");
        check(Creative.Task.from(new JSONObject().put("model","legacy-image")).imageProtocol.equals("images"),"legacy tasks stay on original Images protocol");
        byte[] png;Bitmap bitmap=Bitmap.createBitmap(6,8,Bitmap.Config.ARGB_8888);try{ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,out);png=out.toByteArray();}finally{bitmap.recycle();}
        JSONObject image=new JSONObject().put("type","image_generation_call").put("status","completed").put("result",Base64.encodeToString(png,Base64.NO_WRAP));
        JSONObject response=new JSONObject().put("id","resp_image_test").put("status","completed").put("output",new JSONArray().put(new JSONObject().put("type","reasoning")).put(image));
        JSONObject normalized=CreativeApi.imageResult(response,t,p.key);check(normalized.getJSONArray("data").length()==1&&normalized.getString("id").equals("resp_image_test"),"Responses image output normalizes to cached images");
        try(CreativeCheck.Server server=new CreativeCheck.Server(new CreativeCheck.Reply(response.toString()))){
            p.base=server.base();t.base=p.base;JSONObject result=CreativeApi.create(p,t,new ApiClient.Call());server.await();
            check(server.headers.get(0).startsWith("POST /v1/custom/responses ")&&server.headers.get(0).contains("Bearer "+p.key),"Responses actual HTTP route and auth");
            check(new JSONObject(server.requests.get(0)).getString("model").equals("gpt-6.1-sol"),"HTTP main model exact");
            CreativeStore assets=new CreativeStore(new File(dir,"responses-assets-"+Models.id()));Creative.Asset asset=CreativeApi.saveImage(assets,t,CreativeApi.imageResult(result,t,p.key).getJSONArray("data").getJSONObject(0),0,p,new ApiClient.Call());
            check(Arrays.equals(png,Files.readAllBytes(assets.asset(asset.file).toPath())),"Responses image is decoded and saved intact");
        }
        final Creative.Task task=t;
        fails(()->CreativeApi.imageResult(new JSONObject().put("status","completed").put("output",new JSONArray()),task,p.key),"未返回生成图片");
        fails(()->CreativeApi.imageResult(new JSONObject().put("status","incomplete"),task,p.key),"未完成");
        fails(()->CreativeApi.imageResult(new JSONObject().put("status","queued").put("id","resp_async"),task,p.key),"异步");
        JSONObject refusal=new JSONObject().put("output",new JSONArray().put(new JSONObject().put("type","message").put("content",new JSONArray().put(new JSONObject().put("type","refusal").put("refusal","cannot draw "+p.key)))));
        try{CreativeApi.imageResult(refusal,task,p.key);throw new AssertionError("text refusal not image");}catch(IOException expected){check(expected.getMessage().contains("cannot draw")&&!expected.getMessage().contains(p.key),"text-only and refusal output stays failure with redaction");}
        controller(dir,p,response,png);
    }
    static void controller(File dir,Models.Provider p,JSONObject response,byte[] png)throws Exception{
        HandlerThread thread=new HandlerThread("responses-controller-test");thread.start();Handler handler=new Handler(thread.getLooper());ExecutorService network=Executors.newSingleThreadExecutor();AtomicReference<CreativeController> owner=new AtomicReference<>();
        try{
            try(CreativeCheck.Server server=new CreativeCheck.Server(new CreativeCheck.Reply(response.toString()))){
                p.base=server.base();p.imageProtocol="auto";p.responsesPath="/responses";p.imageToolModel="";CountDownLatch done=new CountDownLatch(1);
                File root=new File(dir,"responses-controller-"+Models.id());CreativeController c=CreativeCheck.on(handler,()->new CreativeController(root,handler,network,id->p,()->{if(owner.get()!=null&&!owner.get().busy())done.countDown();}));owner.set(c);
                CreativeCheck.on(handler,()->{c.draft.providerId=p.id;c.draft.model="gpt-6.1-sol";c.draft.prompt="女生";c.start(p);return null;});check(done.await(6,TimeUnit.SECONDS),"Responses image controller completes");server.await();
                Creative.Task result=CreativeCheck.on(handler,()->c.tasks.get(0));check(result.state.equals("completed")&&result.assets.size()==1&&result.apiId.equals("resp_image_test"),"Responses controller saves image and remote response ID");
                check(server.count()==1&&server.headers.get(0).startsWith("POST /v1/responses "),"automatic Responses route has no Images request and no repeated POST");
                check(Arrays.equals(png,Files.readAllBytes(c.store.asset(result.assets.get(0).file).toPath())),"controller image bytes match");
                // Cache is normalized data, so resuming requires no network even after provider protocol changes.
                Creative.Task pending=Creative.Task.from(result.json());pending.id=Models.id();pending.state="downloading";pending.assets.clear();c.store.save(pending);c.store.saveResult(pending.id,CreativeApi.imageResult(response,pending,p.key));
                CountDownLatch resumed=new CountDownLatch(1);CreativeController next=CreativeCheck.on(handler,()->new CreativeController(root,handler,network,id->p,()->{if(owner.get()!=null&&!owner.get().busy())resumed.countDown();}));owner.set(next);p.imageProtocol="images";
                Creative.Task saved=CreativeCheck.on(handler,()->{for(Creative.Task item:next.tasks)if(item.id.equals(pending.id))return item;throw new AssertionError("cache missing");});
                CreativeCheck.on(handler,()->{next.resume(saved);return null;});check(resumed.await(6,TimeUnit.SECONDS),"Responses cached image resumes");
                check(CreativeCheck.on(handler,()->next.tasks.stream().anyMatch(item->item.id.equals(pending.id)&&item.state.equals("completed")))&&server.count()==1,"cached Responses resume ignores changed protocol and never re-POSTs");
            }
        }finally{network.shutdownNow();network.awaitTermination(3,TimeUnit.SECONDS);thread.quitSafely();thread.join(1000);}
    }
}

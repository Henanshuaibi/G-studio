package cn.g.studio;
import static cn.g.studio.CoreCheck.check;
import static cn.g.studio.CoreCheck.fails;
import android.graphics.*;
import android.os.*;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

final class CreativeCheck {
    static byte[] utf(String s){return s.getBytes(StandardCharsets.UTF_8);}
    static final class Reply {
        int code=200;String type="application/json",location="";byte[] bytes;boolean length=true;
        Reply(String json){bytes=utf(json);}Reply(byte[] bytes,String type){this.bytes=bytes;this.type=type;}
        Reply redirect(String location){code=302;this.location=location;return this;}
    }
    static final class Server implements AutoCloseable {
        final ServerSocket socket=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"));final ArrayList<String> requests=new ArrayList<>(),headers=new ArrayList<>();
        final CountDownLatch done=new CountDownLatch(1);volatile Throwable error;final Thread thread;
        Server(Reply... replies)throws Exception{
            thread=new Thread(()->{try{for(Reply reply:replies)try(Socket c=socket.accept()){
                c.setSoTimeout(5000);InputStream in=c.getInputStream();ByteArrayOutputStream h=new ByteArrayOutputStream();int b,state=0;
                while((b=in.read())!=-1){h.write(b);state=state==0&&b==13?1:state==1&&b==10?2:state==2&&b==13?3:state==3&&b==10?4:0;if(state==4)break;}
                String header=new String(h.toByteArray(),StandardCharsets.UTF_8);int length=0;for(String line:header.split("\r\n"))if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());
                byte[] payload=new byte[length];int count=0,n;while(count<length&&(n=in.read(payload,count,length-count))>0)count+=n;
                synchronized(this){headers.add(header);requests.add(new String(payload,StandardCharsets.UTF_8));}
                OutputStream out=c.getOutputStream();out.write(("HTTP/1.1 "+reply.code+" Test\r\nContent-Type: "+reply.type+"\r\n"+(reply.length?"Content-Length: "+reply.bytes.length+"\r\n":"")+(reply.location.isEmpty()?"":"Location: "+reply.location+"\r\n")+"Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(reply.bytes);out.flush();
            }}catch(Throwable e){if(!socket.isClosed())error=e;}finally{done.countDown();}});thread.start();
        }
        String base(){return "http://127.0.0.1:"+socket.getLocalPort()+"/v1";}
        void await()throws Exception{check(done.await(8,TimeUnit.SECONDS),"creative server completed");if(error!=null)throw new Exception(error);}
        synchronized int count(){return headers.size();}
        public void close()throws Exception{socket.close();thread.join(1000);}
    }
    static Creative.Task task(Models.Provider p,String kind){Creative.Task t=new Creative.Task();t.kind=kind;t.providerId=p.id;t.model="mock-"+kind;t.prompt="城市雨景🌿";t.base=p.base;t.path=kind.equals("image")?p.imagePath:p.videoPath;return t;}
    static <T>T on(Handler handler,Callable<T> action)throws Exception{FutureTask<T> future=new FutureTask<>(action);handler.post(future);return future.get(6,TimeUnit.SECONDS);}
    static void run(File dir)throws Exception{
        Models.Provider p=CoreCheck.provider();p.base="http://127.0.0.1:9/v1";p.imageModel="mock-image";p.videoModel="mock-video";
        check(p.copy().imageModel.equals(p.imageModel)&&p.copy().imagePath.equals("/images/generations")&&p.copy().videoPath.equals("/videos"),"generation provider fields copied");
        Models.Provider old=Models.Provider.from(new JSONObject("{\"id\":\"old\",\"base\":\"https://example.com\"}"));
        check(old.imagePath.equals("/images/generations")&&old.videoPath.equals("/videos")&&!old.videoJson,"old providers get safe generation defaults");
        check(Endpoint.normalize("https://example.com/v1/images/generations",false).equals("https://example.com/v1"),"full image endpoint normalization");
        check(CreativeApi.route("https://example.com/v1/chat/completions","/videos",false).equals("https://example.com/v1/videos"),"video endpoint shares normalized API prefix");
        fails(()->CreativeApi.route(p.base,"//elsewhere.example/videos",true),"路径");fails(()->CreativeApi.route(p.base,"/../videos",true),"路径");
        Creative.Draft draft=new Creative.Draft();draft.model="mock-image";draft.prompt="test";CreativeApi.validate(draft,p);
        draft.kind="video";draft.seconds="0";fails(()->CreativeApi.validate(draft,p),"秒数");draft.seconds="8";draft.size="9:16";fails(()->CreativeApi.validate(draft,p),"尺寸");draft.size="1280x720";CreativeApi.validate(draft,p);
        Creative.Task t=task(p,"image");JSONObject payload=CreativeApi.payload(t);
        check(!payload.has("response_format")&&!payload.has("size")&&!payload.has("stream")&&!payload.toString().contains(p.key),"minimal image payload and no credential in body");
        t.size="1024x1024";t.quality="high";check(CreativeApi.payload(t).getString("quality").equals("high"),"optional image parameters");
        byte[] png;Bitmap bitmap=Bitmap.createBitmap(8,6,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.BLUE);try{ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,out);png=out.toByteArray();}finally{bitmap.recycle();}
        JSONObject imageResult=new JSONObject().put("data",new JSONArray().put(new JSONObject().put("b64_json",Base64.encodeToString(png,Base64.NO_WRAP))));
        CreativeStore store=new CreativeStore(new File(dir,"creative-api-"+UUID.randomUUID()));
        try(Server server=new Server(new Reply(imageResult.toString()))){
            p.base=server.base();t=task(p,"image");JSONObject result=CreativeApi.create(p,t,new ApiClient.Call());server.await();
            check(server.headers.get(0).startsWith("POST /v1/images/generations "),"image HTTP route");
            check(server.headers.get(0).contains("Bearer "+p.key)&&new JSONObject(server.requests.get(0)).getString("prompt").equals(t.prompt),"image auth and Unicode prompt");
            Creative.Asset asset=CreativeApi.saveImage(store,t,result.getJSONArray("data").getJSONObject(0),0,p,new ApiClient.Call());
            check(Arrays.equals(Files.readAllBytes(store.asset(asset.file).toPath()),png)&&asset.mime.equals("image/png"),"base64 image saved byte-for-byte");
            t.assets.add(asset);store.save(t);check(store.load(t.id).assets.get(0).file.equals(asset.file),"generated image metadata persists");
        }
        try(Server server=new Server(new Reply(png,"image/png"))){
            p.base=server.base();t=task(p,"image");Creative.Asset asset=CreativeApi.saveImage(store,t,new JSONObject().put("url",server.base()+"/image.png"),1,p,new ApiClient.Call());server.await();
            check(asset.index==1&&Arrays.equals(Files.readAllBytes(store.asset(asset.file).toPath()),png),"image URL response saved");
        }
        try(Server cdn=new Server(new Reply(png,"image/png"));Server source=new Server(new Reply("").redirect(cdn.base()+"/signed.png?token=temporary"))){
            p.base=source.base();File download=new File(store.media,"redirect.part");CreativeApi.download(source.base()+"/result",source.base(),p,new ApiClient.Call(),download,1024);source.await();cdn.await();
            check(source.headers.get(0).contains("Bearer "+p.key),"original artifact request authenticates");
            check(!cdn.headers.get(0).toLowerCase(Locale.ROOT).contains("authorization:")&&!cdn.headers.get(0).contains(p.key),"cross-origin redirect never receives API key");
            check(Arrays.equals(Files.readAllBytes(download.toPath()),png),"redirected artifact bytes preserved");download.delete();
        }
        check(CreativeApi.sameOrigin("https://a.example/path","https://a.example:443/other")&&!CreativeApi.sameOrigin("http://a.example:80/","http://a.example:81/"),"origin checks include effective port and scheme");
        fails(()->CreativeApi.mediaUrl("file:///secret",p.base,true),"地址");fails(()->CreativeApi.mediaUrl("http://a.example/result","https://a.example/v1",false),"HTTP");
        Reply oversize=new Reply(new byte[64],"image/png");oversize.length=false;
        try(Server server=new Server(oversize)){
            p.base=server.base();File partial=new File(store.media,"overflow.part");fails(()->CreativeApi.download(server.base()+"/image",server.base(),p,new ApiClient.Call(),partial,16),"大小");server.await();check(!partial.exists(),"oversized downloads remove partial file");
        }
        try(Server server=new Server(new Reply("{\"error\":{\"message\":\"invalid "+p.key+"\"}}"))){
            p.base=server.base();Creative.Task image=task(p,"image");try{CreativeApi.create(p,image,new ApiClient.Call());throw new AssertionError("API error rejected");}catch(IOException e){check(!e.getMessage().contains(p.key)&&e.getMessage().contains("隐藏"),"generation errors redact API key");}server.await();
        }
        JSONObject queued=new JSONObject("{\"id\":\"video_test\",\"status\":\"queued\",\"progress\":0}");
        try(Server server=new Server(new Reply(queued.toString()))){
            p.base=server.base();Creative.Task video=task(p,"video");video.size="1280x720";video.seconds="8";
            JSONObject response=CreativeApi.create(p,video,new ApiClient.Call());CreativeApi.applyVideo(video,response,p.key);server.await();
            check(video.apiId.equals("video_test")&&video.state.equals("queued"),"video queue state and ID");
            check(server.headers.get(0).startsWith("POST /v1/videos ")&&server.headers.get(0).contains("multipart/form-data; boundary="),"standard video multipart request");
            String multipart=server.requests.get(0);check(multipart.contains("name=\"prompt\"\r\n\r\n城市雨景🌿")&&multipart.contains("name=\"seconds\"\r\n\r\n8")&&!multipart.contains(p.key),"multipart fields and no credential body");
            CreativeApi.applyVideo(video,new JSONObject("{\"id\":\"video_test\",\"status\":\"in_progress\",\"progress\":37}"),p.key);check(video.state.equals("in_progress")&&video.progress==37,"video progress mapping");
            CreativeApi.applyVideo(video,new JSONObject("{\"id\":\"video_test\",\"status\":\"completed\"}"),p.key);check(video.state.equals("downloading")&&video.progress==100,"completed video enters download phase");
            video.apiId="video/id?x";check(CreativeApi.videoRoute(video,p,true).endsWith("/videos/video%2Fid%3Fx/content"),"video ID path escaping");
            fails(()->CreativeApi.applyVideo(video,new JSONObject("{\"id\":\"v\",\"status\":\"failed\",\"error\":{\"message\":\"failure\"}}"),p.key),"failure");check(video.state.equals("failed"),"explicit video failure preserved");
        }
        try(Server server=new Server(new Reply(queued.toString()))){
            p.base=server.base();Creative.Task video=task(p,"video");video.jsonVideo=true;CreativeApi.create(p,video,new ApiClient.Call());server.await();check(server.headers.get(0).contains("application/json")&&new JSONObject(server.requests.get(0)).getString("model").equals(video.model),"opt-in JSON video submission");
        }
        fails(()->store.asset("../../key"),"文件名");fails(()->store.load("../config"),"编号");
        controllerChecks(dir,p,png,imageResult);
    }
    static void controllerChecks(File dir,Models.Provider p,byte[] png,JSONObject imageResult)throws Exception{
        HandlerThread mainThread=new HandlerThread("g-studio-creative-check");mainThread.start();Handler handler=new Handler(mainThread.getLooper());ExecutorService network=Executors.newFixedThreadPool(2);AtomicReference<CreativeController> owner=new AtomicReference<>();
        try{
            try(Server server=new Server(new Reply(imageResult.toString()))){
                p.base=server.base();CountDownLatch done=new CountDownLatch(1);File folder=new File(dir,"creative-controller-image-"+UUID.randomUUID());
                CreativeController c=on(handler,()->new CreativeController(folder,handler,network,id->p,()->{CreativeController cc=owner.get();if(cc!=null&&!cc.busy())done.countDown();}));owner.set(c);
                on(handler,()->{c.draft.providerId=p.id;c.draft.kind="image";c.draft.model="mock-image";c.draft.prompt="test";c.start(p);fails(()->c.start(p),"当前创作");return null;});
                check(done.await(6,TimeUnit.SECONDS),"image controller completed");server.await();Creative.Task complete=on(handler,()->c.tasks.get(0));
                check(complete.state.equals("completed")&&complete.assets.size()==1&&server.count()==1,"image controller submits once and caches asset");
                check(!c.store.hasResult(complete.id)&&c.store.asset(complete.assets.get(0).file).isFile(),"completed image releases base64 result cache only");
                String saved=new String(Files.readAllBytes(new File(c.store.tasks,complete.id+".json").toPath()),StandardCharsets.UTF_8);check(!saved.contains(p.key)&&!saved.contains("base64"),"task metadata excludes credentials and binary content");
            }
            // Resume an interrupted image from its cached response without making an HTTP request.
            File folder=new File(dir,"creative-controller-cache-"+UUID.randomUUID());CreativeStore cache=new CreativeStore(folder);Creative.Task cached=task(p,"image");cached.state="downloading";cache.save(cached);cache.saveResult(cached.id,imageResult);
            CountDownLatch resumedImage=new CountDownLatch(1);CreativeController restored=on(handler,()->new CreativeController(folder,handler,network,id->p,()->{CreativeController cc=owner.get();if(cc!=null&&!cc.busy())resumedImage.countDown();}));owner.set(restored);
            check(restored.tasks.get(0).state.equals("paused"),"interrupted image recognized as resumable");
            on(handler,()->{restored.resume(restored.tasks.get(0));return null;});check(resumedImage.await(6,TimeUnit.SECONDS),"cached image resumed");
            check(on(handler,()->restored.tasks.get(0).state.equals("completed")),"resume saved image without new POST");
            byte[] mp4=new byte[]{0,0,0,24,'f','t','y','p','i','s','o','m',0,0,0,0,'i','s','o','m','m','p','4','2'};
            try(Server server=new Server(new Reply("{\"id\":\"video_resume\",\"status\":\"queued\"}"),new Reply("{\"id\":\"video_resume\",\"status\":\"completed\"}"),new Reply(mp4,"video/mp4"))){
                p.base=server.base();File videoRoot=new File(dir,"creative-controller-video-"+UUID.randomUUID());CountDownLatch paused=new CountDownLatch(1);
                CreativeController c=on(handler,()->new CreativeController(videoRoot,handler,network,id->p,()->{CreativeController cc=owner.get();if(cc==null)return;if(cc.busy()&&!cc.tasks.isEmpty()&&cc.tasks.get(0).state.equals("queued"))cc.pause();if(!cc.busy())paused.countDown();}));owner.set(c);
                on(handler,()->{c.draft.kind="video";c.draft.providerId=p.id;c.draft.model="mock-video";c.draft.prompt="video";c.start(p);return null;});
                check(paused.await(6,TimeUnit.SECONDS),"video polling paused");Creative.Task task=on(handler,()->c.tasks.get(0));
                check(task.state.equals("paused")&&task.apiId.equals("video_resume")&&server.count()==1,"pause retains server ID without resubmitting or polling");
                CountDownLatch finished=new CountDownLatch(1);CreativeController resume=on(handler,()->new CreativeController(videoRoot,handler,network,id->p,()->{CreativeController cc=owner.get();if(cc!=null&&!cc.busy())finished.countDown();}));owner.set(resume);
                String base=p.base;p.base="http://127.0.0.1:9/v1";on(handler,()->{fails(()->resume.resume(resume.tasks.get(0)),"地址已变更");return null;});p.base=base;
                on(handler,()->{resume.resume(resume.tasks.get(0));return null;});check(finished.await(6,TimeUnit.SECONDS),"video status and asset resumed");server.await();
                Creative.Task complete=on(handler,()->resume.tasks.get(0));check(complete.state.equals("completed")&&complete.assets.size()==1,"video result cached");
                check(server.headers.get(0).startsWith("POST ")&&server.headers.get(1).startsWith("GET /v1/videos/video_resume ")&&server.headers.get(2).startsWith("GET /v1/videos/video_resume/content "),"video resume only queries existing ID and downloads content");
                check(Arrays.equals(Files.readAllBytes(resume.store.asset(complete.assets.get(0).file).toPath()),mp4),"video download bytes intact");
                Creative.Asset asset=complete.assets.get(0);on(handler,()->{resume.delete(complete);return null;});check(!resume.store.asset(asset.file).exists()&&resume.store.list().isEmpty(),"task deletion reclaims local result");
            }
            CreativeStore unknownStore=new CreativeStore(new File(dir,"creative-controller-unknown-"+UUID.randomUUID()));Creative.Task pending=task(p,"video");unknownStore.save(pending);
            CreativeController unknown=on(handler,()->new CreativeController(unknownStore.root,handler,network,id->p,()->{}));
            check(unknown.tasks.get(0).state.equals("unknown")&&!unknown.canResume(unknown.tasks.get(0)),"interrupted submission without ID is never auto-retried");
        }finally{CreativeController c=owner.get();if(c!=null)on(handler,()->{c.pause();return null;});network.shutdownNow();network.awaitTermination(3,TimeUnit.SECONDS);mainThread.quitSafely();mainThread.join(1000);}
    }
}

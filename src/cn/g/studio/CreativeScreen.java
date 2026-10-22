package cn.g.studio;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;

final class CreativeScreen {
    final MainActivity a;final StudioApp app;final CreativeController controller;
    EditText model,prompt,size,seconds,quality;TextView status,start,pause;LinearLayout outputs;
    boolean ready,disposed;String signature="";int visible=12;ApiClient.Call lookup;
    final HashMap<String,TextView> statuses=new HashMap<>();
    CreativeScreen(MainActivity a){this.a=a;app=a.app;controller=app.creative;}
    Models.Provider provider(){for(Models.Provider p:app.config.providers)if(p.id.equals(controller.draft.providerId))return p;return null;}
    void render(){
        Creative.Draft d=controller.draft;if(d.providerId.isEmpty())d.providerId=app.config.selected;Models.Provider p=provider();
        if(d.model.isEmpty()&&p!=null)d.model=d.kind.equals("image")?p.imageModel:p.videoModel;
        a.header("创作",()->{capture();saveDraft();a.page="chat";a.render();});LinearLayout body=a.scrollBody();
        LinearLayout tabs=a.row();tabs.addView(a.button("文生图",()->switchKind("image")),new LinearLayout.LayoutParams(0,-2,1));tabs.addView(a.button("文生视频",()->switchKind("video")),new LinearLayout.LayoutParams(0,-2,1));a.add(body,tabs,0);
        a.add(body,a.label(d.kind.equals("image")?"让想法变成画面":"让画面动起来",a.small?21:28,MainActivity.INK),18);
        a.add(body,a.label("选择支持生成的服务与模型，填写画面描述。",13,MainActivity.MUTED),7);
        a.add(body,a.button(p==null?"选择 API 服务":p.name+" · 更换服务",this::chooseProvider),16);
        model=a.field(body,d.kind.equals("image")?"生图模型 ID":"视频模型 ID",d.model,"填写服务商提供的模型 ID",false);
        a.add(body,a.button("获取模型列表",this::models),8);
        if(d.kind.equals("image")){
            a.add(body,a.button("调用方式 · "+(p==null?"自动选择":CreativeApi.protocolLabel(p.imageProtocol)),this::chooseProtocol),8);
            a.add(body,a.label("自动模式：gpt-6.1-sol 使用 Responses 工具生图；其他模型使用 Images。中转站需支持对应接口。",12,MainActivity.MUTED),6);
        }
        prompt=a.field(body,"画面描述",d.prompt,d.kind.equals("image")?"例如：雨后的城市街道，霓虹倒影，电影感。":"例如：镜头缓慢穿过云海，远处群山在晨光中浮现。",true);prompt.setMinLines(a.small?3:4);
        size=a.field(body,"尺寸（可选）",d.size,d.kind.equals("image")?"如 1024x1024，留空用服务默认值":"如 1280x720，留空用服务默认值",false);
        if(d.kind.equals("video")){seconds=a.field(body,"秒数（可选）",d.seconds,"如 4、8、12，以模型支持为准",false);seconds.setInputType(InputType.TYPE_CLASS_NUMBER);}
        else quality=a.field(body,"质量（可选）",d.quality,"如 low / medium / high，留空用默认值",false);
        start=a.button(d.kind.equals("image")?"生成图片":"生成视频",this::start);start.setTextColor(MainActivity.BG);start.setBackground(a.background(MainActivity.ACCENT,15));a.add(body,start,20);
        pause=a.button("暂停等待",()->controller.pause());a.add(body,pause,8);
        status=a.label("",13,MainActivity.MUTED);a.add(body,status,9);
        a.add(body,a.label("生成结果",a.small?18:22,MainActivity.INK),24);outputs=a.column();a.add(body,outputs,10);
        TextWatcher watcher=new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){if(ready)capture();}public void afterTextChanged(Editable e){}};
        for(EditText edit:new EditText[]{model,prompt,size,seconds,quality})if(edit!=null)edit.addTextChangedListener(watcher);ready=true;refresh();
    }
    void capture(){if(!ready||disposed)return;Creative.Draft d=controller.draft;d.model=model.getText().toString().trim();d.prompt=prompt.getText().toString();d.size=size.getText().toString().trim();d.seconds=seconds==null?"":seconds.getText().toString().trim();d.quality=quality==null?"":quality.getText().toString().trim();}
    void saveDraft(){try{controller.saveDraft();}catch(Exception e){a.error(e);}}
    void switchKind(String kind){capture();if(controller.draft.kind.equals(kind))return;controller.draft.kind=kind;Models.Provider p=provider();controller.draft.model=p==null?"":kind.equals("image")?p.imageModel:p.videoModel;controller.draft.size="";controller.draft.quality="";controller.draft.seconds="";saveDraft();a.render();}
    void chooseProvider(){
        capture();String[] names=new String[app.config.providers.size()+1];for(int i=0;i<names.length-1;i++)names[i]=app.config.providers.get(i).name;names[names.length-1]="管理 API 服务";
        a.sheet().setTitle("选择创作服务").setItems(names,(dialog,which)->{
            if(which==names.length-1){saveDraft();a.page="providers";a.render();return;}
            Models.Provider p=app.config.providers.get(which);controller.draft.providerId=p.id;controller.draft.model=controller.draft.kind.equals("image")?p.imageModel:p.videoModel;saveDraft();a.render();
        }).show();
    }
    void chooseProtocol(){
        capture();Models.Provider p=provider();if(p==null){chooseProvider();return;}
        a.sheet().setTitle("生图调用方式").setItems(new String[]{"自动选择","Images 直接生图","Responses 工具生图"},(dialog,which)->{
            String old=p.imageProtocol;p.imageProtocol=new String[]{"auto","images","responses"}[which];
            try{app.store.saveConfig(app.config);saveDraft();a.render();}catch(Exception e){p.imageProtocol=old;a.error(e);}
        }).show();
    }
    void models(){
        if(lookup!=null)return;Models.Provider p=provider();if(p==null){chooseProvider();return;}
        try{p=p.copy();}catch(Exception e){a.error(e);return;}final Models.Provider selected=p;ApiClient.Call call=new ApiClient.Call();lookup=call;status.setText("正在获取模型列表…");
        app.network.execute(()->{
            try{ArrayList<String> ids=ApiClient.models(selected,call);a.main.post(()->{
                if(disposed||lookup!=call||a.isDestroyed())return;lookup=null;if(ids.isEmpty()){a.toast("没有模型列表，可手动填写模型 ID");refresh();return;}
                LinearLayout box=a.column();EditText search=new EditText(a);search.setHint("搜索模型");box.addView(search);ListView list=new ListView(a);ArrayAdapter<String> adapter=new ArrayAdapter<>(a,android.R.layout.simple_list_item_1,new ArrayList<>(ids));list.setAdapter(adapter);box.addView(list,new LinearLayout.LayoutParams(-1,0,1));
                SheetDialog sheet=a.sheet().setTitle("选择生成模型").setView(box,false).create();list.setOnItemClickListener((parent,v,pos,id)->{model.setText(adapter.getItem(pos));sheet.dismiss();});
                search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int after){}public void onTextChanged(CharSequence s,int st,int before,int c){adapter.clear();String q=s.toString().toLowerCase(Locale.ROOT);for(String id:ids)if(id.toLowerCase(Locale.ROOT).contains(q))adapter.add(id);}public void afterTextChanged(Editable e){}});sheet.show();refresh();
            });}catch(Exception e){a.main.post(()->{if(disposed||lookup!=call||a.isDestroyed())return;lookup=null;a.error(e);refresh();});}
        });
    }
    void start(){
        capture();Models.Provider p=provider();if(p==null){chooseProvider();return;}
        try{
            CreativeApi.validate(controller.draft,p);String old=controller.draft.kind.equals("image")?p.imageModel:p.videoModel;
            if(controller.draft.kind.equals("image"))p.imageModel=controller.draft.model;else p.videoModel=controller.draft.model;
            try{app.store.saveConfig(app.config);}catch(Exception e){if(controller.draft.kind.equals("image"))p.imageModel=old;else p.videoModel=old;throw e;}
            a.hideKeyboard();controller.start(p);
        }catch(Exception e){a.error(e);}
    }
    void refresh(){
        if(disposed||status==null)return;boolean busy=controller.busy();start.setEnabled(!busy&&controller.loadError.isEmpty());start.setAlpha(start.isEnabled()?1:.5f);pause.setVisibility(busy?View.VISIBLE:View.GONE);pause.setEnabled(busy&&!controller.run.stopping);
        if(!controller.loadError.isEmpty())status.setText(controller.loadError);
        else if(busy){Creative.Task t=null;for(Creative.Task item:controller.tasks)if(controller.active(item))t=item;status.setText(controller.run.stopping?"正在暂停本机等待…":(t==null?"正在处理…":t.status())+"\n暂停只停止本机等待，远端任务仍会继续。");}
        else status.setText("结果保存在本机；暂停的任务可继续查询或保存。");
        StringBuilder next=new StringBuilder(busy?controller.run.task.id:"");for(int i=0;i<Math.min(visible,controller.tasks.size());i++){Creative.Task t=controller.tasks.get(i);next.append('|').append(t.id).append(t.state);for(Creative.Asset asset:t.assets)next.append(asset.file);}
        if(!next.toString().equals(signature)||outputs.getChildCount()==0){signature=next.toString();renderTasks();}
        for(Creative.Task t:controller.tasks){TextView v=statuses.get(t.id);if(v!=null)v.setText(t.status());}
    }
    void renderTasks(){
        outputs.removeAllViews();statuses.clear();if(controller.tasks.isEmpty()){outputs.addView(a.label("完成的图片和视频会出现在这里。",14,MainActivity.MUTED));return;}
        for(int i=0;i<Math.min(visible,controller.tasks.size());i++){
            Creative.Task t=controller.tasks.get(i);LinearLayout card=a.column();card.setPadding(a.dp(13),a.dp(12),a.dp(13),a.dp(12));card.setBackground(a.background(MainActivity.CARD,18));
            card.addView(a.label((t.kind.equals("image")?"图片":"视频")+" · "+t.model,14,MainActivity.ACCENT));TextView description=a.label(t.prompt,15,MainActivity.INK);description.setMaxLines(3);description.setEllipsize(TextUtils.TruncateAt.END);a.add(card,description,8);
            a.add(card,a.label(new SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(new Date(t.created)),11,MainActivity.MUTED),5);
            TextView state=a.label(t.status(),12,MainActivity.MUTED);statuses.put(t.id,state);a.add(card,state,8);
            for(Creative.Asset asset:t.assets){
                if(asset.mime.startsWith("image/")){ImageView image=new ImageView(a);image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);card.addView(image,new LinearLayout.LayoutParams(-1,a.dp(a.small?120:180)));image.setOnClickListener(v->preview(asset));loadImage(asset,image,512);}
                a.add(card,a.button((asset.mime.startsWith("video/")?"播放视频":"查看图片")+" · "+String.format(Locale.ROOT,"%.1f MB",asset.bytes/1048576.0),()->preview(asset)),7);
                a.add(card,a.button("保存到设备",()->a.saveCreativeAsset(asset)),5);
            }
            if(!controller.active(t)){
                if(!t.state.equals("completed")&&!t.state.equals("failed")&&controller.canResume(t))a.add(card,a.button("继续查询 / 保存",()->{capture();try{controller.resume(t);}catch(Exception e){a.error(e);}}),9);
                a.add(card,a.button("任务详情",()->a.sheet().setTitle("创作任务").setMessage("模型："+t.model+"\n任务 ID："+(t.apiId.isEmpty()?"尚未取得":t.apiId)+"\n\n"+t.prompt+"\n\n"+t.status()).setPositiveButton("关闭",null).show()),5);
                a.add(card,a.button("删除本地记录",()->a.sheet().setTitle("删除本地创作记录？").setMessage("记录与已缓存的结果将删除。远端任务不会因此取消。").setNegativeButton("取消",null).setPositiveButton("删除",(dialog,which)->{try{controller.delete(t);}catch(Exception e){a.error(e);}}).show()),5);
            }a.add(outputs,card,10);
        }
        if(controller.tasks.size()>visible)a.add(outputs,a.button("查看更早的创作",()->{visible+=12;signature="";refresh();}),12);
    }
    void loadImage(Creative.Asset asset,ImageView view,int limit){
        app.files.execute(()->{Bitmap bitmap=null;try{String path=controller.store.asset(asset.file).getPath();BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(path,o);o.inSampleSize=1;while(Math.max(o.outWidth,o.outHeight)/o.inSampleSize>limit)o.inSampleSize*=2;o.inJustDecodeBounds=false;bitmap=BitmapFactory.decodeFile(path,o);}catch(Exception ignored){}
            final Bitmap loaded=bitmap;if(loaded!=null)a.main.post(()->{if(!a.isDestroyed()&&view.isAttachedToWindow())view.setImageBitmap(loaded);else loaded.recycle();});
        });
    }
    void preview(Creative.Asset asset){
        try{
            if(!controller.store.asset(asset.file).isFile())throw new java.io.IOException("本地结果文件已丢失");
            if(asset.mime.startsWith("image/")){ImageView image=new ImageView(a);image.setScaleType(ImageView.ScaleType.FIT_CENTER);LinearLayout box=a.column();box.addView(image,new LinearLayout.LayoutParams(-1,Math.max(a.dp(100),a.getResources().getDisplayMetrics().heightPixels/2)));a.sheet().setTitle("生成图片").setView(box).setPositiveButton("保存",(d,w)->a.saveCreativeAsset(asset)).show();loadImage(asset,image,1600);}
            else{
                VideoView video=new VideoView(a);LinearLayout box=a.column();box.addView(video,new LinearLayout.LayoutParams(-1,Math.max(a.dp(100),a.getResources().getDisplayMetrics().heightPixels/2)));MediaController controls=new MediaController(a);controls.setAnchorView(video);video.setMediaController(controls);
                video.setOnErrorListener((player,what,extra)->{a.toast("播放失败，可先保存到设备后使用播放器打开");return true;});video.setOnPreparedListener(player->video.start());
                SheetDialog dialog=a.sheet().setTitle("生成视频").setView(box).setPositiveButton("保存",(d,w)->a.saveCreativeAsset(asset)).create();dialog.setOnDismissListener(d->{a.sheets.remove(dialog);video.stopPlayback();});dialog.show();video.setVideoPath(controller.store.asset(asset.file).getPath());
            }
        }catch(Exception e){a.error(e);}
    }
    void dispose(){disposed=true;ready=false;if(lookup!=null){ApiClient.Call old=lookup;lookup=null;old.cancelled=true;new Thread(old::cancel).start();}}
}

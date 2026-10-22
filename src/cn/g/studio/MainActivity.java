package cn.g.studio;
import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.*;
import android.text.method.PasswordTransformationMethod;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;

public final class MainActivity extends Activity implements StudioApp.Listener {
    static final int BG=0xff0c1218,CARD=0xff151f29,INK=0xffe9f0f5,MUTED=0xff93a6b5,ACCENT=0xff8be4c3,USER=0xff1c3835,RED=0xffffb0a9;
    StudioApp app;CreativeScreen creativeScreen;SideDrawer drawer;LinearLayout menuBody;LinearLayout root,body;ScrollView conversation;
    EditText input,nameField,baseField,keyField,modelField,systemField,tempField,turnsField,imageModelField,videoModelField,imagePathField,videoPathField,responsesPathField,imageToolModelField;
    CheckBox streamField,httpField,videoJsonField;TextView liveAnswer,liveReason,liveMeta,lookupStatus;
    String page="chat",editId="",imageProtocolSelection="auto",pickerChat="",creativeExport="";boolean small,follow=true;int recentLimit=20,visibleMessages=60,preserveScrollBottom=-1;String lastLiveText="";
    ApiClient.Call lookup;Models.Provider draft;
    final HashSet<SheetDialog> sheets=new HashSet<>();
    final Handler main=new Handler(Looper.getMainLooper());
    @Override public void onCreate(Bundle state){
        super.onCreate(state);app=(StudioApp)getApplication();small=getResources().getConfiguration().screenWidthDp<320;
        if(state!=null){page=state.getString("page","chat");editId=state.getString("editId","");pickerChat=state.getString("pickerChat","");creativeExport=state.getString("creativeExport","");}
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        root=column();root.setBackgroundColor(BG);drawer=new SideDrawer(this,root);
        ScrollView menuScroll=new ScrollView(this);menuScroll.setFillViewport(true);menuScroll.setVerticalScrollBarEnabled(false);menuBody=column();menuScroll.addView(menuBody);drawer.setMenu(menuScroll);
        drawer.setOnOpening(()->{hideKeyboard();captureDraft();app.saveQuiet();renderMenu();});
        drawer.setOnApplyWindowInsetsListener((v,i)->{
            int edge=getResources().getConfiguration().isScreenRound()?dp(16):0;
            root.setPadding(Math.max(edge,i.getSystemWindowInsetLeft()),i.getSystemWindowInsetTop(),Math.max(edge,i.getSystemWindowInsetRight()),Math.max(i.getSystemWindowInsetBottom(),i.getStableInsetBottom()));
            drawer.setInsets(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getStableInsetBottom(),edge);
            return i.consumeSystemWindowInsets();
        });
        setContentView(drawer);drawer.requestApplyInsets();
        if(!app.fatal.isEmpty()){drawer.setGesturesEnabled(false);root.addView(label(app.fatal,16,RED));return;}
        if(page.equals("history"))page="chat";draft=app.editing;if(page.equals("edit")&&draft==null)page="providers";render();
        if(state!=null&&state.getBoolean("drawerOpen",false)&&page.equals("chat"))drawer.restoreOpen();
        if(state!=null&&state.getBoolean("attachmentSheet",false)&&page.equals("chat"))chooseAttachment();
    }
    @Override protected void onResume(){super.onResume();app.listener=this;if(app.fatal.isEmpty()){if(page.equals("create"))creativeChanged();else changed(true);}}
    @Override protected void onPause(){captureDraft();app.saveQuiet();if(app.listener==this)app.listener=null;super.onPause();}
    @Override protected void onSaveInstanceState(Bundle b){captureDraft();super.onSaveInstanceState(b);b.putString("page",page);b.putString("editId",editId);b.putString("pickerChat",pickerChat);b.putString("creativeExport",creativeExport);b.putBoolean("drawerOpen",drawer!=null&&drawer.willOpen());
        for(SheetDialog sheet:sheets)if(sheet.isShowing()&&!sheet.isClosing()&&sheet.kind.equals("attachments"))b.putBoolean("attachmentSheet",true);}
    @Override protected void onDestroy(){
        if(app.listener==this)app.listener=null;
        cancelLookup();if(creativeScreen!=null)creativeScreen.dispose();if(drawer!=null)drawer.dispose();
        for(SheetDialog sheet:new ArrayList<>(sheets))sheet.dismissNow();sheets.clear();
        super.onDestroy();
    }
    @Override public void onBackPressed(){
        if(drawer!=null&&drawer.isShowing()){drawer.close();return;}
        if(!page.equals("chat")){hideKeyboard();page=page.equals("edit")?"providers":"chat";app.editing=null;render();}
        else super.onBackPressed();
    }
    void captureDraft(){
        if(page.equals("chat")&&input!=null)app.saveDraft(input.getText().toString());
        if(page.equals("edit")&&nameField!=null){draft=readForm(false);app.editing=draft;}
        if(page.equals("create")&&creativeScreen!=null)creativeScreen.capture();
    }
    @Override public void changed(boolean structure){
        if(isDestroyed()||!page.equals("chat"))return;
        if(structure)render();else updateLive();
        if(!app.notice.isEmpty()){toast(app.notice);app.notice="";}
    }
    @Override public void creativeChanged(){if(!isDestroyed()&&page.equals("create")&&creativeScreen!=null)creativeScreen.refresh();}
    int dp(float n){return(int)(getResources().getDisplayMetrics().density*n+.5f);}
    LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    TextView label(String text,float size,int color){TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(color);return v;}
    GradientDrawable background(int color,int radius){GradientDrawable b=new GradientDrawable();b.setColor(color);b.setCornerRadius(dp(radius));return b;}
    TextView button(String text,Runnable action){
        TextView v=label(text,small?13:14,ACCENT);v.setGravity(Gravity.CENTER);v.setMinHeight(dp(small?40:46));
        v.setPadding(dp(12),dp(7),dp(12),dp(7));v.setBackground(background(CARD,14));v.setContentDescription(text);v.setOnClickListener(w->action.run());return v;
    }
    void space(LinearLayout p,int height){p.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height)));}
    void add(LinearLayout p,View v,int top){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(top);p.addView(v,lp);}
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    void error(Exception e){toast(e.getMessage()==null?"操作失败":e.getMessage());}
    boolean idle(){if(app.importing){toast("正在处理附件，请稍等");return false;}if(app.busy()){toast("请先点击停止，再切换对话或配置");return false;}return true;}
    void hideKeyboard(){View v=getCurrentFocus();if(v!=null)((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(v.getWindowToken(),0);}
    void cancelLookup(){
        ApiClient.Call old=lookup;lookup=null;
        if(old!=null){old.cancelled=true;new Thread(old::cancel,"g-studio-model-cancel").start();}
    }
    void render(){
        if(creativeScreen!=null){creativeScreen.dispose();creativeScreen=null;}
        if(drawer!=null)drawer.setGesturesEnabled(page.equals("chat"));
        if(!page.equals("edit"))cancelLookup();
        lastLiveText="";root.removeAllViews();liveAnswer=null;liveReason=null;liveMeta=null;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(page.equals("chat"))renderChat();else if(page.equals("providers"))renderProviders();else if(page.equals("edit"))renderEdit();else if(page.equals("memory"))MemoryScreen.render(this);else if(page.equals("create")){creativeScreen=new CreativeScreen(this);creativeScreen.render();}else renderAbout();
    }
    void header(String title,Runnable back){
        LinearLayout h=row();h.setPadding(dp(8),dp(5),dp(8),dp(5));TextView arrow=button("‹",back);arrow.setTextSize(27);
        h.addView(arrow,new LinearLayout.LayoutParams(dp(42),dp(42)));
        TextView t=label(title,small?17:21,INK);t.setTypeface(null,Typeface.BOLD);t.setPadding(dp(10),0,0,0);
        h.addView(t,new LinearLayout.LayoutParams(0,-2,1));root.addView(h);
    }
    LinearLayout scrollBody(){
        ScrollView s=new ScrollView(this);s.setFillViewport(true);LinearLayout b=column();b.setPadding(dp(small?10:20),dp(8),dp(small?10:20),dp(24));
        s.addView(b);root.addView(s,new LinearLayout.LayoutParams(-1,0,1));return b;
    }
    void renderChat(){
        LinearLayout h=row();h.setPadding(dp(small?6:14),dp(5),dp(small?6:14),dp(5));
        TextView menu=button("☰",this::showMenu);menu.setContentDescription("菜单");h.addView(menu,new LinearLayout.LayoutParams(dp(42),dp(42)));
        LinearLayout titles=column();titles.setPadding(dp(10),0,dp(4),0);TextView brand=label("G studio",small?18:22,INK);brand.setTypeface(null,Typeface.BOLD);titles.addView(brand);
        Models.Provider p=app.provider();TextView sub=label(p==null?"点击连接 AI 服务":p.name+" · "+(p.model.isEmpty()?"选择模型":p.model),small?10:12,MUTED);
        sub.setSingleLine(true);sub.setEllipsize(TextUtils.TruncateAt.END);titles.addView(sub);
        titles.setOnClickListener(v->{if(idle())chooseProvider();});titles.setContentDescription("选择服务和模型");
        h.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        TextView fresh=button("+",()->{if(idle())try{app.newChat();}catch(Exception e){error(e);}});fresh.setContentDescription("新对话");fresh.setTextSize(24);
        h.addView(fresh,new LinearLayout.LayoutParams(dp(42),dp(42)));root.addView(h);
        conversation=new ScrollView(this);conversation.setFillViewport(true);body=column();body.setPadding(dp(small?8:18),dp(8),dp(small?8:18),dp(12));
        conversation.addView(body);root.addView(conversation,new LinearLayout.LayoutParams(-1,0,1));
        conversation.getViewTreeObserver().addOnScrollChangedListener(()->{if(body!=null)follow=body.getHeight()-conversation.getScrollY()-conversation.getHeight()<dp(90);});
        ArrayList<Models.Message> messages=app.chat.messages;
        if(messages.isEmpty())emptyChat(p);
        else{int start=Math.max(0,messages.size()-visibleMessages);if(start>0)add(body,button("查看更早的消息",()->{preserveScrollBottom=body.getHeight()-conversation.getScrollY();visibleMessages+=60;render();}),0);for(int i=start;i<messages.size();i++)messageView(messages.get(i),i==messages.size()-1);}
        renderDraftAttachments();
        LinearLayout composer=row();composer.setPadding(dp(small?6:12),dp(6),dp(small?6:12),dp(8));
        TextView attach=button("+",this::chooseAttachment);attach.setContentDescription("添加图片或文件");composer.addView(attach,new LinearLayout.LayoutParams(dp(36),dp(44)));
        input=new EditText(this);input.setTextColor(INK);input.setHintTextColor(MUTED);input.setTextSize(small?14:16);input.setHint("发消息…");
        input.setInputType(1|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);input.setMaxLines(small?3:5);input.setMinLines(1);
        input.setPadding(dp(12),dp(10),dp(12),dp(10));input.setBackground(background(CARD,16));input.setText(app.chat.draft);input.setSelection(input.length());
        LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(0,-2,1);ip.setMargins(dp(6),0,dp(6),0);composer.addView(input,ip);
        input.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            public void onTextChanged(CharSequence s,int st,int before,int c){app.saveDraft(s.toString());}
            public void afterTextChanged(Editable e){}
        });
        TextView send=button(app.busy()?"■":"↑",()->{
            if(app.busy()){app.stop();return;}
            if(app.importing){toast("正在处理附件，请稍等");return;}
            if(app.provider()==null){page="providers";hideKeyboard();render();return;}
            String text=input.getText().toString();if(text.trim().isEmpty()&&app.chat.draftAttachments.isEmpty())return;hideKeyboard();try{app.send(text,false);}catch(Exception e){error(e);}
        });send.setTextSize(22);send.setTextColor(BG);send.setBackground(background(ACCENT,15));send.setContentDescription(app.busy()?"停止生成":"发送");
        composer.addView(send,new LinearLayout.LayoutParams(dp(44),dp(44)));root.addView(composer);
        if(app.busy())getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final ScrollView target=conversation;final int preserved=preserveScrollBottom;preserveScrollBottom=-1;follow=preserved<0;
        target.post(()->{if(!page.equals("chat")||conversation!=target)return;if(preserved>=0)target.scrollTo(0,Math.max(0,body.getHeight()-preserved));else target.fullScroll(View.FOCUS_DOWN);});
    }
    void emptyChat(Models.Provider p){
        space(body,small?12:44);TextView g=label("G",small?40:64,ACCENT);g.setTypeface(null,Typeface.BOLD);body.addView(g);
        TextView title=label(p==null?"连接你的 AI":"随时，接着聊。",small?21:28,INK);title.setTypeface(null,Typeface.BOLD);add(body,title,8);
        add(body,label(p==null?"添加 API 服务，开启第一段对话。":"想法、问题，或一句突如其来的灵感。",small?13:15,MUTED),10);
        if(p==null)add(body,button("添加服务",()->{page="providers";render();}),20);
        else{add(body,button("帮我理清一个想法",()->{input.setText("帮我理清一个想法：");input.requestFocus();}),20);add(body,button("把复杂问题讲简单",()->{input.setText("请用简单的语言向我解释：");input.requestFocus();}),8);}
    }
    void messageView(Models.Message m,boolean last){
        boolean user=m.role.equals("user");LinearLayout card=column();card.setPadding(dp(13),dp(11),dp(13),dp(12));card.setBackground(background(user?USER:CARD,18));
        TextView who=label(user?"你":m.model.isEmpty()?"AI":m.model,small?10:12,user?ACCENT:MUTED);who.setSingleLine(true);who.setEllipsize(TextUtils.TruncateAt.MIDDLE);card.addView(who);
        TextView reason=label("思考过程 · 点击展开",small?11:12,MUTED);reason.setVisibility(m.reasoning.isEmpty()?View.GONE:View.VISIBLE);reason.setPadding(0,dp(8),0,dp(3));
        reason.setOnClickListener(v->sheet().setTitle("思考过程").setMessage(m.reasoning).setPositiveButton("关闭",null).show());card.addView(reason);
        TextView text=label("",small?15:16,INK);text.setLineSpacing(dp(3),1.06f);text.setPadding(0,dp(6),0,0);text.setText(m.text.isEmpty()&&m.status.equals("streaming")?"正在连接…":Markdown.render(m.text));text.setTextIsSelectable(true);card.addView(text);
        for(Models.Attachment a:m.attachments){
            LinearLayout item=row();item.setPadding(0,dp(8),0,0);
            if(a.kind.equals("image")){ImageView thumb=new ImageView(this);thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);item.addView(thumb,new LinearLayout.LayoutParams(dp(64),dp(64)));loadImage(a,thumb,160);}
            TextView info=label(a.label()+"\n点击查看",small?11:12,ACCENT);info.setPadding(dp(8),dp(8),0,dp(8));info.setMaxLines(4);info.setEllipsize(TextUtils.TruncateAt.END);item.addView(info,new LinearLayout.LayoutParams(0,-2,1));
            item.setOnClickListener(v->previewAttachment(a));card.addView(item);
        }
        TextView meta=label(meta(m),small?10:12,m.status.equals("error")?RED:MUTED);meta.setPadding(0,dp(8),0,0);meta.setVisibility(meta(m).isEmpty()?View.GONE:View.VISIBLE);card.addView(meta);
        TextView actions=label("复制"+(!user&&last?" · 重新生成":""),small?10:12,ACCENT);actions.setPadding(0,dp(10),0,dp(2));actions.setMinHeight(dp(36));actions.setOnClickListener(v->messageActions(m,last));card.addView(actions);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(10);if(user)lp.leftMargin=dp(small?10:32);else lp.rightMargin=dp(small?4:12);body.addView(card,lp);
        if(last&&m.status.equals("streaming")){liveAnswer=text;liveReason=reason;liveMeta=meta;}
    }
    String meta(Models.Message m){String s=m.status.equals("streaming")?"正在回复…":m.error;if(!m.usage.isEmpty())s+=(s.isEmpty()?"":"\n")+m.usage;return s;}
    void updateLive(){
        if(app.run==null||liveAnswer==null)return;Models.Message m=app.run.message;boolean scroll=follow;
        String displayed=m.text.isEmpty()?(m.reasoning.isEmpty()?"正在连接…":"正在思考…"):m.text;
        if(!displayed.equals(lastLiveText)){liveAnswer.setText(Markdown.render(displayed));lastLiveText=displayed;}liveReason.setVisibility(m.reasoning.isEmpty()?View.GONE:View.VISIBLE);liveMeta.setText("正在回复…");
        if(scroll)conversation.post(()->{if(page.equals("chat"))conversation.fullScroll(View.FOCUS_DOWN);});
    }
    void renderDraftAttachments(){
        if(app.importing){TextView progress=label("正在处理附件…",12,ACCENT);progress.setPadding(dp(12),dp(5),dp(12),dp(5));root.addView(progress);}
        if(app.chat.draftAttachments.isEmpty())return;
        HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setHorizontalScrollBarEnabled(false);LinearLayout strip=row();strip.setPadding(dp(8),dp(3),dp(8),dp(3));
        for(Models.Attachment a:app.chat.draftAttachments){
            LinearLayout chip=row();chip.setBackground(background(CARD,12));TextView title=label(a.label(),small?11:12,ACCENT);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);title.setPadding(dp(10),dp(7),0,dp(7));title.setOnClickListener(v->previewAttachment(a));
            chip.addView(title,new LinearLayout.LayoutParams(dp(small?125:180),-2));TextView remove=button("×",()->{try{app.removeAttachment(a);}catch(Exception e){error(e);}});remove.setContentDescription("移除附件 "+a.name);chip.addView(remove,new LinearLayout.LayoutParams(dp(40),dp(40)));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.rightMargin=dp(7);strip.addView(chip,lp);
        }
        scroll.addView(strip);root.addView(scroll);
    }
    SheetDialog.Builder sheet(){
        hideKeyboard();return new SheetDialog.Builder(this).onCreated(dialog->{sheets.add(dialog);dialog.setOnDismissListener(d->sheets.remove(dialog));});
    }
    LinearLayout attachmentCard(String title,String subtitle,int icon,Runnable action){
        LinearLayout card=column();card.setPadding(dp(16),dp(18),dp(16),dp(16));card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x228be4c3),background(CARD,20),null));
        ImageView image=new ImageView(this);image.setImageResource(icon);card.addView(image,new LinearLayout.LayoutParams(dp(30),dp(30)));
        TextView label=label(title,small?15:17,INK);label.setTypeface(null,Typeface.BOLD);add(card,label,10);label.setGravity(Gravity.CENTER);
        TextView hint=label(subtitle,small?10:12,MUTED);hint.setGravity(Gravity.CENTER);add(card,hint,4);
        card.setContentDescription(title+"，"+subtitle);card.setOnClickListener(v->action.run());return card;
    }
    void chooseAttachment(){
        if(!idle())return;
        if(app.chat.draftAttachments.size()>=AttachmentStore.MAX_COUNT){toast("每条消息最多添加 6 个附件");return;}
        LinearLayout content=column();add(content,label("选择要发送的内容",small?12:14,MUTED),2);LinearLayout choices=row();
        final SheetDialog[] holder=new SheetDialog[1];
        LinearLayout.LayoutParams photo=new LinearLayout.LayoutParams(0,-2,1);photo.rightMargin=dp(6);
        choices.addView(attachmentCard("图片","照片与截图",R.drawable.attachment_image,()->holder[0].dismissThen(()->openAttachmentPicker(true))),photo);
        LinearLayout.LayoutParams file=new LinearLayout.LayoutParams(0,-2,1);file.leftMargin=dp(6);
        choices.addView(attachmentCard("文件","文档与代码",R.drawable.attachment_file,()->holder[0].dismissThen(()->openAttachmentPicker(false))),file);
        add(content,choices,16);
        add(content,button("AI 生图",()->holder[0].dismissThen(()->openCreative("image"))),12);
        add(content,button("AI 视频",()->holder[0].dismissThen(()->openCreative("video"))),6);
        space(content,8);holder[0]=sheet().setTitle("添加附件").setKind("attachments").setView(content).create();holder[0].show();
    }
    void openCreative(String kind){
        captureDraft();Creative.Draft d=app.creative.draft;if(!d.kind.equals(kind)){d.kind=kind;d.model="";d.size="";d.seconds="";d.quality="";}page="create";render();
    }
    void openAttachmentPicker(boolean image){
        if(!idle())return;captureDraft();app.saveQuiet();pickerChat=app.chat.id;
        Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT);picker.addCategory(Intent.CATEGORY_OPENABLE);picker.setType(image?"image/*":"*/*");picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{startActivityForResult(picker,703);}catch(ActivityNotFoundException e){toast("设备没有文件选择器，请安装可处理文件选择的应用");}
    }
    void loadImage(Models.Attachment a,ImageView view,int limit){
        app.files.execute(()->{
            Bitmap loaded=null;
            try{
                String path=app.store.attachments.file(a.id).getPath();BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(path,o);
                int sample=1;while(Math.max(o.outWidth,o.outHeight)/sample>limit)sample*=2;o.inJustDecodeBounds=false;o.inSampleSize=sample;loaded=BitmapFactory.decodeFile(path,o);
            }catch(Exception ignored){}
            final Bitmap bitmap=loaded;if(bitmap!=null)main.post(()->{if(!isDestroyed()&&view.isAttachedToWindow())view.setImageBitmap(bitmap);else bitmap.recycle();});
        });
    }
    void previewAttachment(Models.Attachment a){
        if(a.kind.equals("image")){
            ImageView image=new ImageView(this);image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout box=column();box.addView(image,new LinearLayout.LayoutParams(-1,Math.max(dp(100),getResources().getDisplayMetrics().heightPixels/2)));
            sheet().setTitle(a.name).setView(box).setPositiveButton("关闭",null).show();loadImage(a,image,1400);
        }else if(a.kind.equals("text")){
            ScrollView scroll=new ScrollView(this);TextView text=label(a.text.length()>30000?a.text.substring(0,30000)+"\n\n（预览显示前 3 万字符；发送时包含全文）":a.text,14,INK);text.setTextIsSelectable(true);text.setPadding(dp(16),dp(8),dp(16),dp(12));scroll.addView(text);
            sheet().setTitle(a.name).setView(scroll).setPositiveButton("关闭",null).show();
        }else sheet().setTitle(a.name).setMessage(a.label()+"\n\nPDF 将随消息发送；所选中转站和模型需要支持 PDF 附件。").setPositiveButton("关闭",null).show();
    }
    void messageActions(Models.Message m,boolean last){
        ArrayList<String> options=new ArrayList<>();options.add("复制");if(!m.text.isEmpty())options.add("记入记忆");if(m.role.equals("assistant")&&last)options.add("重新生成");
        sheet().setTitle("消息操作").setItems(options.toArray(new String[0]),(d,which)->{
            String action=options.get(which);if(action.equals("复制"))copy(m.text);else if(action.equals("记入记忆"))MemoryScreen.edit(this,"",m.text);else if(idle())try{app.send("",true);}catch(Exception e){error(e);}
        }).show();
    }
    void copy(String text){((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("G studio",text));toast("已复制");}
    void showMenu(){drawer.open();}
    void menuAction(boolean needsIdle,Runnable action){
        if(needsIdle&&!idle())return;
        drawer.closeThen(()->{captureDraft();action.run();});
    }
    TextView menuItem(String text,Runnable action,boolean selected){
        TextView v=button(text,action);v.setGravity(Gravity.CENTER_VERTICAL|Gravity.LEFT);v.setTextColor(selected?ACCENT:INK);v.setTextSize(small?14:15);
        v.setBackground(background(selected?USER:0x00000000,13));v.setMinHeight(dp(small?42:48));return v;
    }
    void renderMenu(){
        menuBody.removeAllViews();
        LinearLayout heading=row();TextView brand=label("G studio",small?22:27,ACCENT);brand.setTypeface(null,Typeface.BOLD);heading.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        TextView close=button("‹",()->drawer.close());close.setTextSize(26);close.setContentDescription("收起菜单");heading.addView(close,new LinearLayout.LayoutParams(dp(40),dp(40)));menuBody.addView(heading);
        add(menuBody,label("随身 AI · 你的对话空间",small?11:12,MUTED),4);
        TextView fresh=menuItem("＋  新对话",()->menuAction(true,()->{try{app.newChat();}catch(Exception e){error(e);}}),true);add(menuBody,fresh,22);
        add(menuBody,menuItem("生图与视频",()->menuAction(true,()->{page="create";render();}),false),2);
        add(menuBody,menuItem("API 服务",()->menuAction(true,()->{page="providers";render();}),false),2);
        add(menuBody,menuItem("记忆",()->menuAction(true,()->{page="memory";render();}),false),2);
        View line=new View(this);line.setBackgroundColor(0xff263540);LinearLayout.LayoutParams divider=new LinearLayout.LayoutParams(-1,dp(1));divider.setMargins(0,dp(14),0,dp(16));menuBody.addView(line,divider);
        menuBody.addView(label("最近会话",12,MUTED));
        LinearLayout recent=column();add(menuBody,recent,8);recent.addView(label("正在读取…",12,MUTED));
        app.files.execute(()->{
            try{
                ArrayList<Models.Chat> chats=app.store.listChats();
                main.post(()->{
                    if(isDestroyed()||recent.getParent()!=menuBody)return;recent.removeAllViews();
                    if(chats.isEmpty())recent.addView(label("从一段新对话开始",small?12:13,MUTED));
                    for(int n=0;n<Math.min(recentLimit,chats.size());n++){
                        Models.Chat chat=chats.get(n);TextView item=menuItem(chat.title,()->menuAction(true,()->{try{visibleMessages=60;app.selectChat(chat.id);}catch(Exception e){error(e);}}),app.chat.id.equals(chat.id));
                        item.setOnLongClickListener(v->{menuAction(true,()->chatActions(chat));return true;});item.setMaxLines(2);item.setEllipsize(TextUtils.TruncateAt.END);add(recent,item,2);
                    }
                    if(chats.size()>recentLimit)add(recent,button("加载更多会话",()->{recentLimit+=20;renderMenu();}),8);
                });
            }catch(Exception e){main.post(()->{if(!isDestroyed()&&recent.getParent()==menuBody){recent.removeAllViews();recent.addView(label("读取失败，请重新打开侧栏重试",12,MUTED));}});}
        });
        space(menuBody,18);
        add(menuBody,menuItem("关于 G studio",()->menuAction(true,()->{page="about";render();}),false),2);
        add(menuBody,label("0.9",11,MUTED),12);
    }
    void chooseProvider(){
        if(app.config.providers.isEmpty()){page="providers";render();return;}String[] names=new String[app.config.providers.size()+1];
        for(int i=0;i<app.config.providers.size();i++){Models.Provider p=app.config.providers.get(i);names[i]=p.name+" · "+p.model;}names[names.length-1]="管理服务 / 修改模型";
        sheet().setTitle("选择服务").setItems(names,(d,i)->{
            if(i==names.length-1){page="providers";render();return;}try{app.selectProvider(app.config.providers.get(i).id);}catch(Exception e){error(e);}
        }).show();
    }
    void renderProviders(){
        header("API 服务",()->{page="chat";render();});LinearLayout b=scrollBody();
        add(b,label("连接你自己的模型服务",small?17:23,INK),4);add(b,label("支持 OpenAI 兼容的中转站与直连接口。",13,MUTED),8);
        for(Models.Provider p:app.config.providers){
            LinearLayout card=column();card.setPadding(dp(14),dp(12),dp(14),dp(12));card.setBackground(background(CARD,16));
            card.addView(label(p.name+(app.chat.providerId.equals(p.id)?"  ✓":""),17,INK));add(card,label(p.model.isEmpty()?(p.imageModel.isEmpty()?"视频 · "+p.videoModel:"图片 · "+p.imageModel):p.model,13,ACCENT),5);
            TextView endpoint=label(p.base,11,MUTED);endpoint.setMaxLines(2);add(card,endpoint,5);LinearLayout controls=row();
            controls.addView(button("使用",()->{try{app.selectProvider(p.id);page="chat";render();}catch(Exception e){error(e);}}),new LinearLayout.LayoutParams(0,-2,1));
            controls.addView(button("编辑",()->openEditor(p)),new LinearLayout.LayoutParams(0,-2,1));
            controls.addView(button("删除",()->sheet().setTitle("删除这个服务？").setMessage("聊天记录会保留，API Key 和服务配置会被删除。")
                .setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{try{app.deleteProvider(p.id);render();}catch(Exception e){error(e);}}).show()),new LinearLayout.LayoutParams(0,-2,1));
            add(card,controls,9);add(b,card,14);
        }
        add(b,button("+ 添加 API 服务",()->openEditor(new Models.Provider())),18);add(b,label("密钥加密保存在本机。请求只发往你配置的服务地址。",12,MUTED),16);
    }
    void openEditor(Models.Provider p){cancelLookup();try{draft=p.copy();app.editing=draft;editId=p.id;page="edit";render();}catch(Exception e){error(e);}}
    EditText field(LinearLayout b,String name,String value,String hint,boolean multi){
        add(b,label(name,13,MUTED),16);EditText e=new EditText(this);e.setTextSize(small?14:16);e.setTextColor(INK);e.setHintTextColor(0xff667c8c);
        e.setPadding(dp(12),dp(10),dp(12),dp(10));e.setBackground(background(CARD,12));e.setText(value);e.setHint(hint);e.setSingleLine(!multi);
        if(multi){e.setInputType(1|InputType.TYPE_TEXT_FLAG_MULTI_LINE);e.setMinLines(3);e.setMaxLines(8);}add(b,e,6);return e;
    }
    void renderEdit(){
        header("编辑服务",()->{hideKeyboard();page="providers";app.editing=null;render();});LinearLayout b=scrollBody();if(draft==null)draft=new Models.Provider();
        nameField=field(b,"服务名称",draft.name,"例如：我的中转站",false);
        baseField=field(b,"API 地址",draft.base,"https://example.com/v1",false);baseField.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        add(b,label("域名自动补 /v1；已有路径会保留，也支持完整 /chat/completions 地址。",11,MUTED),6);
        keyField=field(b,"API Key",draft.key,"填入服务商提供的密钥",false);keyField.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyField.setTransformationMethod(PasswordTransformationMethod.getInstance());keyField.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        modelField=field(b,"聊天模型 ID（可选）",draft.model,"手动输入，或获取模型列表",false);add(b,button("获取模型列表",this::fetchModels),8);
        lookupStatus=label("",12,MUTED);add(b,lookupStatus,6);
        systemField=field(b,"助手设定（可选）",draft.system,"例如：用简洁的中文回答。",true);
        streamField=new CheckBox(this);streamField.setText("流式回复");streamField.setTextColor(INK);streamField.setChecked(draft.stream);add(b,streamField,12);
        add(b,label("不支持流式的中转站，可关闭后使用普通回复。",11,MUTED),2);
        tempField=field(b,"温度（可选，0～2）",draft.temperature,"留空使用服务默认值",false);tempField.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        turnsField=field(b,"携带最近多少轮对话（1～50）",Integer.toString(draft.contextTurns),"10",false);turnsField.setInputType(InputType.TYPE_CLASS_NUMBER);
        httpField=new CheckBox(this);httpField.setText("允许 HTTP 地址");httpField.setTextColor(INK);httpField.setChecked(draft.allowHttp);add(b,httpField,12);
        add(b,label("仅用于你信任的 HTTP 服务；HTTP 不加密密钥和聊天内容。",11,MUTED),2);
        add(b,label("生图与视频（可选）",17,INK),22);
        imageModelField=field(b,"生图模型 ID",draft.imageModel,"填写服务商提供的模型 ID",false);
        videoModelField=field(b,"视频模型 ID",draft.videoModel,"填写服务商提供的模型 ID",false);
        imageProtocolSelection=draft.imageProtocol;
        TextView protocol=button("生图调用方式 · "+CreativeApi.protocolLabel(imageProtocolSelection),()->{});
        protocol.setOnClickListener(v->sheet().setTitle("生图调用方式").setItems(new String[]{"自动选择","Images 直接生图","Responses 工具生图"},(dialog,which)->{imageProtocolSelection=new String[]{"auto","images","responses"}[which];protocol.setText("生图调用方式 · "+CreativeApi.protocolLabel(imageProtocolSelection));}).show());add(b,protocol,12);
        responsesPathField=field(b,"Responses 生图路径",draft.responsesPath,"/responses",false);
        imageToolModelField=field(b,"图片工具模型（可选）",draft.imageToolModel,"留空由服务选择；仅 Responses 生图使用",false);
        imagePathField=field(b,"生图接口路径",draft.imagePath,"/images/generations",false);
        videoPathField=field(b,"视频接口路径",draft.videoPath,"/videos",false);
        add(b,label("路径接在 API 地址后；以服务商文档为准。",11,MUTED),6);
        videoJsonField=new CheckBox(this);videoJsonField.setText("视频使用 JSON 提交");videoJsonField.setTextColor(INK);videoJsonField.setChecked(draft.videoJson);add(b,videoJsonField,10);
        add(b,label("默认使用 multipart，支持标准 Videos 任务查询与下载。",11,MUTED),3);
        TextView save=button("保存并使用",()->{
            try{Models.Provider p=readForm(true);hideKeyboard();app.saveProvider(p);app.editing=null;
                if(p.model.isEmpty()){Creative.Draft d=app.creative.draft;d.providerId=p.id;d.kind=p.imageModel.isEmpty()?"video":"image";d.model=d.kind.equals("image")?p.imageModel:p.videoModel;d.size="";d.seconds="";d.quality="";page="create";}else page="chat";
                render();toast("服务已保存");}catch(Exception e){error(e);}
        });save.setTextColor(BG);save.setBackground(background(ACCENT,14));add(b,save,20);
    }
    Models.Provider readForm(boolean validate){
        Models.Provider p=new Models.Provider();p.id=editId;p.name=nameField.getText().toString().trim();p.base=baseField.getText().toString().trim();
        p.key=keyField.getText().toString().trim();p.model=modelField.getText().toString().trim();p.system=systemField.getText().toString();p.temperature=tempField.getText().toString().trim();
        p.imageModel=imageModelField.getText().toString().trim();p.videoModel=videoModelField.getText().toString().trim();p.imagePath=imagePathField.getText().toString().trim();p.videoPath=videoPathField.getText().toString().trim();p.videoJson=videoJsonField.isChecked();
        p.imageProtocol=imageProtocolSelection;p.responsesPath=responsesPathField.getText().toString().trim();p.imageToolModel=imageToolModelField.getText().toString().trim();
        p.stream=streamField.isChecked();p.allowHttp=httpField.isChecked();try{p.contextTurns=Integer.parseInt(turnsField.getText().toString().trim());}catch(Exception e){p.contextTurns=0;}
        if(validate){
            if(p.name.isEmpty())throw new IllegalArgumentException("请填写服务名称");p.base=Endpoint.normalize(p.base,p.allowHttp);
            if(p.model.isEmpty()&&p.imageModel.isEmpty()&&p.videoModel.isEmpty())throw new IllegalArgumentException("请至少填写一种模型 ID");
            if(p.responsesPath.isEmpty())p.responsesPath="/responses";
            if(p.imagePath.isEmpty())p.imagePath="/images/generations";if(p.videoPath.isEmpty())p.videoPath="/videos";
            CreativeApi.route(p.base,p.responsesPath,p.allowHttp);CreativeApi.route(p.base,p.imagePath,p.allowHttp);CreativeApi.route(p.base,p.videoPath,p.allowHttp);
            if(p.key.contains("\n")||p.key.contains("\r"))throw new IllegalArgumentException("API Key 不能包含换行");
            if(p.contextTurns<1||p.contextTurns>50)throw new IllegalArgumentException("上下文轮数请填写 1～50");
            try{Models.Provider validation=p.copy();if(validation.model.isEmpty())validation.model="validate";ApiClient.request(validation,new ArrayList<>());}catch(Exception e){throw new IllegalArgumentException(e.getMessage());}
        }return p;
    }
    void fetchModels(){
        if(lookup!=null){toast("正在获取模型列表");return;}Models.Provider p=readForm(false);
        try{Endpoint.models(p.base,p.allowHttp);}catch(Exception e){error(e);return;}
        ApiClient.Call call=new ApiClient.Call();lookup=call;lookupStatus.setText("正在连接服务…");
        app.network.execute(()->{
            try{
                ArrayList<String> models=ApiClient.models(p,call);
                main.post(()->{if(lookup!=call||isDestroyed())return;lookup=null;if(!page.equals("edit"))return;
                    lookupStatus.setText(models.isEmpty()?"没有可用模型，请手动填写":"已连接 · "+models.size()+" 个模型");if(!models.isEmpty())pickModel(models);});
            }catch(Exception e){main.post(()->{if(lookup!=call||isDestroyed())return;lookup=null;if(page.equals("edit"))lookupStatus.setText(e.getMessage());});}
        });
    }
    void pickModel(ArrayList<String> models){
        LinearLayout box=column();box.setPadding(dp(12),dp(4),dp(12),0);EditText search=new EditText(this);search.setHint("搜索模型");search.setTextSize(15);box.addView(search);
        ListView list=new ListView(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,new ArrayList<>(models));list.setAdapter(adapter);
        box.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        SheetDialog dialog=sheet().setTitle("选择模型").setView(box,false).setNegativeButton("取消",null).create();
        list.setOnItemClickListener((parent,v,pos,id)->{modelField.setText(adapter.getItem(pos));dialog.dismiss();});
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int st,int before,int c){adapter.clear();String q=s.toString().toLowerCase(Locale.ROOT);for(String m:models)if(m.toLowerCase(Locale.ROOT).contains(q))adapter.add(m);}
            public void afterTextChanged(Editable e){}
        });dialog.show();
    }
    void chatActions(Models.Chat c){
        sheet().setTitle(c.title).setItems(new String[]{"重命名","删除"},(d,which)->{
            if(which==0){
                LinearLayout box=column();EditText title=field(box,"会话名称",c.title,"输入名称",false);TextView save=button("保存",()->{});add(box,save,14);
                SheetDialog dialog=sheet().setTitle("重命名对话").setView(box).setNegativeButton("取消",null).create();
                save.setOnClickListener(v->{try{String value=title.getText().toString().trim();if(value.isEmpty())throw new IllegalArgumentException("请填写名称");String name=value.substring(0,Math.min(value.length(),80));Models.Chat target=app.chat.id.equals(c.id)?app.chat:c;target.title=name;app.store.saveChat(target);dialog.dismiss();render();}catch(Exception e){error(e);}});dialog.show();
            }else sheet().setTitle("删除这段对话？").setMessage("删除后无法恢复，已保存的记忆会保留。").setNegativeButton("取消",null).setPositiveButton("删除",(dd,w)->{
                try{app.store.deleteChat(c.id);try{app.store.pruneAttachments();}catch(Exception ignored){}if(app.chat.id.equals(c.id)){app.chat=new Models.Chat();app.chat.providerId=app.config.selected;app.persist();}render();}catch(Exception e){error(e);}
            }).show();
        }).show();
    }
    void renderAbout(){
        header("关于",()->{page="chat";render();});LinearLayout b=scrollBody();
        add(b,label("G studio",32,ACCENT),12);add(b,label("0.9 · 随身 AI 聊天",16,INK),8);
        add(b,label("支持 OpenAI 兼容聊天、生图与 Videos 视频任务接口，可连接中转站和自建服务。生成能力以服务商和模型支持为准。",15,MUTED),20);
        add(b,label("聊天记录保存在本机；API Key 使用 Android Keystore 加密。无账号、无广告、无统计上传。",15,MUTED),14);
        add(b,label("应用切到后台或被系统结束时，回复可能中断；已保存的内容会保留。",14,MUTED),14);
        add(b,label("支持图片、PDF、DOCX 和文本附件。图片及 PDF 需要服务和模型支持；DOCX 仅提取正文文字。创作页支持文生图与文生视频；本版暂不包含联网工具或自动查询余额。",14,MUTED),14);
    }
    void saveCreativeAsset(Creative.Asset asset){
        try{if(!app.creative.store.asset(asset.file).isFile())throw new java.io.IOException("本地结果文件已丢失");}
        catch(Exception e){error(e);return;}creativeExport=asset.file;
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType(asset.mime);intent.putExtra(Intent.EXTRA_TITLE,asset.name);
        try{startActivityForResult(intent,705);}catch(ActivityNotFoundException e){toast("设备没有文件保存界面，结果仍保存在应用中");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null)return;
        if(request==705&&data.getData()!=null){
            final Uri target=data.getData();final String source=creativeExport;final ContentResolver resolver=getContentResolver();
            app.files.execute(()->{
                try(java.io.InputStream in=new java.io.FileInputStream(app.creative.store.asset(source));java.io.OutputStream out=resolver.openOutputStream(target)){
                    if(out==null)throw new java.io.IOException("无法写入保存位置");byte[] bytes=new byte[32768];int count;while((count=in.read(bytes))!=-1)out.write(bytes,0,count);
                    main.post(()->{if(!isDestroyed())toast("已保存到所选位置");});
                }catch(Exception e){main.post(()->{if(!isDestroyed())error(e);});}
            });
        }else if(request==703){
            if(!pickerChat.equals(app.chat.id)){toast("会话已切换，请在当前会话重新选择附件");return;}
            ArrayList<Uri> uris=new ArrayList<>();ClipData clips=data.getClipData();
            if(clips!=null)for(int n=0;n<clips.getItemCount();n++){Uri uri=clips.getItemAt(n).getUri();if(uri!=null&&!uris.contains(uri))uris.add(uri);}
            else if(data.getData()!=null)uris.add(data.getData());
            try{app.importAttachments(getContentResolver(),uris);}catch(Exception e){error(e);}
        }
    }
}

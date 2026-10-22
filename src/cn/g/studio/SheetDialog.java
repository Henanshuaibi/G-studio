package cn.g.studio;

import android.animation.*;
import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.os.Bundle;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.widget.*;
import java.util.function.Consumer;

/** Shared bottom sheet for all app-owned choices, previews and confirmations. */
final class SheetDialog extends Dialog {
    static final int INK=0xffe9f0f5,MUTED=0xff93a6b5,ACCENT=0xff8be4c3,CARD=0xff1b2a36;
    final SheetRoot root;
    final SheetPanel panel;
    final View scrim;
    final boolean fill;
    final String kind;
    private ValueAnimator animator;
    private float shown;
    private boolean closing,finishing,cancelled;
    private Runnable afterClose;
    private final Runnable enter=()->{if(isShowing()&&!closing)animateTo(1);};

    static final class Builder {
        final Context context;String title="",message="",positive="",negative="",kind="";String[] items;
        View view;boolean scroll=true;
        DialogInterface.OnClickListener itemListener,positiveListener,negativeListener;
        Consumer<SheetDialog> created;
        Builder(Context context){this.context=context;}
        Builder setTitle(String value){title=value;return this;}
        Builder setMessage(String value){message=value;return this;}
        Builder setView(View value){view=value;return this;}
        Builder setView(View value,boolean scroll){view=value;this.scroll=scroll;return this;}
        Builder setItems(String[] value,DialogInterface.OnClickListener action){items=value;itemListener=action;return this;}
        Builder setPositiveButton(String value,DialogInterface.OnClickListener action){positive=value;positiveListener=action;return this;}
        Builder setNegativeButton(String value,DialogInterface.OnClickListener action){negative=value;negativeListener=action;return this;}
        Builder setKind(String value){kind=value;return this;}
        Builder onCreated(Consumer<SheetDialog> value){created=value;return this;}
        SheetDialog create(){SheetDialog d=new SheetDialog(this);if(created!=null)created.accept(d);return d;}
        SheetDialog show(){SheetDialog d=create();d.show();return d;}
    }
    private SheetDialog(Builder b){
        super(b.context,R.style.SheetTheme);fill=b.view!=null&&!b.scroll;kind=b.kind;
        if(b.context instanceof Activity)setOwnerActivity((Activity)b.context);
        root=new SheetRoot(b.context);root.setMotionEventSplittingEnabled(false);
        scrim=new View(b.context);scrim.setBackgroundColor(Color.BLACK);scrim.setAlpha(0);scrim.setContentDescription("收起面板");scrim.setOnClickListener(v->cancel());
        root.addView(scrim,new FrameLayout.LayoutParams(-1,-1));
        panel=new SheetPanel(b.context);panel.setOrientation(LinearLayout.VERTICAL);panel.setClickable(true);panel.setPadding(dp(18),dp(4),dp(18),dp(18));panel.setElevation(dp(20));
        GradientDrawable background=new GradientDrawable();background.setColor(0xff121c26);float radius=dp(26);background.setCornerRadii(new float[]{radius,radius,radius,radius,0,0,0,0});panel.setBackground(background);panel.setClipToOutline(true);
        root.addView(panel,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        FrameLayout gripArea=new FrameLayout(b.context);gripArea.setContentDescription("向下拖动收起面板");View grip=new View(b.context);grip.setBackground(round(0xff637584,2));
        FrameLayout.LayoutParams gp=new FrameLayout.LayoutParams(dp(36),dp(4),Gravity.CENTER);gripArea.addView(grip,gp);panel.addView(gripArea,new LinearLayout.LayoutParams(-1,dp(24)));
        LinearLayout heading=new LinearLayout(b.context);heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text(b.title.isEmpty()?"操作":b.title,small()?18:21,INK);title.setTypeface(null,Typeface.BOLD);title.setMaxLines(2);heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView close=action("×",false,()->cancel());close.setTextSize(24);close.setContentDescription("收起面板");heading.addView(close,new LinearLayout.LayoutParams(dp(44),dp(44)));panel.addView(heading,new LinearLayout.LayoutParams(-1,-2));
        View content;
        if(b.view!=null)content=b.view;
        else{
            LinearLayout column=new LinearLayout(b.context);column.setOrientation(LinearLayout.VERTICAL);
            if(!b.message.isEmpty()){TextView message=text(b.message,small()?14:15,MUTED);message.setPadding(0,dp(8),0,dp(16));message.setLineSpacing(dp(3),1.05f);message.setTextIsSelectable(true);column.addView(message);}
            if(b.items!=null)for(int i=0;i<b.items.length;i++){
                final int index=i;TextView row=action(b.items[i],false,()->dismissThen(()->{if(b.itemListener!=null)b.itemListener.onClick(this,index);}));
                row.setGravity(Gravity.CENTER_VERTICAL|Gravity.LEFT);row.setTextSize(small()?14:16);row.setMinHeight(dp(50));
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(6);column.addView(row,lp);
            }
            content=column;
        }
        FrameLayout body=new FrameLayout(b.context);
        if(fill||content instanceof ScrollView||content instanceof ListView)body.addView(content,new FrameLayout.LayoutParams(-1,fill?-1:-2));
        else{ScrollView scrolling=new ScrollView(b.context);scrolling.setFillViewport(false);scrolling.setClipToPadding(false);scrolling.addView(content);body.addView(scrolling,new FrameLayout.LayoutParams(-1,-2));}
        panel.addView(body,new LinearLayout.LayoutParams(-1,fill?0:-2,1));
        if(!b.negative.isEmpty()||!b.positive.isEmpty()){
            LinearLayout footer=new LinearLayout(b.context);footer.setPadding(0,dp(12),0,0);
            if(!b.negative.isEmpty())footer.addView(action(b.negative,false,()->dismissThen(()->{if(b.negativeListener!=null)b.negativeListener.onClick(this,BUTTON_NEGATIVE);})),new LinearLayout.LayoutParams(0,dp(48),1));
            if(!b.positive.isEmpty()){
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(48),1);if(!b.negative.isEmpty())lp.leftMargin=dp(10);
                footer.addView(action(b.positive,true,()->dismissThen(()->{if(b.positiveListener!=null)b.positiveListener.onClick(this,BUTTON_POSITIVE);})),lp);
            }panel.addView(footer,new LinearLayout.LayoutParams(-1,-2));
        }
        root.setFocusableInTouchMode(true);root.requestFocus();
        root.setOnApplyWindowInsetsListener((v,insets)->{
            int nav=insets.getStableInsetBottom(),bottom=insets.getSystemWindowInsetBottom();boolean keyboard=bottom>nav+dp(24);
            root.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),keyboard?bottom:0);
            int side=b.context.getResources().getConfiguration().isScreenRound()?dp(22):dp(18);
            panel.setPadding(side,dp(4),side,keyboard?dp(12):nav+dp(14));return insets.consumeSystemWindowInsets();
        });
    }
    private int dp(float v){return(int)(v*getContext().getResources().getDisplayMetrics().density+.5f);}
    private boolean small(){return getContext().getResources().getConfiguration().screenWidthDp<320;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String value,float size,int color){TextView v=new TextView(getContext());v.setText(value);v.setTextSize(size);v.setTextColor(color);return v;}
    private TextView action(String label,boolean primary,Runnable runnable){
        TextView v=text(label,small()?14:15,primary?0xff0c1218:INK);v.setGravity(Gravity.CENTER);v.setMinHeight(dp(44));v.setPadding(dp(12),dp(8),dp(12),dp(8));
        v.setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(0x228be4c3),round(primary?ACCENT:CARD,14),null));v.setOnClickListener(w->{if(!closing)runnable.run();});return v;
    }
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);setContentView(root);Window window=getWindow();if(window==null)return;
        window.setBackgroundDrawableResource(android.R.color.transparent);window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        window.setStatusBarColor(Color.TRANSPARENT);window.setNavigationBarColor(Color.TRANSPARENT);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        setCanceledOnTouchOutside(false);
    }
    @Override public void show(){
        closing=false;cancelled=false;afterClose=null;super.show();Window w=getWindow();if(w!=null){w.setLayout(-1,-1);w.setGravity(Gravity.FILL);}
        root.requestApplyInsets();setShown(0);root.post(enter);
    }
    private void setShown(float value){shown=Math.max(0,Math.min(1,value));panel.setTranslationY((1-shown)*panel.getMeasuredHeight());scrim.setAlpha(.5f*shown);}
    private void stopAnimation(){if(animator!=null){ValueAnimator old=animator;animator=null;old.removeAllListeners();old.cancel();}}
    private void animateTo(float end){
        stopAnimation();if(shown==end||!ValueAnimator.areAnimatorsEnabled()){setShown(end);if(end==0)finishClose();return;}
        ValueAnimator a=ValueAnimator.ofFloat(shown,end);animator=a;a.setDuration(Math.max(100,Math.round((end==1?260:190)*Math.abs(end-shown))));a.setInterpolator(new DecelerateInterpolator(1.5f));
        a.addUpdateListener(v->setShown((Float)v.getAnimatedValue()));a.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator value){if(animator!=a)return;animator=null;setShown(end);if(end==0)finishClose();}});a.start();
    }
    void dismissThen(Runnable action){if(closing)return;if(!isShowing()){if(action!=null)action.run();return;}afterClose=action;closing=true;root.resetTouch();root.removeCallbacks(enter);animateTo(0);}
    @Override public void dismiss(){if(finishing){super.dismiss();return;}dismissThen(null);}
    @Override public void cancel(){if(finishing){super.cancel();return;}if(closing)return;cancelled=true;dismissThen(null);}
    private void finishClose(){
        if(!closing)return;Runnable action=afterClose;afterClose=null;finishing=true;
        try{if(cancelled)super.cancel();else super.dismiss();}finally{finishing=false;}
        Activity owner=getOwnerActivity();if(action!=null&&(owner==null||(!owner.isDestroyed()&&!owner.isFinishing())))action.run();
    }
    void dismissNow(){root.removeCallbacks(enter);stopAnimation();afterClose=null;closing=true;root.resetTouch();finishing=true;try{super.dismiss();}finally{finishing=false;}}
    boolean isClosing(){return closing;}
    private final class SheetPanel extends LinearLayout {
        SheetPanel(Context c){super(c);}
        @Override protected void onMeasure(int width,int height){int max=Math.max(dp(1),Math.round(MeasureSpec.getSize(height)*.9f));super.onMeasure(width,MeasureSpec.makeMeasureSpec(max,fill?MeasureSpec.EXACTLY:MeasureSpec.AT_MOST));}
        @Override protected void onLayout(boolean changed,int l,int t,int r,int b){super.onLayout(changed,l,t,r,b);setShown(shown);}
    }
    private final class SheetRoot extends FrameLayout {
        final int slop;float x,y,start;boolean eligible,dragging;int pointer;VelocityTracker velocity;
        SheetRoot(Context c){super(c);slop=ViewConfiguration.get(c).getScaledTouchSlop();}
        void recycleVelocity(){if(velocity!=null){velocity.recycle();velocity=null;}}
        void resetTouch(){eligible=false;dragging=false;recycleVelocity();}
        @Override public boolean dispatchTouchEvent(MotionEvent e){
            int action=e.getActionMasked();if(action==MotionEvent.ACTION_DOWN){recycleVelocity();velocity=VelocityTracker.obtain();x=e.getX();y=e.getY();pointer=e.getPointerId(0);float top=panel.getTop()+panel.getTranslationY();eligible=!closing&&y>=top&&y<=top+dp(70);dragging=false;}
            if(velocity!=null)velocity.addMovement(e);
            if(action==MotionEvent.ACTION_POINTER_DOWN){if(dragging){dragging=false;animateTo(1);}eligible=false;}
            boolean result=super.dispatchTouchEvent(e);if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){eligible=false;dragging=false;recycleVelocity();}return result;
        }
        private boolean startDrag(MotionEvent e){
            if(!eligible||closing)return false;float dy=e.getY()-y,dx=e.getX()-x;
            if(Math.abs(dx)>slop&&Math.abs(dx)>Math.abs(dy)){eligible=false;return false;}
            if(dy>slop&&dy>Math.abs(dx)*1.15f){root.removeCallbacks(enter);stopAnimation();start=shown;dragging=true;return true;}return false;
        }
        @Override public boolean onInterceptTouchEvent(MotionEvent e){if(dragging)return true;return e.getActionMasked()==MotionEvent.ACTION_MOVE&&startDrag(e);}
        @Override public boolean onTouchEvent(MotionEvent e){
            int action=e.getActionMasked();if(closing)return true;if(!dragging&&action==MotionEvent.ACTION_MOVE)startDrag(e);
            if(dragging){
                if(action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_UP)setShown(start-(e.getY()-y)/Math.max(1,panel.getHeight()));
                if(action==MotionEvent.ACTION_CANCEL){dragging=false;animateTo(1);}
                else if(action==MotionEvent.ACTION_UP){float speed=0;if(velocity!=null){velocity.computeCurrentVelocity(1000);speed=velocity.getYVelocity(pointer);}dragging=false;if(shown<.7f||speed>dp(450))cancel();else animateTo(1);}return true;
            }return eligible||super.onTouchEvent(e);
        }
    }
}

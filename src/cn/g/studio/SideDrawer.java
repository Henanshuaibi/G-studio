package cn.g.studio;

import android.animation.*;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.*;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import java.util.Collections;

/** A modal left drawer with horizontal dragging and independent vertical menu scrolling. */
final class SideDrawer extends FrameLayout {
    final View content,scrim;
    final FrameLayout panel;
    final int slop,edge;
    final float fling;
    private float fraction,downX,downY,startFraction;
    private boolean targetOpen,gestures=true,eligible,dragging,outside,vertical,blocked;
    private int pointerId;
    private VelocityTracker velocity;
    private ValueAnimator animation;
    private Runnable opening,afterClose;
    private View previousFocus;

    SideDrawer(Context context,View content){
        super(context);this.content=content;
        ViewConfiguration config=ViewConfiguration.get(context);slop=config.getScaledTouchSlop();edge=dp(24);fling=Math.max(config.getScaledMinimumFlingVelocity(),dp(450));
        setMotionEventSplittingEnabled(false);
        addView(content,new LayoutParams(-1,-1));
        scrim=new View(context);scrim.setBackgroundColor(0xff000000);scrim.setVisibility(GONE);scrim.setContentDescription("收起菜单");
        scrim.setOnClickListener(v->close());addView(scrim,new LayoutParams(-1,-1));
        panel=new FrameLayout(context);GradientDrawable bg=new GradientDrawable();bg.setColor(0xff121c26);
        float radius=dp(24);bg.setCornerRadii(new float[]{0,0,radius,radius,radius,radius,0,0});panel.setBackground(bg);panel.setClipToOutline(true);panel.setElevation(dp(18));
        panel.setVisibility(INVISIBLE);panel.setFocusableInTouchMode(true);panel.setContentDescription("G studio 菜单");
        addView(panel,new LayoutParams(dp(300),-1,Gravity.LEFT));
    }
    private int dp(float value){return(int)(value*getResources().getDisplayMetrics().density+.5f);}
    void setMenu(View menu){panel.removeAllViews();panel.addView(menu,new LayoutParams(-1,-1));}
    void setOnOpening(Runnable callback){opening=callback;}
    void setInsets(int left,int top,int bottom,int roundEdge){panel.setPadding(Math.max(dp(14),Math.max(left,roundEdge)),top+dp(12),Math.max(dp(12),roundEdge),bottom+dp(12));}
    void setGesturesEnabled(boolean enabled){gestures=enabled;if(!enabled)closeImmediately();updateExclusion();}
    boolean isShowing(){return fraction>0||targetOpen;}
    boolean willOpen(){return dragging?fraction>=.5f:targetOpen;}
    float progress(){return fraction;}
    int drawerWidth(){return panel.getMeasuredWidth();}
    private void prepare(){
        if(panel.getVisibility()==VISIBLE)return;
        previousFocus=content.findFocus();
        if(opening!=null)opening.run();
        panel.setVisibility(VISIBLE);scrim.setVisibility(VISIBLE);panel.requestFocus();
        content.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }
    void open(){
        if(!gestures)return;
        prepare();animateTo(true,null);
    }
    void restoreOpen(){if(gestures){prepare();targetOpen=true;setFraction(1);}}
    void close(){animateTo(false,null);}
    void closeThen(Runnable action){animateTo(false,action);}
    private void cancelAnimation(){
        if(animation!=null){ValueAnimator old=animation;animation=null;old.removeAllListeners();old.cancel();}
        afterClose=null;
    }
    void closeImmediately(){cancelAnimation();targetOpen=false;setFraction(0);finishClosed();}
    private void setFraction(float value){
        fraction=Math.max(0,Math.min(1,value));panel.setTranslationX((fraction-1)*drawerWidth());scrim.setAlpha(.52f*fraction);
        if(fraction>0){panel.setVisibility(VISIBLE);scrim.setVisibility(VISIBLE);}
    }
    private void finishClosed(){
        panel.setVisibility(INVISIBLE);scrim.setVisibility(GONE);content.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        if(previousFocus!=null&&previousFocus.isAttachedToWindow())previousFocus.requestFocus();previousFocus=null;
    }
    private void animateTo(boolean open,Runnable completion){
        cancelAnimation();targetOpen=open;afterClose=completion;
        if(open)prepare();
        float end=open?1:0;
        if(fraction==end||!ValueAnimator.areAnimatorsEnabled()){
            setFraction(end);settled();return;
        }
        ValueAnimator animator=ValueAnimator.ofFloat(fraction,end);animation=animator;
        animator.setDuration(Math.max(100,Math.round(260*Math.abs(end-fraction))));animator.setInterpolator(new DecelerateInterpolator(1.6f));
        animator.addUpdateListener(a->setFraction((Float)a.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator a){if(animation!=animator)return;animation=null;setFraction(end);settled();}});animator.start();
    }
    private void settled(){
        if(targetOpen){panel.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);return;}
        finishClosed();Runnable action=afterClose;afterClose=null;if(action!=null)action.run();
    }
    @Override protected void onMeasure(int w,int h){
        LayoutParams lp=(LayoutParams)panel.getLayoutParams();lp.width=Math.max(1,Math.min(dp(320),MeasureSpec.getSize(w)-dp(48)));super.onMeasure(w,h);
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){super.onLayout(changed,l,t,r,b);setFraction(fraction);updateExclusion();}
    private void updateExclusion(){
        if(Build.VERSION.SDK_INT<29)return;
        // Android allows 200 dp per edge: reserve the middle section for opening the drawer.
        if(!gestures||getHeight()==0){setSystemGestureExclusionRects(Collections.emptyList());return;}
        int height=Math.min(dp(200),getHeight());int top=(getHeight()-height)/2;
        setSystemGestureExclusionRects(Collections.singletonList(new Rect(0,top,edge,top+height)));
    }
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event){
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){
            if(velocity!=null)velocity.recycle();velocity=VelocityTracker.obtain();
            downX=event.getX();downY=event.getY();startFraction=fraction;pointerId=event.getPointerId(0);
            eligible=gestures&&(isShowing()||downX<=edge);dragging=false;vertical=false;blocked=false;
            outside=isShowing()&&downX>drawerWidth()*fraction;
        }
        if(velocity!=null)velocity.addMovement(event);
        if(action==MotionEvent.ACTION_POINTER_DOWN){
            if(dragging){settleDrag(true);blocked=true;}eligible=false;
        }
        boolean handled=super.dispatchTouchEvent(event);
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){
            eligible=false;dragging=false;outside=false;blocked=false;
            if(velocity!=null){velocity.recycle();velocity=null;}
        }return handled;
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent event){
        if(blocked)return true;
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN)return outside;
        if(dragging)return true;
        if(action==MotionEvent.ACTION_MOVE&&tryStartDrag(event))return true;
        return outside;
    }
    private boolean tryStartDrag(MotionEvent event){
        if(!eligible||vertical)return false;
        float dx=event.getX()-downX,dy=event.getY()-downY;
        if(Math.abs(dy)>slop&&Math.abs(dy)>Math.abs(dx)*1.15f){vertical=true;return false;}
        if(Math.abs(dx)>slop&&Math.abs(dx)>Math.abs(dy)*1.15f&&(isShowing()||dx>0)){
            startFraction=fraction;cancelAnimation();prepare();dragging=true;
            if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);return true;
        }return false;
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        int action=event.getActionMasked();if(blocked)return true;
        if(action==MotionEvent.ACTION_MOVE&&!dragging)tryStartDrag(event);
        if(dragging){
            if(action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_UP)setFraction(startFraction+(event.getX()-downX)/Math.max(1,drawerWidth()));
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)settleDrag(action==MotionEvent.ACTION_CANCEL);
            return true;
        }
        if(outside){
            if(action==MotionEvent.ACTION_UP&&Math.abs(event.getX()-downX)<slop&&Math.abs(event.getY()-downY)<slop)close();
            return true;
        }
        return isShowing()||eligible||super.onTouchEvent(event);
    }
    private void settleDrag(boolean cancelled){
        float vx=0,vy=0;if(velocity!=null&&!cancelled){velocity.computeCurrentVelocity(1000);vx=velocity.getXVelocity(pointerId);vy=velocity.getYVelocity(pointerId);}
        boolean open=Math.abs(vx)>fling&&Math.abs(vx)>Math.abs(vy)?vx>0:fraction>=.5f;
        dragging=false;animateTo(open,null);
    }
    void dispose(){cancelAnimation();opening=null;if(velocity!=null){velocity.recycle();velocity=null;}}
    @Override protected void onDetachedFromWindow(){
        cancelAnimation();setFraction(targetOpen?1:0);if(!targetOpen)finishClosed();
        if(velocity!=null){velocity.recycle();velocity=null;}super.onDetachedFromWindow();
    }
}

package com.tungee.amapbridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.*;
import java.util.regex.*;

public class AmapAccessibilityService extends AccessibilityService {
    private static volatile AmapAccessibilityService instance;
    private final Handler h=new Handler(Looper.getMainLooper());
    private long lastRun=0;
    private int scrollCount=0;
    private boolean clickedInfo=false;
    private boolean retriedNetwork=false;
    private String lastTask="";

    @Override protected void onServiceConnected(){
        super.onServiceConnected();
        instance=this;
        BridgeService.sendDebug(TaskState.taskId,"无障碍服务已连接");
    }
    @Override public void onDestroy(){ if(instance==this) instance=null; super.onDestroy(); }
    public static boolean isAlive(){ return instance!=null; }
    public static void kick(){
        AmapAccessibilityService s=instance;
        if(s!=null){ s.h.removeCallbacks(s.scanRunnable); s.h.postDelayed(s.scanRunnable,500); }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        if(!TaskState.active)return;
        if(event.getPackageName()==null || !"com.autonavi.minimap".contentEquals(event.getPackageName()))return;
        long now=System.currentTimeMillis();
        if(now-lastRun<400)return;
        lastRun=now;
        h.removeCallbacks(scanRunnable);
        h.postDelayed(scanRunnable,300);
    }

    private final Runnable scanRunnable=this::scan;

    private void resetForTask(){
        String id=TaskState.taskId==null?"":TaskState.taskId;
        if(!id.equals(lastTask)){
            lastTask=id;
            scrollCount=0;
            clickedInfo=false;
            retriedNetwork=false;
        }
    }

    private void scan(){
        if(!TaskState.active)return;
        resetForTask();
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null){
            BridgeService.sendDebug(TaskState.taskId,"高德窗口节点为空，继续等待");
            h.postDelayed(scanRunnable,800);
            return;
        }

        ArrayList<String> texts=new ArrayList<>();
        collect(root,texts,0);
        String raw=String.join(" | ",texts);
        if(raw.length()>24000) raw=raw.substring(0,24000);
        BridgeService.sendDebug(TaskState.taskId,"读取到高德界面文字 "+texts.size()+" 项，已滑动="+scrollCount);

        if(raw.contains("网络好像开小差") || raw.contains("网络开小差") || raw.contains("加载失败")){
            if(!retriedNetwork && clickAny(root,new String[]{"刷新重试","重新加载","重试"})){
                retriedNetwork=true;
                BridgeService.sendDebug(TaskState.taskId,"检测到高德网络异常，已自动点击刷新重试");
                h.postDelayed(scanRunnable,2600);
                return;
            }
            long elapsed=System.currentTimeMillis()-TaskState.startedAt;
            if(elapsed>6500){
                Result r=parse(raw,TaskState.hotelName);
                finish(r,"amap_network_error","高德页面提示网络异常，请先确认手机高德可正常加载",raw);
                return;
            }
        }

        Result r=parse(raw,TaskState.hotelName);
        long elapsed=System.currentTimeMillis()-TaskState.startedAt;
        if(!r.open.isEmpty() && (elapsed>1300 || !r.rooms.isEmpty() || !r.phone.isEmpty())){
            finish(r,"success","",raw);
            return;
        }

        // 只点击明确的酒店信息入口，避免误点右上角“更多”。
        if(!clickedInfo && clickAny(root,new String[]{"酒店信息","酒店详情","设施服务","酒店设施","更多酒店信息"})){
            clickedInfo=true;
            BridgeService.sendDebug(TaskState.taskId,"已点击酒店信息/酒店详情入口");
            h.postDelayed(scanRunnable,1000);
            return;
        }

        if(scrollCount<15){
            boolean moved=scrollForward(root);
            if(!moved) moved=swipeUp();
            scrollCount++;
            BridgeService.sendDebug(TaskState.taskId,moved?"已执行上滑，继续查找开业信息":"本轮未能滚动，稍后重试");
            h.postDelayed(scanRunnable,moved?900:700);
            return;
        }

        if(elapsed>26000 || scrollCount>=15){
            finish(r,r.open.isEmpty()?"open_time_not_found":"partial","",raw);
        }else h.postDelayed(scanRunnable,700);
    }

    private void finish(Result r,String status,String extra,String raw){
        String evidence="开业="+r.open+"；装修="+r.renovate+"；客房="+r.rooms+"；电话="+r.phone;
        if(extra!=null&&!extra.isEmpty()) evidence=evidence+"；"+extra;
        BridgeService.sendResult(TaskState.taskId,TaskState.poiId,TaskState.hotelName,r.open,r.renovate,r.rooms,r.phone,r.nameCheck,status,evidence,raw);
        TaskState.clear();
        scrollCount=0;
        clickedInfo=false;
        retriedNetwork=false;
        lastTask="";
    }

    private void collect(AccessibilityNodeInfo n,List<String> out,int d){
        if(n==null||d>45||out.size()>1600)return;
        CharSequence t=n.getText(),cd=n.getContentDescription();
        if(t!=null){String s=t.toString().trim();if(!s.isEmpty())out.add(s);}
        if(cd!=null){String s=cd.toString().trim();if(!s.isEmpty())out.add(s);}
        for(int i=0;i<n.getChildCount();i++)collect(n.getChild(i),out,d+1);
    }

    private boolean clickAny(AccessibilityNodeInfo n,String[] keys){
        if(n==null)return false;
        CharSequence t=n.getText();
        CharSequence cd=n.getContentDescription();
        String s=t!=null?t.toString():(cd!=null?cd.toString():"");
        if(!s.isEmpty()){
            for(String k:keys){
                if(s.equals(k)||s.contains(k)){
                    AccessibilityNodeInfo c=n;
                    for(int i=0;i<6&&c!=null;i++,c=c.getParent()){
                        if(c.isClickable()&&c.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;
                    }
                }
            }
        }
        for(int i=0;i<n.getChildCount();i++) if(clickAny(n.getChild(i),keys))return true;
        return false;
    }

    private boolean scrollForward(AccessibilityNodeInfo n){
        if(n==null)return false;
        if(n.isScrollable()&&n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))return true;
        for(int i=0;i<n.getChildCount();i++) if(scrollForward(n.getChild(i)))return true;
        return false;
    }

    private boolean swipeUp(){
        try{
            DisplayMetrics dm=getResources().getDisplayMetrics();
            float x=dm.widthPixels*0.50f;
            float y1=dm.heightPixels*0.78f;
            float y2=dm.heightPixels*0.30f;
            Path p=new Path();
            p.moveTo(x,y1);
            p.lineTo(x,y2);
            GestureDescription.StrokeDescription stroke=new GestureDescription.StrokeDescription(p,0,430);
            GestureDescription gesture=new GestureDescription.Builder().addStroke(stroke).build();
            return dispatchGesture(gesture,null,null);
        }catch(Exception e){
            BridgeService.sendDebug(TaskState.taskId,"手势上滑异常："+e.getClass().getSimpleName());
            return false;
        }
    }

    private static class Result{String open="",renovate="",rooms="",phone="",nameCheck="";}

    private Result parse(String raw,String expected){
        Result r=new Result();
        String s=raw.replace('：',':');
        r.open=first(s,new String[]{
            "(?:开业时间|开业|开张|开店)\\s*[:：]?\\s*((?:19|20)\\d{2}[年./-](?:0?[1-9]|1[0-2])月?(?:[./-]?(?:0?[1-9]|[12]\\d|3[01])日?)?)",
            "((?:19|20)\\d{2}年(?:0?[1-9]|1[0-2])月(?:[0-3]?\\d日)?)\\s*(?:开业|开张|开店)",
            "(?:开业时间|开业)\\s*[:：]?\\s*((?:19|20)\\d{2})年?"
        });
        r.renovate=first(s,new String[]{
            "(?:装修时间|装修|翻新)\\s*[:：]?\\s*((?:19|20)\\d{2}[年./-](?:0?[1-9]|1[0-2])月?)",
            "((?:19|20)\\d{2}年(?:0?[1-9]|1[0-2])月?)\\s*(?:装修|翻新)",
            "(?:装修时间|装修|翻新)\\s*[:：]?\\s*((?:19|20)\\d{2})年?"
        });
        r.rooms=first(s,new String[]{
            "(?:客房数|客房数量|房间数|房型数量)\\s*[:：]?\\s*(\\d{1,4})\\s*间?",
            "(\\d{1,4})\\s*间\\s*(?:客房|房间)"
        });
        r.phone=first(s,new String[]{
            "(?:酒店电话|联系电话|电话|前台电话)\\s*[:：]?\\s*((?:\\+?86[- ]?)?(?:0\\d{2,3}[- ]?)?\\d{7,8}|1[3-9]\\d{9})"
        });
        String a=norm(expected),b=norm(s);
        if(!a.isEmpty()){
            String key=a.length()>8?a.substring(0,8):a;
            r.nameCheck=b.contains(key)?"通过":"待核实";
        }
        return r;
    }

    private String first(String s,String[] ps){
        for(String p:ps){
            Matcher m=Pattern.compile(p,Pattern.CASE_INSENSITIVE).matcher(s);
            if(m.find())return m.group(1).replace(" ","").trim();
        }
        return "";
    }
    private String norm(String s){
        return s==null?"":s.replaceAll("[\\s·•・\\-—_（）()【】\\[\\]<>〈〉]","").replace("酒店","").replace("宾馆","");
    }
    @Override public void onInterrupt(){}
}

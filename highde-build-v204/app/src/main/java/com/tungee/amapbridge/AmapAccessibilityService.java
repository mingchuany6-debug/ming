package com.tungee.amapbridge;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.*;
import java.util.regex.*;

public class AmapAccessibilityService extends AccessibilityService {
    private static volatile AmapAccessibilityService instance;
    private final Handler h=new Handler(Looper.getMainLooper());
    private long lastRun=0; private int scrollCount=0; private boolean clickedInfo=false; private String lastTask="";

    @Override protected void onServiceConnected(){
        super.onServiceConnected(); instance=this;
        BridgeService.sendDebug(TaskState.taskId,"无障碍服务已连接");
    }
    @Override public void onDestroy(){ if(instance==this) instance=null; super.onDestroy(); }
    public static boolean isAlive(){ return instance!=null; }
    public static void kick(){
        AmapAccessibilityService s=instance;
        if(s!=null){ s.h.removeCallbacks(s.scanRunnable); s.h.postDelayed(s.scanRunnable,600); }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        if(!TaskState.active)return;
        if(event.getPackageName()==null || !"com.autonavi.minimap".contentEquals(event.getPackageName()))return;
        long now=System.currentTimeMillis(); if(now-lastRun<450)return; lastRun=now;
        h.removeCallbacks(scanRunnable); h.postDelayed(scanRunnable,350);
    }

    private final Runnable scanRunnable=this::scan;

    private void resetForTask(){
        String id=TaskState.taskId==null?"":TaskState.taskId;
        if(!id.equals(lastTask)){ lastTask=id; scrollCount=0; clickedInfo=false; }
    }

    private void scan(){
        if(!TaskState.active)return;
        resetForTask();
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null){ BridgeService.sendDebug(TaskState.taskId,"高德窗口节点为空，继续等待"); h.postDelayed(scanRunnable,800);return; }
        ArrayList<String> texts=new ArrayList<>(); collect(root,texts,0);
        String raw=String.join(" | ",texts); if(raw.length()>20000) raw=raw.substring(0,20000);
        BridgeService.sendDebug(TaskState.taskId,"读取到高德界面文字 "+texts.size()+" 项，滚动="+scrollCount);

        if(!clickedInfo && clickAny(root,new String[]{"酒店信息","酒店详情","设施服务","详情","更多信息","更多"})){
            clickedInfo=true; BridgeService.sendDebug(TaskState.taskId,"已尝试点击酒店信息/详情"); h.postDelayed(scanRunnable,900); return;
        }

        Result r=parse(raw,TaskState.hotelName);
        long elapsed=System.currentTimeMillis()-TaskState.startedAt;
        if(!r.open.isEmpty() && (elapsed>1400 || !r.rooms.isEmpty() || !r.phone.isEmpty())){ finish(r,"success",raw); return; }

        if(scrollCount<12){
            if(scrollForward(root)){scrollCount++; h.postDelayed(scanRunnable,850); return;}
            scrollCount++;
        }
        if(elapsed>22000 || scrollCount>=12){ finish(r,r.open.isEmpty()?"open_time_not_found":"partial",raw); }
        else h.postDelayed(scanRunnable,800);
    }

    private void finish(Result r,String status,String raw){
        String evidence="开业="+r.open+"；装修="+r.renovate+"；客房="+r.rooms+"；电话="+r.phone;
        BridgeService.sendResult(TaskState.taskId,TaskState.poiId,TaskState.hotelName,r.open,r.renovate,r.rooms,r.phone,r.nameCheck,status,evidence,raw);
        TaskState.clear(); scrollCount=0; clickedInfo=false; lastTask="";
    }

    private void collect(AccessibilityNodeInfo n,List<String> out,int d){
        if(n==null||d>45||out.size()>1500)return;
        CharSequence t=n.getText(),cd=n.getContentDescription();
        if(t!=null){String s=t.toString().trim();if(!s.isEmpty())out.add(s);}
        if(cd!=null){String s=cd.toString().trim();if(!s.isEmpty())out.add(s);}
        for(int i=0;i<n.getChildCount();i++)collect(n.getChild(i),out,d+1);
    }

    private boolean clickAny(AccessibilityNodeInfo n,String[] keys){
        if(n==null)return false;
        CharSequence t=n.getText();
        if(t!=null){
            String s=t.toString();
            for(String k:keys) if(s.equals(k)||s.contains(k)){
                AccessibilityNodeInfo c=n;
                for(int i=0;i<6&&c!=null;i++,c=c.getParent()) if(c.isClickable()&&c.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;
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

    private static class Result{String open="",renovate="",rooms="",phone="",nameCheck="";}
    private Result parse(String raw,String expected){
        Result r=new Result(); String s=raw.replace('：',':');
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
        r.rooms=first(s,new String[]{"(?:客房数|客房数量|房间数)\\s*[:：]?\\s*(\\d{1,4})\\s*间?","(\\d{1,4})\\s*间\\s*(?:客房|房间)"});
        r.phone=first(s,new String[]{"(?:酒店电话|联系电话|电话|前台电话)\\s*[:：]?\\s*((?:\\+?86[- ]?)?(?:0\\d{2,3}[- ]?)?\\d{7,8}|1[3-9]\\d{9})"});
        String a=norm(expected), b=norm(s);
        if(!a.isEmpty()) r.nameCheck=b.contains(a.length()>8?a.substring(0,8):a)?"通过":"待核实";
        return r;
    }
    private String first(String s,String[] ps){
        for(String p:ps){Matcher m=Pattern.compile(p,Pattern.CASE_INSENSITIVE).matcher(s);if(m.find())return m.group(1).replace(" ","").trim();}
        return "";
    }
    private String norm(String s){return s==null?"":s.replaceAll("[\\s·•・\\-—_（）()【】\\[\\]<>〈〉]","").replace("酒店","").replace("宾馆","");}
    @Override public void onInterrupt(){}
}

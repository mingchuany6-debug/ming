package com.tungee.amapbridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
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
    private int scrollCount=0, searchScrollCount=0, searchSubmitAttempts=0;
    private boolean clickedInfo=false, retriedNetwork=false, searchUsed=false, searchMode=false;
    private boolean searchSubmitted=false, directFallbackUsed=false;
    private String lastTask="";

    @Override protected void onServiceConnected(){
        super.onServiceConnected(); instance=this;
        BridgeService.sendDebug(TaskState.taskId,"无障碍服务已连接");
    }
    @Override public void onDestroy(){ if(instance==this)instance=null; super.onDestroy(); }
    public static boolean isAlive(){ return instance!=null; }
    public static void kick(){
        AmapAccessibilityService s=instance;
        if(s!=null){s.h.removeCallbacks(s.scanRunnable);s.h.postDelayed(s.scanRunnable,350);}
    }
    public static void markSearchMode(){
        AmapAccessibilityService s=instance;
        if(s!=null){
            s.searchMode=true;s.searchUsed=true;s.scrollCount=0;s.searchScrollCount=0;s.clickedInfo=false;
            s.searchSubmitted=false;s.searchSubmitAttempts=0;s.directFallbackUsed=false;
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        if(!TaskState.active)return;
        if(event.getPackageName()==null||!"com.autonavi.minimap".contentEquals(event.getPackageName()))return;
        long now=System.currentTimeMillis();if(now-lastRun<260)return;lastRun=now;
        h.removeCallbacks(scanRunnable);h.postDelayed(scanRunnable,220);
    }

    private final Runnable scanRunnable=this::scan;

    private void resetForTask(){
        String id=TaskState.taskId==null?"":TaskState.taskId;
        if(!id.equals(lastTask)){
            lastTask=id;scrollCount=0;searchScrollCount=0;clickedInfo=false;retriedNetwork=false;
            searchUsed=false;searchMode=false;searchSubmitted=false;searchSubmitAttempts=0;directFallbackUsed=false;
        }
    }

    private void scan(){
        if(!TaskState.active)return;
        resetForTask();
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null){BridgeService.sendDebug(TaskState.taskId,"高德窗口节点为空，继续等待");h.postDelayed(scanRunnable,650);return;}

        ArrayList<String> texts=new ArrayList<>();collect(root,texts,0);
        String raw=String.join(" | ",texts);if(raw.length()>26000)raw=raw.substring(0,26000);
        BridgeService.sendDebug(TaskState.taskId,(searchMode?"搜索阶段":"详情阶段")+"读取文字 "+texts.size()+" 项｜滑动="+scrollCount+"｜搜索滑动="+searchScrollCount);

        if(handleNetworkError(root,raw))return;
        if(searchMode){handleSearchPage(root,raw);return;}
        handleDetailPage(root,raw);
    }

    private boolean handleNetworkError(AccessibilityNodeInfo root,String raw){
        if(raw.contains("网络好像开小差")||raw.contains("网络开小差")||raw.contains("加载失败")){
            if(!retriedNetwork&&clickAny(root,new String[]{"刷新重试","重新加载","重试"})){
                retriedNetwork=true;BridgeService.sendDebug(TaskState.taskId,"检测到高德网络异常，已点击刷新重试");h.postDelayed(scanRunnable,2200);return true;
            }
            if(System.currentTimeMillis()-TaskState.startedAt>6500){
                Result r=parse(raw,TaskState.hotelName);finish(r,"amap_network_error","高德页面提示网络异常",raw);return true;
            }
        }
        return false;
    }

    private void handleSearchPage(AccessibilityNodeInfo root,String raw){
        // 关键修复：URI 在部分高德版本只会把关键词放进搜索框，并不会真正“提交搜索”。
        // 必须主动聚焦搜索框、写入酒店名并执行 IME_SEARCH/点击搜索。
        if(!searchSubmitted){
            boolean ok=submitSearch(root,TaskState.hotelName);
            searchSubmitAttempts++;
            if(ok){
                searchSubmitted=true;
                BridgeService.sendDebug(TaskState.taskId,"已主动提交高德搜索："+TaskState.hotelName);
                h.postDelayed(scanRunnable,1350);return;
            }
            if(searchSubmitAttempts<=4){
                BridgeService.sendDebug(TaskState.taskId,"搜索框已出现但尚未提交，正在第"+searchSubmitAttempts+"次触发搜索");
                h.postDelayed(scanRunnable,650);return;
            }
            BridgeService.sendDebug(TaskState.taskId,"搜索提交未成功，尝试POI直达兜底");
            if(fallbackPoi())return;
        }

        if(looksLikeHotelDetail(raw,TaskState.hotelName)){
            searchMode=false;scrollCount=0;clickedInfo=false;
            BridgeService.sendDebug(TaskState.taskId,"搜索已直接进入目标酒店详情");
            h.postDelayed(scanRunnable,300);return;
        }
        if(clickBestHotelResult(root,TaskState.hotelName)){
            searchMode=false;scrollCount=0;clickedInfo=false;
            BridgeService.sendDebug(TaskState.taskId,"已匹配并点击目标酒店搜索结果");
            h.postDelayed(scanRunnable,900);return;
        }

        if(searchScrollCount<3){
            boolean moved=scrollForward(root);if(!moved)moved=swipeUp();
            searchScrollCount++;
            BridgeService.sendDebug(TaskState.taskId,moved?"搜索结果未命中，继续向下找目标酒店":"搜索结果页无法滚动，继续等待");
            h.postDelayed(scanRunnable,650);return;
        }
        if(fallbackPoi())return;
        Result r=parse(raw,TaskState.hotelName);finish(r,"search_no_match","关键词搜索未找到匹配酒店",raw);
    }

    private boolean submitSearch(AccessibilityNodeInfo root,String keyword){
        try{
            AccessibilityNodeInfo input=findSearchInput(root,keyword);
            if(input==null){
                boolean tapped=tapNodeContaining(root,keyword);
                if(tapped){BridgeService.sendDebug(TaskState.taskId,"已点击当前搜索词所在搜索框，等待输入控件出现");}
                return false;
            }
            input.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,keyword);
            boolean set=input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args);
            if(set)BridgeService.sendDebug(TaskState.taskId,"已把酒店名写入高德搜索框");

            if(Build.VERSION.SDK_INT>=30){
                try{
                    if(input.performAction(AccessibilityNodeInfo.ACTION_IME_ENTER)){
                        BridgeService.sendDebug(TaskState.taskId,"已通过键盘搜索动作提交");
                        return true;
                    }
                }catch(Throwable ignored){}
            }
            if(clickExact(root,new String[]{"搜索","搜索一下","查找"})){
                BridgeService.sendDebug(TaskState.taskId,"已点击高德搜索按钮提交");return true;
            }
            // 某些输入法不暴露“搜索”按钮，最后模拟点击软键盘右下角搜索键。
            if(set&&tapImeSearch()){
                BridgeService.sendDebug(TaskState.taskId,"已点击软键盘右下角搜索键");return true;
            }
        }catch(Exception e){
            BridgeService.sendDebug(TaskState.taskId,"主动搜索异常："+e.getClass().getSimpleName());
        }
        return false;
    }

    private AccessibilityNodeInfo findSearchInput(AccessibilityNodeInfo n,String keyword){
        if(n==null)return null;
        String cls=String.valueOf(n.getClassName());
        CharSequence t=n.getText();String s=t==null?"":t.toString().trim();
        if(n.isEditable()||cls.contains("EditText"))return n;
        if(!s.isEmpty()&&keyword!=null&&s.equals(keyword)){
            AccessibilityNodeInfo c=n;
            for(int i=0;i<5&&c!=null;i++,c=c.getParent()){
                String cc=String.valueOf(c.getClassName());
                if(c.isEditable()||cc.contains("EditText"))return c;
            }
        }
        for(int i=0;i<n.getChildCount();i++){
            AccessibilityNodeInfo x=findSearchInput(n.getChild(i),keyword);if(x!=null)return x;
        }
        return null;
    }

    private boolean tapNodeContaining(AccessibilityNodeInfo n,String keyword){
        if(n==null||keyword==null||keyword.isEmpty())return false;
        CharSequence t=n.getText();
        if(t!=null&&t.toString().contains(keyword)){
            AccessibilityNodeInfo c=n;
            for(int i=0;i<6&&c!=null;i++,c=c.getParent()){
                if(c.isClickable()&&c.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;
            }
            Rect b=new Rect();n.getBoundsInScreen(b);if(!b.isEmpty())return tap(b.centerX(),b.centerY());
        }
        for(int i=0;i<n.getChildCount();i++)if(tapNodeContaining(n.getChild(i),keyword))return true;
        return false;
    }

    private boolean clickExact(AccessibilityNodeInfo n,String[] keys){
        if(n==null)return false;
        CharSequence t=n.getText(),cd=n.getContentDescription();
        String a=t==null?"":t.toString().trim(),b=cd==null?"":cd.toString().trim();
        for(String k:keys){
            if(a.equals(k)||b.equals(k)){
                AccessibilityNodeInfo c=n;
                for(int i=0;i<6&&c!=null;i++,c=c.getParent())if(c.isClickable()&&c.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;
            }
        }
        for(int i=0;i<n.getChildCount();i++)if(clickExact(n.getChild(i),keys))return true;
        return false;
    }

    private boolean fallbackPoi(){
        if(directFallbackUsed||TaskState.poiId==null||TaskState.poiId.isEmpty())return false;
        directFallbackUsed=true;
        boolean ok=AmapLauncher.openPoi(this,TaskState.poiId,TaskState.hotelName,TaskState.lat,TaskState.lon);
        if(ok){
            searchMode=false;scrollCount=0;clickedInfo=false;
            BridgeService.sendDebug(TaskState.taskId,"关键词搜索未完成，已切换POI_ID直达兜底");
            h.postDelayed(scanRunnable,900);return true;
        }
        return false;
    }

    private boolean looksLikeHotelDetail(String raw,String expected){
        String n=norm(expected),r=norm(raw);if(n.isEmpty())return false;
        String key=n.length()>7?n.substring(0,7):n;if(!r.contains(key))return false;
        int markers=0;for(String m:new String[]{"评分","导航","电话","收藏","分享","路线","地址"})if(raw.contains(m))markers++;
        return markers>=3;
    }

    private void handleDetailPage(AccessibilityNodeInfo root,String raw){
        Result r=parse(raw,TaskState.hotelName);
        long elapsed=System.currentTimeMillis()-TaskState.startedAt;
        if(!r.open.isEmpty()){finish(r,"success","",raw);return;}

        if(!clickedInfo&&clickAny(root,new String[]{"酒店信息","酒店详情","设施服务","酒店设施","更多酒店信息","关于酒店"})){
            clickedInfo=true;BridgeService.sendDebug(TaskState.taskId,"已点击酒店信息/酒店详情入口");h.postDelayed(scanRunnable,750);return;
        }

        int maxScroll=directFallbackUsed?8:10;
        if(scrollCount<maxScroll){
            boolean moved=scrollForward(root);if(!moved)moved=swipeUp();
            scrollCount++;BridgeService.sendDebug(TaskState.taskId,moved?"详情页自动上滑，继续找开业/装修/房量/电话":"本轮无法滚动，稍后重试");
            h.postDelayed(scanRunnable,moved?620:550);return;
        }
        if(elapsed>26000||scrollCount>=maxScroll)finish(r,r.open.isEmpty()?"open_time_not_found":"partial","已执行搜索+详情快速扫描",raw);
        else h.postDelayed(scanRunnable,550);
    }

    private void finish(Result r,String status,String extra,String raw){
        String path=directFallbackUsed?"酒店名搜索→POI直达兜底":"酒店名搜索→匹配详情";
        String evidence="路径="+path+"；开业="+r.open+"；装修="+r.renovate+"；客房="+r.rooms+"；电话="+r.phone;
        if(extra!=null&&!extra.isEmpty())evidence=evidence+"；"+extra;
        BridgeService.sendResult(TaskState.taskId,TaskState.poiId,TaskState.hotelName,r.open,r.renovate,r.rooms,r.phone,r.nameCheck,status,evidence,raw);
        TaskState.clear();scrollCount=0;searchScrollCount=0;clickedInfo=false;retriedNetwork=false;searchUsed=false;
        searchMode=false;searchSubmitted=false;searchSubmitAttempts=0;directFallbackUsed=false;lastTask="";
    }

    private void collect(AccessibilityNodeInfo n,List<String> out,int d){
        if(n==null||d>45||out.size()>1800)return;
        CharSequence t=n.getText(),cd=n.getContentDescription();
        if(t!=null){String s=t.toString().trim();if(!s.isEmpty())out.add(s);}
        if(cd!=null){String s=cd.toString().trim();if(!s.isEmpty())out.add(s);}
        for(int i=0;i<n.getChildCount();i++)collect(n.getChild(i),out,d+1);
    }

    private boolean clickBestHotelResult(AccessibilityNodeInfo root,String expected){
        Candidate best=new Candidate();findBest(root,expected,best);
        if(best.node==null||best.score<65){BridgeService.sendDebug(TaskState.taskId,"搜索结果暂未发现目标酒店，最高匹配分="+best.score);return false;}
        AccessibilityNodeInfo c=best.node;
        for(int i=0;i<7&&c!=null;i++,c=c.getParent()){
            if(c.isClickable()&&c.performAction(AccessibilityNodeInfo.ACTION_CLICK)){
                BridgeService.sendDebug(TaskState.taskId,"搜索结果匹配分="+best.score+"｜"+best.text);return true;
            }
        }
        Rect b=new Rect();best.node.getBoundsInScreen(b);return !b.isEmpty()&&tap(b.centerX(),b.centerY());
    }
    private static class Candidate{AccessibilityNodeInfo node;int score=0;String text="";}
    private void findBest(AccessibilityNodeInfo n,String expected,Candidate best){
        if(n==null)return;
        CharSequence t=n.getText();
        if(t!=null){
            String text=t.toString().trim();String cls=String.valueOf(n.getClassName());
            if(!text.isEmpty()&&!cls.contains("EditText")){
                int score=nameScore(text,expected);Rect b=new Rect();n.getBoundsInScreen(b);DisplayMetrics dm=getResources().getDisplayMetrics();
                if(b.centerY()>0&&b.centerY()<dm.heightPixels*0.15)score-=35;
                if(score>best.score){best.score=score;best.node=n;best.text=text;}
            }
        }
        for(int i=0;i<n.getChildCount();i++)findBest(n.getChild(i),expected,best);
    }
    private int nameScore(String actual,String expected){
        String a=norm(actual),e=norm(expected);if(a.isEmpty()||e.isEmpty())return 0;
        if(a.equals(e))return 100;
        if(a.contains(e)||e.contains(a)){int min=Math.min(a.length(),e.length());if(min>=8)return 92;if(min>=5)return 80;}
        int common=0,max=Math.min(a.length(),e.length());while(common<max&&a.charAt(common)==e.charAt(common))common++;
        if(common>=10)return 85;if(common>=7)return 72;if(common>=5)return 60;return 0;
    }

    private boolean clickAny(AccessibilityNodeInfo n,String[] keys){
        if(n==null)return false;
        CharSequence t=n.getText(),cd=n.getContentDescription();String s=t!=null?t.toString():(cd!=null?cd.toString():"");
        if(!s.isEmpty())for(String k:keys)if(s.equals(k)||s.contains(k)){
            AccessibilityNodeInfo c=n;for(int i=0;i<6&&c!=null;i++,c=c.getParent())if(c.isClickable()&&c.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;
        }
        for(int i=0;i<n.getChildCount();i++)if(clickAny(n.getChild(i),keys))return true;return false;
    }
    private boolean scrollForward(AccessibilityNodeInfo n){
        if(n==null)return false;if(n.isScrollable()&&n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))return true;
        for(int i=0;i<n.getChildCount();i++)if(scrollForward(n.getChild(i)))return true;return false;
    }
    private boolean swipeUp(){
        DisplayMetrics dm=getResources().getDisplayMetrics();return swipe(dm.widthPixels*0.50f,dm.heightPixels*0.79f,dm.widthPixels*0.50f,dm.heightPixels*0.30f,330);
    }
    private boolean tapImeSearch(){
        DisplayMetrics dm=getResources().getDisplayMetrics();return tap(dm.widthPixels*0.90f,dm.heightPixels*0.90f);
    }
    private boolean tap(float x,float y){
        try{Path p=new Path();p.moveTo(x,y);GestureDescription g=new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p,0,90)).build();return dispatchGesture(g,null,null);}catch(Exception e){return false;}
    }
    private boolean swipe(float x1,float y1,float x2,float y2,long ms){
        try{Path p=new Path();p.moveTo(x1,y1);p.lineTo(x2,y2);GestureDescription g=new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p,0,ms)).build();return dispatchGesture(g,null,null);}catch(Exception e){BridgeService.sendDebug(TaskState.taskId,"手势异常："+e.getClass().getSimpleName());return false;}
    }

    private static class Result{String open="",renovate="",rooms="",phone="",nameCheck="";}
    private Result parse(String raw,String expected){
        Result r=new Result();String s=raw.replace('：',':');
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
        r.rooms=first(s,new String[]{"(?:客房数|客房数量|房间数|房型数量)\\s*[:：]?\\s*(\\d{1,4})\\s*间?","(\\d{1,4})\\s*间\\s*(?:客房|房间)"});
        r.phone=first(s,new String[]{"(?:酒店电话|联系电话|电话|前台电话)\\s*[:：]?\\s*((?:\\+?86[- ]?)?(?:0\\d{2,3}[- ]?)?\\d{7,8}|1[3-9]\\d{9})"});
        String a=norm(expected),b=norm(s);if(!a.isEmpty()){String key=a.length()>8?a.substring(0,8):a;r.nameCheck=b.contains(key)?"通过":"待核实";}return r;
    }
    private String first(String s,String[] ps){for(String p:ps){Matcher m=Pattern.compile(p,Pattern.CASE_INSENSITIVE).matcher(s);if(m.find())return m.group(1).replace(" ","").trim();}return "";}
    private String norm(String s){return s==null?"":s.replaceAll("[\\s·•・\\-—_（）()【】\\[\\]<>〈〉]","").replace("大酒店","").replace("酒店","").replace("宾馆","");}
    @Override public void onInterrupt(){}
}

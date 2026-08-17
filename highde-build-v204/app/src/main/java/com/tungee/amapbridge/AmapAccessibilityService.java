package com.tungee.amapbridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
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
    private int searchScrollCount=0;
    private boolean clickedInfo=false;
    private boolean retriedNetwork=false;
    private boolean searchUsed=false;
    private boolean searchMode=false;
    private String lastTask="";

    @Override protected void onServiceConnected(){
        super.onServiceConnected(); instance=this;
        BridgeService.sendDebug(TaskState.taskId,"无障碍服务已连接");
    }
    @Override public void onDestroy(){ if(instance==this)instance=null; super.onDestroy(); }
    public static boolean isAlive(){ return instance!=null; }
    public static void kick(){
        AmapAccessibilityService s=instance;
        if(s!=null){s.h.removeCallbacks(s.scanRunnable);s.h.postDelayed(s.scanRunnable,450);}
    }
    public static void markSearchMode(){
        AmapAccessibilityService s=instance;
        if(s!=null){s.searchMode=true;s.searchUsed=true;s.scrollCount=0;s.searchScrollCount=0;s.clickedInfo=false;}
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        if(!TaskState.active)return;
        if(event.getPackageName()==null||!"com.autonavi.minimap".contentEquals(event.getPackageName()))return;
        long now=System.currentTimeMillis();if(now-lastRun<350)return;lastRun=now;
        h.removeCallbacks(scanRunnable);h.postDelayed(scanRunnable,260);
    }

    private final Runnable scanRunnable=this::scan;

    private void resetForTask(){
        String id=TaskState.taskId==null?"":TaskState.taskId;
        if(!id.equals(lastTask)){
            lastTask=id;scrollCount=0;searchScrollCount=0;clickedInfo=false;retriedNetwork=false;searchUsed=false;searchMode=false;
        }
    }

    private void scan(){
        if(!TaskState.active)return;
        resetForTask();
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null){BridgeService.sendDebug(TaskState.taskId,"高德窗口节点为空，继续等待");h.postDelayed(scanRunnable,700);return;}

        ArrayList<String> texts=new ArrayList<>();collect(root,texts,0);
        String raw=String.join(" | ",texts);if(raw.length()>26000)raw=raw.substring(0,26000);
        BridgeService.sendDebug(TaskState.taskId,(searchMode?"搜索页":"详情页")+"读取文字 "+texts.size()+" 项｜滑动="+scrollCount+"｜搜索滑动="+searchScrollCount);

        if(handleNetworkError(root,raw))return;
        if(searchMode){handleSearchPage(root,raw);return;}
        handleDetailPage(root,raw);
    }

    private boolean handleNetworkError(AccessibilityNodeInfo root,String raw){
        if(raw.contains("网络好像开小差")||raw.contains("网络开小差")||raw.contains("加载失败")){
            if(!retriedNetwork&&clickAny(root,new String[]{"刷新重试","重新加载","重试"})){
                retriedNetwork=true;BridgeService.sendDebug(TaskState.taskId,"检测到高德网络异常，已点击刷新重试");h.postDelayed(scanRunnable,2400);return true;
            }
            long elapsed=System.currentTimeMillis()-TaskState.startedAt;
            if(elapsed>6500){Result r=parse(raw,TaskState.hotelName);finish(r,"amap_network_error","高德页面提示网络异常",raw);return true;}
        }
        return false;
    }

    private void handleSearchPage(AccessibilityNodeInfo root,String raw){
        if(looksLikeHotelDetail(raw,TaskState.hotelName)){
            searchMode=false;scrollCount=0;clickedInfo=false;
            BridgeService.sendDebug(TaskState.taskId,"关键词搜索已直接进入酒店详情，无需点击结果");
            h.postDelayed(scanRunnable,350);return;
        }
        if(clickBestHotelResult(root,TaskState.hotelName)){
            searchMode=false;scrollCount=0;clickedInfo=false;
            BridgeService.sendDebug(TaskState.taskId,"关键词搜索已匹配并点击酒店结果，进入详情");
            h.postDelayed(scanRunnable,1100);return;
        }
        if(searchScrollCount<4){
            boolean moved=scrollForward(root);if(!moved)moved=swipeUp();
            searchScrollCount++;BridgeService.sendDebug(TaskState.taskId,moved?"搜索结果未命中，继续向下找匹配酒店":"搜索结果页无法滚动，继续等待");
            h.postDelayed(scanRunnable,800);return;
        }
        Result r=parse(raw,TaskState.hotelName);finish(r,"search_no_match","关键词搜索未找到匹配酒店",raw);
    }

    private boolean looksLikeHotelDetail(String raw,String expected){
        String n=norm(expected),r=norm(raw);if(n.isEmpty())return false;
        String key=n.length()>7?n.substring(0,7):n;
        if(!r.contains(key))return false;
        int markers=0;
        for(String m:new String[]{"评分","导航","电话","收藏","分享","路线","地址"})if(raw.contains(m))markers++;
        return markers>=3;
    }

    private void handleDetailPage(AccessibilityNodeInfo root,String raw){
        Result r=parse(raw,TaskState.hotelName);
        long elapsed=System.currentTimeMillis()-TaskState.startedAt;
        if(!r.open.isEmpty()){finish(r,"success","",raw);return;}

        if(!clickedInfo&&clickAny(root,new String[]{"酒店信息","酒店详情","设施服务","酒店设施","更多酒店信息","关于酒店"})){
            clickedInfo=true;BridgeService.sendDebug(TaskState.taskId,"已点击酒店信息/酒店详情入口");h.postDelayed(scanRunnable,900);return;
        }

        if(!searchUsed&&scrollCount>=3){
            boolean ok=AmapLauncher.openSearch(this,TaskState.hotelName,TaskState.lat,TaskState.lon);
            if(ok){
                searchUsed=true;searchMode=true;searchScrollCount=0;scrollCount=0;clickedInfo=false;
                BridgeService.sendDebug(TaskState.taskId,"POI详情未发现开业线索，已自动切换：酒店名+经纬度范围搜索");
                h.postDelayed(scanRunnable,1000);return;
            }
        }

        int maxScroll=searchUsed?11:3;
        if(scrollCount<maxScroll){
            boolean moved=scrollForward(root);if(!moved)moved=swipeUp();
            scrollCount++;BridgeService.sendDebug(TaskState.taskId,moved?"已执行上滑，继续找开业/装修/房量/电话":"本轮无法滚动，稍后重试");
            h.postDelayed(scanRunnable,moved?780:650);return;
        }
        if(elapsed>25000||scrollCount>=maxScroll)finish(r,r.open.isEmpty()?"open_time_not_found":"partial","已执行POI直达+关键词搜索",raw);
        else h.postDelayed(scanRunnable,650);
    }

    private void finish(Result r,String status,String extra,String raw){
        String path=searchUsed?"POI直达→关键词搜索":"POI直达";
        String evidence="路径="+path+"；开业="+r.open+"；装修="+r.renovate+"；客房="+r.rooms+"；电话="+r.phone;
        if(extra!=null&&!extra.isEmpty())evidence=evidence+"；"+extra;
        BridgeService.sendResult(TaskState.taskId,TaskState.poiId,TaskState.hotelName,r.open,r.renovate,r.rooms,r.phone,r.nameCheck,status,evidence,raw);
        TaskState.clear();scrollCount=0;searchScrollCount=0;clickedInfo=false;retriedNetwork=false;searchUsed=false;searchMode=false;lastTask="";
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
        if(best.node==null||best.score<65){BridgeService.sendDebug(TaskState.taskId,"搜索页暂未发现足够匹配的酒店结果，最高分="+best.score);return false;}
        AccessibilityNodeInfo c=best.node;
        for(int i=0;i<7&&c!=null;i++,c=c.getParent()){
            if(c.isClickable()&&c.performAction(AccessibilityNodeInfo.ACTION_CLICK)){
                BridgeService.sendDebug(TaskState.taskId,"搜索结果匹配分="+best.score+"｜"+best.text);return true;
            }
        }
        return false;
    }
    private static class Candidate{AccessibilityNodeInfo node;int score=0;String text="";}
    private void findBest(AccessibilityNodeInfo n,String expected,Candidate best){
        if(n==null)return;
        CharSequence t=n.getText();
        if(t!=null){
            String text=t.toString().trim();String cls=String.valueOf(n.getClassName());
            if(!text.isEmpty()&&!cls.contains("EditText")){
                int score=nameScore(text,expected);
                Rect b=new Rect();n.getBoundsInScreen(b);DisplayMetrics dm=getResources().getDisplayMetrics();
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
        try{
            DisplayMetrics dm=getResources().getDisplayMetrics();float x=dm.widthPixels*0.50f,y1=dm.heightPixels*0.79f,y2=dm.heightPixels*0.30f;
            Path p=new Path();p.moveTo(x,y1);p.lineTo(x,y2);
            GestureDescription gesture=new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p,0,390)).build();
            return dispatchGesture(gesture,null,null);
        }catch(Exception e){BridgeService.sendDebug(TaskState.taskId,"手势上滑异常："+e.getClass().getSimpleName());return false;}
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

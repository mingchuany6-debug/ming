package com.tungee.amapbridge;

import android.app.*;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public class BridgeService extends Service {
    private static volatile BridgeService instance;
    private static volatile boolean connected=false;
    private volatile boolean running=false;
    private Socket socket; private BufferedReader reader; private BufferedWriter writer;
    private String host; private int port;
    private final Handler mainHandler=new Handler(Looper.getMainLooper());

    public static boolean isConnected(){ return connected; }
    @Override public void onCreate(){ super.onCreate(); instance=this; createChannel(); }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null){ host=intent.getStringExtra("host"); port=intent.getIntExtra("port",8765); }
        startForeground(1001, notification("正在连接电脑…"));
        if(!running){running=true; new Thread(this::loop,"bridge-net").start();}
        return START_STICKY;
    }
    @Override public void onDestroy(){running=false; connected=false; close(); instance=null; super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}

    private void createChannel(){
        if(android.os.Build.VERSION.SDK_INT>=26){
            NotificationChannel ch=new NotificationChannel("bridge","高德采集连接",NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }
    private Notification notification(String txt){
        return new Notification.Builder(this, android.os.Build.VERSION.SDK_INT>=26?"bridge":"")
                .setContentTitle("高德酒店采集助手").setContentText(txt)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation).setOngoing(true).build();
    }
    private void loop(){
        int wait=1500;
        while(running){
            try{
                if(host==null||host.trim().isEmpty()){Thread.sleep(1500);continue;}
                socket=new Socket(host,port); socket.setTcpNoDelay(true); connected=true;
                reader=new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                writer=new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8));
                send(new JSONObject().put("type","hello").put("device",android.os.Build.MODEL).put("version","2.2.0")
                        .put("accessibility",AmapAccessibilityService.isAlive()));
                ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(1001,notification("已连接电脑，结果卡片直读模式待命"));
                wait=1500; String line;
                while(running && (line=reader.readLine())!=null) handle(line);
            }catch(Exception e){
                try{Thread.sleep(wait);}catch(Exception ignored){}
                wait=Math.min(10000,wait+1200);
            } finally { connected=false; close(); }
        }
    }
    private void handle(String line){
        try{
            JSONObject j=new JSONObject(line); String cmd=j.optString("cmd");
            if("ping".equals(cmd)){send(new JSONObject().put("type","pong").put("accessibility",AmapAccessibilityService.isAlive()));return;}
            if("status".equals(cmd)){send(new JSONObject().put("type","status").put("accessibility",AmapAccessibilityService.isAlive()).put("task_active",TaskState.active));return;}
            if("task".equals(cmd)){
                String id=j.optString("task_id"), poi=j.optString("poi_id"), name=j.optString("name"), lat=j.optString("lat"), lon=j.optString("lon"), address=j.optString("address");
                if(!AmapAccessibilityService.isAlive()){
                    sendDebug(id,"无障碍服务未开启/未连接，任务不执行");
                    sendResult(id,poi,name,"","","","","","accessibility_off","请在手机设置中开启“高德酒店采集助手”无障碍服务","");
                    return;
                }
                TaskState.set(id,poi,name,lat,lon,address);
                sendDebug(id,"收到任务：优先搜索酒店并直接读取结果卡片中的开业年份/房量｜"+name+"｜"+poi);

                boolean ok=AmapLauncher.openSearch(this,name,lat,lon);
                if(ok){
                    AmapAccessibilityService.markSearchMode();
                    send(new JSONObject().put("type","task_opened").put("task_id",id).put("ok",true).put("mode","search_card"));
                }else{
                    sendDebug(id,"高德关键词搜索调起失败，改用POI_ID直达");
                    boolean pok=AmapLauncher.openPoi(this,poi,name,lat,lon);
                    send(new JSONObject().put("type","task_opened").put("task_id",id).put("ok",pok).put("mode","poi"));
                    if(!pok){
                        sendResult(id,poi,name,"","","","","","open_failed","关键词搜索和POI直达都无法调起高德地图","");
                        TaskState.clear();return;
                    }
                }
                mainHandler.postDelayed(AmapAccessibilityService::kick,450);
                mainHandler.postDelayed(AmapAccessibilityService::kick,1050);
                mainHandler.postDelayed(AmapAccessibilityService::kick,2100);
            } else if("stop".equals(cmd)){ TaskState.clear(); }
        }catch(Exception e){ sendDebug(TaskState.taskId,"任务处理异常："+e.getClass().getSimpleName()+" "+String.valueOf(e.getMessage())); }
    }
    private synchronized void send(JSONObject j){
        try{ if(writer!=null){writer.write(j.toString());writer.write("\n");writer.flush();} }catch(Exception ignored){}
    }
    public static void sendResult(String taskId,String poiId,String hotelName,String open,String renovate,String rooms,String phone,String nameCheck,String status,String evidence,String raw){
        BridgeService s=instance; if(s==null)return;
        try{
            s.send(new JSONObject().put("type","result").put("task_id",taskId).put("poi_id",poiId).put("name",hotelName)
                    .put("open_time",open).put("renovate_time",renovate).put("rooms",rooms).put("phone",phone)
                    .put("name_check",nameCheck).put("status",status).put("evidence",evidence).put("raw_text",raw));
        }catch(Exception ignored){}
    }
    public static void sendDebug(String taskId,String msg){
        BridgeService s=instance; if(s==null)return;
        try{s.send(new JSONObject().put("type","debug").put("task_id",taskId==null?"":taskId).put("message",msg));}catch(Exception ignored){}
    }
    private void close(){
        try{if(reader!=null)reader.close();}catch(Exception ignored){}
        try{if(writer!=null)writer.close();}catch(Exception ignored){}
        try{if(socket!=null)socket.close();}catch(Exception ignored){}
        reader=null;writer=null;socket=null;
    }
}

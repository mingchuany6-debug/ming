package com.tungee.amapbridge;

import android.app.*;
import android.content.Intent;
import android.os.IBinder;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public class BridgeService extends Service {
    private static volatile BridgeService instance;
    private volatile boolean running=false;
    private Socket socket; private BufferedReader reader; private BufferedWriter writer;
    private String host; private int port;

    @Override public void onCreate(){ super.onCreate(); instance=this; createChannel(); }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        host=intent.getStringExtra("host"); port=intent.getIntExtra("port",8765);
        startForeground(1001, notification("正在连接电脑…"));
        if(!running){running=true; new Thread(this::loop,"bridge-net").start();}
        return START_STICKY;
    }
    @Override public void onDestroy(){running=false; close(); instance=null; super.onDestroy();}
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
                socket=new Socket(host,port); socket.setTcpNoDelay(true);
                reader=new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                writer=new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8));
                send(new JSONObject().put("type","hello").put("device",android.os.Build.MODEL).put("version","2.0.4"));
                ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(1001,notification("已连接电脑，等待酒店任务"));
                wait=1500; String line;
                while(running && (line=reader.readLine())!=null) handle(line);
            }catch(Exception e){
                try{Thread.sleep(wait);}catch(Exception ignored){}
                wait=Math.min(10000,wait+1200);
            } finally { close(); }
        }
    }
    private void handle(String line){
        try{
            JSONObject j=new JSONObject(line); String cmd=j.optString("cmd");
            if("ping".equals(cmd)){send(new JSONObject().put("type","pong"));return;}
            if("task".equals(cmd)){
                String id=j.optString("task_id"), poi=j.optString("poi_id"), name=j.optString("name"), lat=j.optString("lat"), lon=j.optString("lon");
                TaskState.set(id,poi,name,lat,lon);
                boolean ok=AmapLauncher.openPoi(this,poi,name,lat,lon);
                send(new JSONObject().put("type","task_opened").put("task_id",id).put("ok",ok));
                if(!ok){
                    sendResult(id,poi,name,"","","","","","open_failed","无法调起高德地图","");
                    TaskState.clear();
                }
            } else if("stop".equals(cmd)){ TaskState.clear(); }
        }catch(Exception ignored){}
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
        try{s.send(new JSONObject().put("type","debug").put("task_id",taskId).put("message",msg));}catch(Exception ignored){}
    }
    private void close(){
        try{if(reader!=null)reader.close();}catch(Exception ignored){}
        try{if(writer!=null)writer.close();}catch(Exception ignored){}
        try{if(socket!=null)socket.close();}catch(Exception ignored){}
        reader=null; writer=null; socket=null;
    }
}

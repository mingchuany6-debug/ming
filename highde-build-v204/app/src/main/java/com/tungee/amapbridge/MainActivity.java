package com.tungee.amapbridge;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private EditText hostEdit, portEdit;
    private TextView status, health;
    private SharedPreferences prefs;
    @Override public void onCreate(Bundle b){super.onCreate(b);prefs=getSharedPreferences("bridge",MODE_PRIVATE);buildUi();}
    @Override protected void onResume(){super.onResume();refreshHealth();}
    private TextView label(String s,int size){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(Color.rgb(20,30,40));v.setPadding(0,12,0,8);return v;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextSize(17);b.setAllCaps(false);return b;}
    private boolean amapInstalled(){try{return getPackageManager().getLaunchIntentForPackage("com.autonavi.minimap")!=null;}catch(Exception e){return false;}}
    private boolean accessibilityEnabled(){try{String enabled=Settings.Secure.getString(getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);return enabled!=null&&enabled.toLowerCase().contains(getPackageName().toLowerCase())&&enabled.toLowerCase().contains("amapaccessibilityservicev23");}catch(Exception e){return AmapAccessibilityServiceV23.isAlive();}}
    private void refreshHealth(){if(health==null)return;boolean a=amapInstalled(),acc=accessibilityEnabled()||AmapAccessibilityServiceV23.isAlive(),net=BridgeService.isConnected(),ocr=Build.VERSION.SDK_INT>=30;String task=TaskState.active?"进行中："+TaskState.hotelName:"无";health.setText("高德App："+(a?"✓ 已安装":"✗ 未安装")+"\n无障碍："+(acc?"✓ 已开启":"✗ 未开启")+"\n屏幕OCR："+(ocr?"✓ 支持":"✗ Android版本过低")+"\n电脑连接："+(net?"✓ 已连接":"○ 未连接")+"\n当前任务："+task);health.setTextColor((a&&acc&&ocr)?Color.rgb(20,125,70):Color.rgb(190,60,35));}
    private void buildUi(){
        ScrollView sc=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(40,45,40,45);root.setBackgroundColor(Color.rgb(246,250,248));sc.addView(root);
        TextView title=label("高德酒店采集助手 2.3.0",27);title.setTextColor(Color.rgb(17,135,82));title.setGravity(Gravity.CENTER_HORIZONTAL);root.addView(title);
        TextView sub=label("屏幕OCR版｜只要高德画面能看到“开业时间”，就直接识别",15);sub.setGravity(Gravity.CENTER_HORIZONTAL);root.addView(sub);
        status=label("状态：未连接",17);status.setTextColor(Color.rgb(180,80,10));root.addView(status);health=label("正在自检…",16);health.setPadding(18,14,18,18);root.addView(health);
        Button check=button("① 手机环境自检");root.addView(check);check.setOnClickListener(v->{refreshHealth();if(!accessibilityEnabled())Toast.makeText(this,"请重新开启V2.3无障碍服务",Toast.LENGTH_LONG).show();});
        Button acc=button("② 开启无障碍 + 屏幕读取权限");root.addView(acc);acc.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        Button discover=button("③ 自动发现电脑");root.addView(discover);discover.setOnClickListener(v->{status.setText("状态：正在局域网发现电脑…");new Thread(()->{Discovery.Result r=Discovery.findPc(3200);runOnUiThread(()->{if(r!=null){hostEdit.setText(r.host);portEdit.setText(String.valueOf(r.port));status.setText("已发现电脑："+r.host+":"+r.port);}else status.setText("未发现电脑：请手填电脑端顶部IP");refreshHealth();});}).start();});
        root.addView(label("电脑IP",14));hostEdit=new EditText(this);hostEdit.setSingleLine(true);hostEdit.setText(prefs.getString("host",""));root.addView(hostEdit);
        root.addView(label("端口",14));portEdit=new EditText(this);portEdit.setSingleLine(true);portEdit.setInputType(InputType.TYPE_CLASS_NUMBER);portEdit.setText(String.valueOf(prefs.getInt("port",8765)));root.addView(portEdit);
        Button connect=button("④ 连接电脑并等待任务");root.addView(connect);connect.setOnClickListener(v->connect());
        Button testAmap=button("⑤ 检查/打开高德地图");root.addView(testAmap);testAmap.setOnClickListener(v->{Intent i=getPackageManager().getLaunchIntentForPackage("com.autonavi.minimap");if(i==null)Toast.makeText(this,"未检测到高德地图App",Toast.LENGTH_LONG).show();else startActivity(i);});
        Button stop=button("停止连接");root.addView(stop);stop.setOnClickListener(v->{stopService(new Intent(this,BridgeService.class));status.setText("状态：已停止");refreshHealth();});
        TextView note=label("V2.3变化：\n无障碍只负责搜索/点击/滑动；酒店字段改为直接截图当前高德画面并使用内置中文OCR识别。\n\n例如画面显示：\n开业时间 2022年09月01日  →  2022-09-01\n2023年开业  →  2023\n101间房  →  101\n\n这样不再要求高德把文字暴露给AccessibilityNodeInfo。\n\n注意：升级后请务必把旧无障碍权限关闭一次，再重新开启V2.3服务。",14);note.setTextColor(Color.DKGRAY);root.addView(note);setContentView(sc);refreshHealth();
    }
    private void connect(){String host=hostEdit.getText().toString().trim();int port;try{port=Integer.parseInt(portEdit.getText().toString().trim());}catch(Exception e){port=8765;}if(host.isEmpty()){Toast.makeText(this,"请先自动发现电脑或填写电脑IP",Toast.LENGTH_LONG).show();return;}if(!accessibilityEnabled()){Toast.makeText(this,"请先重新开启V2.3无障碍权限",Toast.LENGTH_LONG).show();refreshHealth();return;}if(Build.VERSION.SDK_INT<30){Toast.makeText(this,"V2.3屏幕OCR要求Android 11及以上",Toast.LENGTH_LONG).show();return;}if(!amapInstalled()){Toast.makeText(this,"没有检测到高德地图App",Toast.LENGTH_LONG).show();return;}prefs.edit().putString("host",host).putInt("port",port).apply();Intent s=new Intent(this,BridgeService.class);s.putExtra("host",host);s.putExtra("port",port);startForegroundService(s);status.setText("状态：正在连接 "+host+":"+port);Toast.makeText(this,"V2.3屏幕OCR服务已启动",Toast.LENGTH_LONG).show();new android.os.Handler().postDelayed(this::refreshHealth,1800);}
}

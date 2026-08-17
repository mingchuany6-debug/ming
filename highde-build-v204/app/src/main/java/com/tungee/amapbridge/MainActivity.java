package com.tungee.amapbridge;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
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
    private boolean accessibilityEnabled(){try{String enabled=Settings.Secure.getString(getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);return enabled!=null&&enabled.toLowerCase().contains(getPackageName().toLowerCase())&&enabled.toLowerCase().contains("amapaccessibilityservice");}catch(Exception e){return AmapAccessibilityService.isAlive();}}
    private void refreshHealth(){if(health==null)return;boolean a=amapInstalled(),acc=accessibilityEnabled()||AmapAccessibilityService.isAlive(),net=BridgeService.isConnected();String task=TaskState.active?"进行中："+TaskState.hotelName:"无";health.setText("高德App："+(a?"✓ 已安装":"✗ 未安装")+"\n无障碍："+(acc?"✓ 已开启":"✗ 未开启")+"\n电脑连接："+(net?"✓ 已连接":"○ 未连接")+"\n当前任务："+task);health.setTextColor((a&&acc)?Color.rgb(20,125,70):Color.rgb(190,60,35));}
    private void buildUi(){
        ScrollView sc=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(40,45,40,45);root.setBackgroundColor(Color.rgb(246,250,248));sc.addView(root);
        TextView title=label("高德酒店采集助手 2.1.1",27);title.setTextColor(Color.rgb(17,135,82));title.setGravity(Gravity.CENTER_HORIZONTAL);root.addView(title);
        TextView sub=label("主动搜索提交版｜酒店名搜索 → 精确匹配 → 详情提取 → 自动回填",15);sub.setGravity(Gravity.CENTER_HORIZONTAL);root.addView(sub);
        status=label("状态：未连接",17);status.setTextColor(Color.rgb(180,80,10));root.addView(status);health=label("正在自检…",16);health.setPadding(18,14,18,18);root.addView(health);
        Button check=button("① 手机环境自检");root.addView(check);check.setOnClickListener(v->{refreshHealth();if(!accessibilityEnabled())Toast.makeText(this,"无障碍未开启：请点击下一步开启",Toast.LENGTH_LONG).show();});
        Button acc=button("② 开启无障碍读取权限");root.addView(acc);acc.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        Button discover=button("③ 自动发现电脑");root.addView(discover);discover.setOnClickListener(v->{status.setText("状态：正在局域网发现电脑…");new Thread(()->{Discovery.Result r=Discovery.findPc(3200);runOnUiThread(()->{if(r!=null){hostEdit.setText(r.host);portEdit.setText(String.valueOf(r.port));status.setText("已发现电脑："+r.host+":"+r.port);}else status.setText("未发现电脑：可在下面手填电脑端顶部显示的IP");refreshHealth();});}).start();});
        root.addView(label("电脑IP（发现失败可手填，填电脑软件顶部的IP）",14));hostEdit=new EditText(this);hostEdit.setSingleLine(true);hostEdit.setText(prefs.getString("host",""));root.addView(hostEdit);
        root.addView(label("端口",14));portEdit=new EditText(this);portEdit.setSingleLine(true);portEdit.setInputType(InputType.TYPE_CLASS_NUMBER);portEdit.setText(String.valueOf(prefs.getInt("port",8765)));root.addView(portEdit);
        Button connect=button("④ 连接电脑并等待任务");root.addView(connect);connect.setOnClickListener(v->connect());
        Button testAmap=button("⑤ 检查/打开高德地图");root.addView(testAmap);testAmap.setOnClickListener(v->{Intent i=getPackageManager().getLaunchIntentForPackage("com.autonavi.minimap");if(i==null)Toast.makeText(this,"未检测到高德地图App",Toast.LENGTH_LONG).show();else startActivity(i);});
        Button stop=button("停止连接");root.addView(stop);stop.setOnClickListener(v->{stopService(new Intent(this,BridgeService.class));status.setText("状态：已停止");refreshHealth();});
        TextView note=label("工作方式：\n电脑逐家下发酒店 → 手机主动打开高德搜索 → 自动聚焦搜索框并提交酒店名 → 自动点击最匹配酒店 → 找开业/装修/房量/电话 → 立即回传电脑。搜索失败才使用POI_ID直达兜底。\n\n必须满足：\n✓ 安卓真机\n✓ 手机与电脑同一Wi-Fi\n✓ 无障碍已开启\n✓ 高德App本身网络正常。",14);note.setTextColor(Color.DKGRAY);root.addView(note);setContentView(sc);refreshHealth();
    }
    private void connect(){String host=hostEdit.getText().toString().trim();int port;try{port=Integer.parseInt(portEdit.getText().toString().trim());}catch(Exception e){port=8765;}if(host.isEmpty()){Toast.makeText(this,"请先自动发现电脑或填写电脑IP",Toast.LENGTH_LONG).show();return;}if(!accessibilityEnabled()){Toast.makeText(this,"无障碍服务还没开启，请先完成第②步",Toast.LENGTH_LONG).show();refreshHealth();return;}if(!amapInstalled()){Toast.makeText(this,"没有检测到高德地图App",Toast.LENGTH_LONG).show();return;}prefs.edit().putString("host",host).putInt("port",port).apply();Intent s=new Intent(this,BridgeService.class);s.putExtra("host",host);s.putExtra("port",port);startForegroundService(s);status.setText("状态：正在连接 "+host+":"+port);Toast.makeText(this,"主动搜索服务已启动；看到“已连接电脑”后即可从电脑批量开始",Toast.LENGTH_LONG).show();new android.os.Handler().postDelayed(this::refreshHealth,1800);}
}

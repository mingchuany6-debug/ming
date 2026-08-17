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
    private TextView status;
    private SharedPreferences prefs;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("bridge", MODE_PRIVATE);
        buildUi();
    }

    private TextView label(String s, int size) {
        TextView v = new TextView(this);
        v.setText(s); v.setTextSize(size); v.setTextColor(Color.rgb(20,30,40));
        v.setPadding(0,12,0,8); return v;
    }
    private Button button(String s) {
        Button b = new Button(this); b.setText(s); b.setTextSize(17); b.setAllCaps(false); return b;
    }
    private void buildUi() {
        ScrollView sc = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(40,45,40,45);
        root.setBackgroundColor(Color.rgb(246,250,248)); sc.addView(root);

        TextView title = label("高德酒店采集助手 2.0.4", 27);
        title.setTextColor(Color.rgb(17,135,82)); title.setGravity(Gravity.CENTER_HORIZONTAL); root.addView(title);
        TextView sub = label("手机 ↔ Wi-Fi ↔ 电脑｜无需USB / ADB", 15);
        sub.setGravity(Gravity.CENTER_HORIZONTAL); root.addView(sub);
        status = label("状态：未连接", 17); status.setTextColor(Color.rgb(180,80,10)); root.addView(status);

        Button discover = button("① 自动发现电脑"); root.addView(discover);
        discover.setOnClickListener(v -> {
            status.setText("状态：正在局域网发现电脑…");
            new Thread(() -> {
                Discovery.Result r = Discovery.findPc(2600);
                runOnUiThread(() -> {
                    if (r != null) {
                        hostEdit.setText(r.host); portEdit.setText(String.valueOf(r.port));
                        status.setText("已发现电脑：" + r.host + ":" + r.port);
                    } else status.setText("未发现电脑：请确认手机与电脑在同一Wi-Fi，电脑端已启动连接服务");
                });
            }).start();
        });

        root.addView(label("电脑IP（自动发现失败时可手填）", 14));
        hostEdit = new EditText(this); hostEdit.setSingleLine(true); hostEdit.setText(prefs.getString("host", "")); root.addView(hostEdit);
        root.addView(label("端口", 14));
        portEdit = new EditText(this); portEdit.setSingleLine(true); portEdit.setInputType(InputType.TYPE_CLASS_NUMBER); portEdit.setText(String.valueOf(prefs.getInt("port", 8765))); root.addView(portEdit);

        Button acc = button("② 开启无障碍读取权限"); root.addView(acc);
        acc.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        Button testAmap = button("③ 检查/打开高德地图"); root.addView(testAmap);
        testAmap.setOnClickListener(v -> {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.autonavi.minimap");
            if (i == null) Toast.makeText(this, "未检测到高德地图App", Toast.LENGTH_LONG).show();
            else startActivity(i);
        });

        Button connect = button("④ 连接电脑并等待任务"); root.addView(connect);
        connect.setOnClickListener(v -> connect());

        Button stop = button("停止连接"); root.addView(stop);
        stop.setOnClickListener(v -> { stopService(new Intent(this, BridgeService.class)); status.setText("状态：已停止"); });

        TextView note = label("使用顺序：\n1. 电脑端先启动；\n2. 手机和电脑在同一Wi-Fi；\n3. 第一次开启本App无障碍权限；\n4. 自动发现电脑；\n5. 点连接电脑并等待任务；\n6. 电脑导入高德Excel后开始补全。", 14);
        note.setTextColor(Color.DKGRAY); root.addView(note);
        setContentView(sc);
    }

    private void connect() {
        String host = hostEdit.getText().toString().trim();
        int port;
        try { port = Integer.parseInt(portEdit.getText().toString().trim()); } catch(Exception e){ port=8765; }
        if (host.isEmpty()) { Toast.makeText(this,"请先自动发现电脑或填写电脑IP",Toast.LENGTH_LONG).show(); return; }
        prefs.edit().putString("host",host).putInt("port",port).apply();
        Intent s = new Intent(this, BridgeService.class); s.putExtra("host",host); s.putExtra("port",port);
        startForegroundService(s);
        status.setText("状态：正在连接 " + host + ":" + port);
        Toast.makeText(this,"连接服务已启动，可切换到高德地图",Toast.LENGTH_LONG).show();
    }
}

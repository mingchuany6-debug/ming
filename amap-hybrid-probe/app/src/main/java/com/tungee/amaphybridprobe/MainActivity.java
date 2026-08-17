package com.tungee.amaphybridprobe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.*;

import com.amap.api.services.core.AMapException;
import com.amap.api.services.core.ServiceSettings;
import com.amap.api.services.core.PoiItemV2;
import com.amap.api.services.poisearch.PoiSearchV2;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class MainActivity extends Activity {
    private EditText androidKey, webKey, poiIds;
    private CheckBox consent;
    private TextView status, output;
    private Button testBtn;
    private volatile String lastText="";

    private static final String PKG="com.tungee.amaphybridprobe";
    private static final String SHA1="8B:08:D1:78:83:1D:AD:D1:2A:05:5D:06:06:15:BE:66:73:34:7B:F6";

    @Override public void onCreate(Bundle b){ super.onCreate(b); buildUi(); }

    private TextView label(String s,int sp){
        TextView v=new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(Color.rgb(35,45,55)); v.setPadding(4,10,4,6); return v;
    }
    private EditText edit(String hint){ EditText e=new EditText(this);e.setHint(hint);e.setSingleLine(true);e.setTextSize(15);return e; }

    private void buildUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(28,28,28,28);root.setBackgroundColor(Color.rgb(247,250,248));
        ScrollView sc=new ScrollView(this);sc.addView(root);setContentView(sc);
        TextView title=label("高德 Web + Android 双Key数据探针 1.0",24);title.setTextColor(Color.rgb(18,132,79));title.setGravity(Gravity.CENTER);root.addView(title);
        root.addView(label("同一个 POI_ID 同时跑 Web V5 Detail 与 Android Search SDK ShowFields.ALL，专门验证 Android 通道是否有 Web 没有的酒店字段。",14));
        TextView bind=label("Android Key 绑定信息（固定签名）\nPackageName："+PKG+"\nSHA1："+SHA1,14);bind.setTextColor(Color.rgb(0,90,150));root.addView(bind);
        root.addView(label("Android平台SDK Key",14));androidKey=edit("粘贴你新建的 Android Key");root.addView(androidKey);
        root.addView(label("之前的 Web服务 Key",14));webKey=edit("粘贴原高德 Web Key");root.addView(webKey);
        root.addView(label("POI_ID（可一行一个，最多10个）",14));
        poiIds=new EditText(this);poiIds.setHint("例如：B0FFHC...\nB021F0...");poiIds.setMinLines(4);poiIds.setGravity(Gravity.TOP);poiIds.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);root.addView(poiIds);
        consent=new CheckBox(this);consent.setText("我已阅读并同意高德开放平台相关服务协议和隐私政策，并仅用于本次测试");consent.setTextSize(14);root.addView(consent);
        testBtn=new Button(this);testBtn.setText("开始双通道测试");testBtn.setTextSize(17);root.addView(testBtn);testBtn.setOnClickListener(v->startTest());
        Button copy=new Button(this);copy.setText("复制全部测试结果");root.addView(copy);copy.setOnClickListener(v->{ ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);cm.setPrimaryClip(ClipData.newPlainText("AMap Probe",lastText));Toast.makeText(this,"已复制",Toast.LENGTH_SHORT).show(); });
        status=label("状态：等待输入 Key 和 POI_ID",15);status.setTextColor(Color.rgb(170,95,0));root.addView(status);
        output=label("",13);output.setTextIsSelectable(true);output.setBackgroundColor(Color.WHITE);output.setPadding(16,16,16,24);root.addView(output);
    }

    private void startTest(){
        String ak=androidKey.getText().toString().trim(), wk=webKey.getText().toString().trim(), raw=poiIds.getText().toString().trim();
        if(ak.isEmpty()||wk.isEmpty()||raw.isEmpty()){Toast.makeText(this,"Android Key、Web Key、POI_ID 都要填",Toast.LENGTH_LONG).show();return;}
        if(!consent.isChecked()){Toast.makeText(this,"请先勾选协议/隐私确认",Toast.LENGTH_LONG).show();return;}
        List<String> ids=new ArrayList<>();for(String x:raw.split("[\\s,;，；]+")){x=x.trim();if(!x.isEmpty()&&!ids.contains(x))ids.add(x);if(ids.size()>=10)break;}
        if(ids.isEmpty())return;
        testBtn.setEnabled(false);status.setText("状态：测试中 0/"+ids.size());output.setText("");
        new Thread(()->runTests(ak,wk,ids),"probe").start();
    }

    private void runTests(String ak,String wk,List<String> ids){
        StringBuilder all=new StringBuilder();
        try{
            ServiceSettings.updatePrivacyShow(this,true,true);
            ServiceSettings.updatePrivacyAgree(this,true);
            ServiceSettings.getInstance().setApiKey(ak);
            ServiceSettings.getInstance().setProtocol(ServiceSettings.HTTPS);
            for(int i=0;i<ids.size();i++){
                final int n=i+1;String id=ids.get(i);
                runOnUiThread(()->status.setText("状态：测试中 "+n+"/"+ids.size()+"｜"+id));
                JSONObject web=fetchWeb(wk,id); JSONObject android=fetchAndroid(id);
                String section=buildSection(id,web,android); all.append(section).append("\n\n");
                final String shown=all.toString();runOnUiThread(()->output.setText(shown));
            }
        }catch(Exception e){all.append("\n[总异常] ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());}
        lastText=all.toString();runOnUiThread(()->{output.setText(lastText);status.setText("状态：测试完成，请重点看【疑似额外字段】和 Android SDK 原始展开结果");testBtn.setEnabled(true);});
    }

    private JSONObject fetchWeb(String key,String id){
        JSONObject wrap=new JSONObject();
        try{
            String fields="business,photos,navi,indoor,children";
            String u="https://restapi.amap.com/v5/place/detail?key="+URLEncoder.encode(key,"UTF-8")+"&id="+URLEncoder.encode(id,"UTF-8")+"&show_fields="+fields;
            HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection();c.setConnectTimeout(12000);c.setReadTimeout(18000);c.setRequestMethod("GET");c.setRequestProperty("User-Agent","AMapHybridProbe/1.0");
            int code=c.getResponseCode();BufferedReader br=new BufferedReader(new InputStreamReader(code>=200&&code<400?c.getInputStream():c.getErrorStream(),StandardCharsets.UTF_8));
            StringBuilder sb=new StringBuilder();String line;while((line=br.readLine())!=null)sb.append(line);br.close();wrap.put("http_code",code);wrap.put("body",new JSONObject(sb.toString()));
        }catch(Exception e){try{wrap.put("error",e.getClass().getSimpleName()+": "+e.getMessage());}catch(Exception ignored){}}
        return wrap;
    }

    private JSONObject fetchAndroid(String id){
        JSONObject out=new JSONObject();
        try{
            PoiSearchV2.Query q=new PoiSearchV2.Query("","","");q.setShowFields(new PoiSearchV2.ShowFields(PoiSearchV2.ShowFields.ALL));
            PoiSearchV2 ps=new PoiSearchV2(this,q);PoiItemV2 item=ps.searchPOIId(id);
            if(item==null){out.put("error","searchPOIId returned null");return out;}
            out.put("sdk_class",item.getClass().getName());out.put("data",dumpObject(item,0,new IdentityHashMap<>()));
        }catch(AMapException e){try{out.put("amap_error_code",e.getErrorCode());out.put("amap_error",e.getErrorMessage());}catch(Exception ignored){}}
        catch(Exception e){try{out.put("error",e.getClass().getSimpleName()+": "+e.getMessage());}catch(Exception ignored){}}
        return out;
    }

    private Object dumpObject(Object obj,int depth,IdentityHashMap<Object,Boolean> seen){
        if(obj==null)return JSONObject.NULL;if(isSimple(obj))return String.valueOf(obj);if(depth>3)return String.valueOf(obj);if(seen.containsKey(obj))return "<cycle>";seen.put(obj,true);
        try{
            if(obj instanceof Collection){JSONArray a=new JSONArray();int i=0;for(Object x:(Collection<?>)obj){if(i++>=10)break;a.put(dumpObject(x,depth+1,seen));}return a;}
            if(obj.getClass().isArray()){JSONArray a=new JSONArray();int n=Math.min(Array.getLength(obj),10);for(int i=0;i<n;i++)a.put(dumpObject(Array.get(obj,i),depth+1,seen));return a;}
            JSONObject j=new JSONObject();Method[] ms=obj.getClass().getMethods();Arrays.sort(ms,Comparator.comparing(Method::getName));
            for(Method m:ms){String name=m.getName();if(m.getParameterCount()!=0||name.equals("getClass")||name.equals("hashCode")||name.equals("toString"))continue;if(!(name.startsWith("get")||name.startsWith("is")))continue;try{Object v=m.invoke(obj);if(v!=null)j.put(name,dumpObject(v,depth+1,seen));}catch(Throwable ignored){}}
            if(j.length()==0)return String.valueOf(obj);return j;
        }finally{seen.remove(obj);}
    }
    private boolean isSimple(Object o){return o instanceof CharSequence||o instanceof Number||o instanceof Boolean||o instanceof Character||o.getClass().isEnum();}

    private String buildSection(String id,JSONObject web,JSONObject android){
        StringBuilder s=new StringBuilder();s.append("==============================\nPOI_ID: ").append(id).append("\n==============================\n\n【疑似额外字段/重点扫描】\n");
        List<String> hits=new ArrayList<>();scan(android,"ANDROID",hits);scan(web,"WEB",hits);if(hits.isEmpty())s.append("未发现包含 open/开业/room/客房/renovate/装修/checkin/year 等关键词的字段。\n");else for(String h:hits)s.append(h).append("\n");
        s.append("\n【Android SDK · ShowFields.ALL】\n").append(pretty(android));s.append("\n\n【Web API · V5 Detail】\n").append(pretty(web));return s.toString();
    }
    private void scan(Object o,String path,List<String> hits){
        if(hits.size()>120||o==null)return;try{if(o instanceof JSONObject){JSONObject j=(JSONObject)o;Iterator<String> it=j.keys();while(it.hasNext()){String k=it.next();Object v=j.opt(k);String p=path+"."+k;String low=(k+" "+String.valueOf(v)).toLowerCase(Locale.ROOT);if(match(low))hits.add(p+" = "+shorten(String.valueOf(v)));scan(v,p,hits);}}else if(o instanceof JSONArray){JSONArray a=(JSONArray)o;for(int i=0;i<Math.min(a.length(),10);i++)scan(a.opt(i),path+"["+i+"]",hits);}}catch(Exception ignored){}
    }
    private boolean match(String x){String[] ks={"开业","开张","装修","翻新","客房","房间","room","renovat","opening","open_date","opendate","checkin","hotel","year"};for(String k:ks)if(x.contains(k))return true;return false;}
    private String shorten(String x){x=x.replace("\n"," ");return x.length()>220?x.substring(0,220)+"...":x;}
    private String pretty(JSONObject j){try{return j.toString(2);}catch(Exception e){return j.toString();}}
}

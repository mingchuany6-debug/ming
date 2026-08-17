package com.tungee.amapbridge;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class AmapLauncher {
    public static boolean openPoi(Context c,String poi,String name,String lat,String lon){
        try {
            StringBuilder u=new StringBuilder("amapuri://poi/detail?sourceApplication=TungeeHotelHelper");
            if(name!=null&&!name.isEmpty()) u.append("&poiname=").append(URLEncoder.encode(name, StandardCharsets.UTF_8.name()));
            if(lat!=null&&!lat.isEmpty()) u.append("&lat=").append(Uri.encode(lat));
            if(lon!=null&&!lon.isEmpty()) u.append("&lon=").append(Uri.encode(lon));
            if(poi!=null&&!poi.isEmpty()) u.append("&poiid=").append(Uri.encode(poi));
            Intent i=new Intent(Intent.ACTION_VIEW,Uri.parse(u.toString()));
            i.setPackage("com.autonavi.minimap");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        }catch(Exception e){ return false; }
    }
}

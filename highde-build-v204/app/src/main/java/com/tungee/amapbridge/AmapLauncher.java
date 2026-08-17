package com.tungee.amapbridge;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

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

    public static boolean openSearch(Context c,String name,String lat,String lon){
        try{
            StringBuilder u=new StringBuilder("androidamap://poi?sourceApplication=TungeeHotelHelper");
            u.append("&keywords=").append(URLEncoder.encode(name==null?"":name, StandardCharsets.UTF_8.name()));
            u.append("&dev=0");
            try{
                double la=Double.parseDouble(lat), lo=Double.parseDouble(lon);
                double dLat=0.025, dLon=0.030;
                u.append("&lat1=").append(String.format(Locale.US,"%.6f",la+dLat));
                u.append("&lon1=").append(String.format(Locale.US,"%.6f",lo-dLon));
                u.append("&lat2=").append(String.format(Locale.US,"%.6f",la-dLat));
                u.append("&lon2=").append(String.format(Locale.US,"%.6f",lo+dLon));
            }catch(Exception ignored){}
            Intent i=new Intent(Intent.ACTION_VIEW,Uri.parse(u.toString()));
            i.setPackage("com.autonavi.minimap");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        }catch(Exception e){return false;}
    }
}

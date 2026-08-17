package com.tungee.amapbridge;

import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public final class Discovery {
    public static class Result { public String host; public int port; }

    public static Result findPc(int timeoutMs) {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            s.setBroadcast(true);
            s.setSoTimeout(timeoutMs);
            byte[] out = "TUNGEE_AMAP_DISCOVER_V2".getBytes(StandardCharsets.UTF_8);
            s.send(new DatagramPacket(out,out.length,InetAddress.getByName("255.255.255.255"),8766));
            byte[] buf = new byte[2048];
            DatagramPacket p = new DatagramPacket(buf,buf.length);
            s.receive(p);
            String txt = new String(p.getData(),0,p.getLength(),StandardCharsets.UTF_8);
            JSONObject j = new JSONObject(txt);
            if(!"TUNGEE_AMAP_PC_V2".equals(j.optString("type"))) return null;
            Result r = new Result();
            r.host = p.getAddress().getHostAddress();
            r.port = j.optInt("port",8765);
            return r;
        } catch(Exception e){
            return null;
        } finally {
            if(s!=null) s.close();
        }
    }
}

package com.tungee.amapbridge;

public final class TaskState {
    public static volatile String taskId="", poiId="", hotelName="", lat="", lon="", address="";
    public static volatile long startedAt=0;
    public static volatile boolean active=false;

    public static synchronized void set(String id,String poi,String name,String la,String lo,String addr){
        taskId=id; poiId=poi; hotelName=name; lat=la; lon=lo; address=addr;
        startedAt=System.currentTimeMillis(); active=true;
    }
    public static synchronized void clear(){ active=false; }
}

package com.theplugos.local;
import android.app.*;
import android.content.*;
import android.os.*;
import android.net.wifi.WifiManager;

public final class HubService extends Service {
    static volatile String status="Hub stopped";
    private HubServer server; private Ledger ledger; private PowerManager.WakeLock wake; private WifiManager.WifiLock wifi;
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager n=getSystemService(NotificationManager.class);
        n.createNotificationChannel(new NotificationChannel("hub","Local shop hub",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        startForeground(1,new Notification.Builder(this,"hub").setSmallIcon(android.R.drawable.ic_menu_manage).setContentTitle("ThePlugOS training hub").setContentText("Keep this device powered and on shop Wi-Fi").setContentIntent(open).setOngoing(true).build());
        try {
            Crypto crypto=new Crypto(); LocalState state=new LocalState(this,crypto);
            if(state.read().optJSONObject("config")==null || !state.read().optJSONObject("config").optBoolean("isHub",false)) throw new IllegalStateException("This device is not the hub");
            ledger=new Ledger(this,crypto); if(!ledger.initialized()) throw new IllegalStateException("Hub setup incomplete");
            server=new HubServer(ledger);
            wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ThePlugOS:hub"); wake.acquire();
            wifi=((WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE)).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"ThePlugOS:hub"); wifi.acquire();
            status="Hub listening on port 8443";
        } catch(Exception e) { status="Hub unavailable: "+e.getMessage(); stopSelf(); }
    }
    @Override public int onStartCommand(Intent i,int flags,int id) { return START_STICKY; }
    @Override public android.os.IBinder onBind(Intent i) { return null; }
    @Override public void onDestroy() {
        if(server!=null) server.close(); if(ledger!=null) ledger.close();
        if(wake!=null&&wake.isHeld()) wake.release(); if(wifi!=null&&wifi.isHeld()) wifi.release();
        if(status.startsWith("Hub listening")) status="Hub stopped"; super.onDestroy();
    }
}

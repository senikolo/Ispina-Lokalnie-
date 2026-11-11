package com.ispina.lokalnie.sync;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.webkit.JavascriptInterface;

public final class NativeScheduleBridge {
    private final Context context;
    public NativeScheduleBridge(Context context){this.context=context.getApplicationContext();}

    @JavascriptInterface public String getMldScheduleJson(){return ScheduleStore.read(context,SyncConfig.FILE_MLD);}
    @JavascriptInterface public String getGminnaScheduleJson(){return ScheduleStore.read(context,SyncConfig.FILE_GMINNA);}
    @JavascriptInterface public String getScheduleSyncStatus(){return ScheduleStore.statusJson(context);}
    @JavascriptInterface public void requestScheduleSync(){ScheduleSync.syncNow(context);}
    @JavascriptInterface public void setGminnaApiUrl(String url){ScheduleStore.setGminnaUrl(context,url);ScheduleSync.syncNow(context);}

    // The F-Droid candidate deliberately exposes no realtime endpoint. The UI
    // detects the empty response and falls back to the published timetable.
    @JavascriptInterface public String getMldRealtimeJson(){return "";}
    @JavascriptInterface public void requestMldRealtime(){}
    @JavascriptInterface public String getLatestPostJson(){return "";}

    @JavascriptInterface public String getWasteData(String fallbackJson){
        SharedPreferences p=context.getSharedPreferences("ispina_waste",Context.MODE_PRIVATE);
        String value=p.getString("data","");
        return value == null || value.isEmpty() ? (fallbackJson == null ? "" : fallbackJson) : value;
    }
    @JavascriptInterface public boolean saveWasteData(String json){
        return context.getSharedPreferences("ispina_waste",Context.MODE_PRIVATE)
                .edit().putString("data",json == null ? "" : json).commit();
    }
    @JavascriptInterface public boolean openReader(String rawUrl){
        try {
            Uri uri=Uri.parse(rawUrl);
            String scheme=uri.getScheme(), host=uri.getHost();
            if (!"https".equalsIgnoreCase(scheme) || host == null) return false;
            boolean allowed=host.equalsIgnoreCase("ispinalokalnie.com")
                    || host.equalsIgnoreCase("www.ispinalokalnie.com")
                    || host.equalsIgnoreCase("ispinalokalnie.wordpress.com");
            if (!allowed) return false;
            Intent intent=new Intent(Intent.ACTION_VIEW,uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Exception ignored) { return false; }
    }
}

package com.ispina.lokalnie.sync;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class ScheduleSyncWorker extends Worker {
    public ScheduleSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull @Override public Result doWork() {
        Context c=getApplicationContext(); SharedPreferences p=ScheduleStore.prefs(c); long now=System.currentTimeMillis();
        p.edit().putLong("last_attempt",now).apply(); boolean mld=false,gminna=false; String mldErr="",gErr="",mldSource="";
        for(String u:SyncConfig.MLD_GTFS_URLS){
            try{
                byte[] z=download(u,25_000,35_000,25*1024*1024);
                String parsed=GtfsSubsetParser.parse(z,u,now);
                new JSONObject(parsed); // dodatkowa walidacja JSON
                ScheduleStore.writeAtomic(c,SyncConfig.FILE_MLD,parsed);
                mld=true;mldSource=u;mldErr="";break;
            }catch(Exception e){mldErr=compact(e);}
        }
        String gu=ScheduleStore.getGminnaUrl(c);
        if(!gu.isEmpty()){
            try{
                String text=new String(download(gu,15_000,20_000,2*1024*1024),StandardCharsets.UTF_8);
                validateGminna(text); ScheduleStore.writeAtomic(c,SyncConfig.FILE_GMINNA,text); gminna=true;
            }catch(Exception e){gErr=compact(e);}
        }else gErr="Brak skonfigurowanego adresu Google Apps Script /exec";
        SharedPreferences.Editor e=p.edit().putString("mld_error",mldErr).putString("gminna_error",gErr).putString("mld_source",mldSource);
        if(mld)e.putLong("mld_last_success",now); if(gminna)e.putLong("gminna_last_success",now); if(mld||gminna)e.putLong("last_success",now); e.apply();
        // Nigdy nie kasujemy poprzedniego poprawnego cache. Brak sieci = retry dla ręcznego uruchomienia.
        return (mld||gminna)?Result.success():Result.retry();
    }

    private static void validateGminna(String text) throws Exception {
        JSONObject j=new JSONObject(text); if(j.optInt("schemaVersion",0)<1)throw new IllegalArgumentException("schemaVersion");
        JSONArray stops=j.optJSONArray("stops"),groups=j.optJSONArray("serviceGroups");
        if(stops==null||stops.length()<10||groups==null||groups.length()<2)throw new IllegalArgumentException("Niepełny JSON linii gminnej");
        for(int i=0;i<groups.length();i++){
            JSONObject g=groups.getJSONObject(i); JSONArray r=g.getJSONArray("route"),o=g.getJSONArray("offsets"); if(r.length()!=o.length())throw new IllegalArgumentException("route/offsets");
            int prev=-1; for(int k=0;k<o.length();k++){int x=o.getInt(k);if(x<prev)throw new IllegalArgumentException("malejące offsets");prev=x;}
        }
    }

    private static byte[] download(String url,int connect,int read,int max) throws Exception {
        HttpURLConnection h=(HttpURLConnection)new URL(url).openConnection(); h.setConnectTimeout(connect);h.setReadTimeout(read);h.setInstanceFollowRedirects(true);h.setRequestProperty("User-Agent","IspinaLokalnie/1.80-sync-draft");
        int code=h.getResponseCode(); if(code<200||code>=300)throw new IllegalStateException("HTTP "+code);
        try(InputStream in=h.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[16384];int n,total=0;while((n=in.read(b))>=0){total+=n;if(total>max)throw new IllegalStateException("Plik za duży");out.write(b,0,n);}return out.toByteArray();
        }finally{h.disconnect();}
    }
    private static String compact(Exception e){String s=e.getClass().getSimpleName()+": "+(e.getMessage()==null?"":e.getMessage());return s.length()>180?s.substring(0,180):s;}
}

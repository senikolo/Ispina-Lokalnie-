package com.ispina.lokalnie.sync;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

public final class ScheduleSync {
    private static final String DAILY="schedule-sync-daily";
    private static final String NOW="schedule-sync-now";
    private ScheduleSync(){}

    public static void scheduleDaily(Context context){
        Calendar now=Calendar.getInstance(); Calendar next=(Calendar)now.clone();
        next.set(Calendar.HOUR_OF_DAY,1); next.set(Calendar.MINUTE,5); next.set(Calendar.SECOND,0); next.set(Calendar.MILLISECOND,0);
        if(!next.after(now)) next.add(Calendar.DAY_OF_YEAR,1);
        long delay=Math.max(0,next.getTimeInMillis()-now.getTimeInMillis());
        Constraints c=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest r=new PeriodicWorkRequest.Builder(ScheduleSyncWorker.class,24, TimeUnit.HOURS)
                .setInitialDelay(delay,TimeUnit.MILLISECONDS).setConstraints(c).build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.UPDATE,r);
    }

    public static void syncNow(Context context){
        Constraints c=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest r=new OneTimeWorkRequest.Builder(ScheduleSyncWorker.class).setConstraints(c).build();
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE,r);
    }

    public static void syncIfStale(Context context){
        long last=ScheduleStore.prefs(context).getLong("last_attempt",0); long age=System.currentTimeMillis()-last;
        if(last==0||age>26L*60L*60L*1000L)syncNow(context);
    }
}

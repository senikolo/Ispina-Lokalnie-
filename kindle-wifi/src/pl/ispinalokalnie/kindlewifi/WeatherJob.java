package pl.ispinalokalnie.kindlewifi;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

public final class WeatherJob extends JobService {
    private static final int PERIODIC = 215031;
    private static final int IMMEDIATE = 215032;
    private final HashMap<Integer, Thread> workers = new HashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    static boolean kick(Context context) {
        if (!WeatherSync.isEnabled(WeatherSync.settings(context))) return false;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return false;
        ComponentName service = new ComponentName(context, WeatherJob.class);
        if (!hasJob(scheduler, IMMEDIATE)) {
            scheduler.schedule(new JobInfo.Builder(IMMEDIATE, service)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(0).build());
        }
        return hasJob(scheduler, PERIODIC)
                || scheduler.schedule(new JobInfo.Builder(PERIODIC, service)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                        .setPeriodic(TimeUnit.MINUTES.toMillis(30)).build()) == JobScheduler.RESULT_SUCCESS;
    }

    private static boolean hasJob(JobScheduler scheduler, int id) {
        for (JobInfo job : scheduler.getAllPendingJobs()) {
            if (job.getId() == id) return true;
        }
        return false;
    }

    static void cancel(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler != null) {
            scheduler.cancel(IMMEDIATE);
            scheduler.cancel(PERIODIC);
        }
    }

    @Override public boolean onStartJob(JobParameters params) {
        if (!WeatherSync.isEnabled(WeatherSync.settings(this))) return false;
        int id = params.getJobId();
        Thread worker = new Thread(() -> {
            try { WeatherSync.refresh(getApplicationContext(), false); }
            catch (Exception ignored) {
                // The cached report stays available and the periodic job can retry later.
            } finally {
                Thread self = Thread.currentThread();
                main.post(() -> {
                    if (workers.get(id) == self) {
                        workers.remove(id);
                        jobFinished(params, false);
                    }
                });
            }
        }, "kindle-weather");
        workers.put(id, worker);
        worker.start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        Thread worker = workers.remove(params.getJobId());
        if (worker != null) worker.interrupt();
        return WeatherSync.isEnabled(WeatherSync.settings(this));
    }

    @Override public void onDestroy() {
        for (Thread worker : workers.values()) worker.interrupt();
        workers.clear();
        super.onDestroy();
    }
}

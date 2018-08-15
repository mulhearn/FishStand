package edu.ucdavis.crayfis.fishstand;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.camera2.CaptureResult;
import android.os.AsyncTask;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;
import android.os.IBinder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by mulhearn on 4/28/18.
 */

public class DaqService extends Service implements Camera.Frame.OnFrameCallback {

    private static final float MIN_BATTERY_PCT = .3f;
    private static final float RESTART_BATTERY_PCT = .9f;
    private static final long BATTERY_CHECK_TIME = 600000L;

    Analysis analysis;

    Handler broadcast_handler;
    HandlerThread broadcast_thread;

    private LocalBroadcastManager broadcast_manager;
    private final BroadcastReceiver state_change_receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final App.STATE new_state = (App.STATE) intent.getSerializableExtra(App.EXTRA_NEW_STATE);
            broadcast_handler.post(new Runnable() {
                @Override
                public void run() {
                    switch (new_state) {
                        case RUNNING:
                            Run();
                            break;
                        case STOPPING:
                            Stop();
                            break;
                    }
                }
            });
        }
    };

    private AtomicInteger events;

    private String job_tag;
    private int num;
    private boolean repeat;
    private boolean run_finished; // to make sure we can still exit with stop button
    private int delay;
    private Boolean delay_applied;  // has the initial delay already been applied?

    private long last_exposure;
    private long last_duration;
    private int last_iso;

    public static final String TAG = "DaqService";

    @Override
    public void onCreate() {
        Log.i(TAG, "onCreate");

        broadcast_thread = new HandlerThread("Broadcasts");
        broadcast_thread.start();
        broadcast_handler = new Handler(broadcast_thread.getLooper());

        broadcast_manager = LocalBroadcastManager.getInstance(this);
        broadcast_manager.registerReceiver(state_change_receiver, new IntentFilter(App.ACTION_STATE_CHANGE));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand");

        delay_applied = false;
        showNotification();

        return START_STICKY;
    }

    @Override
    public void onDestroy(){
        Log.i(TAG, "onDestroy");

        stopForeground(true);
        broadcast_manager.unregisterReceiver(state_change_receiver);
        broadcast_thread.quitSafely();
        try {
            broadcast_thread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    // Required notification for running service in Foreground:
    public interface NOTIFICATIONS {
        int FOREGROUND_ID = 101;
        String CHANNEL_ID = "edu.ucdavis.crayfis.fishstand";
        String CHANNEL_NAME = "FishStand DAQ";
    }

    private void showNotification() {

        /*
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, 0);
        */

        Bitmap icon = BitmapFactory.decodeResource(getResources(),
                R.mipmap.ic_launcher);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel notificationChannel
                    = new NotificationChannel(NOTIFICATIONS.CHANNEL_ID, NOTIFICATIONS.CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW);

            NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(notificationChannel);
        }

        Notification notification = new NotificationCompat.Builder(this, NOTIFICATIONS.CHANNEL_ID)
                .setContentTitle("FishStand Cosmics")
                .setTicker("FishStand Cosmics")
                .setContentText("Cosmics running is underway")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setLargeIcon(Bitmap.createScaledBitmap(icon, 128, 128, false))
                //.setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATIONS.FOREGROUND_ID,
                notification);

    }

    @Override
    public IBinder onBind(Intent intent) {
        // Used only in case if services are bound (Bound Services).
        return null;
    }

    public void Run() {

        int run_num = App.getPref().getInt("run_num", 0);
        App.log().newRun(run_num);

        App.log().append("init called.\n");
        App.getConfig().parseConfig();
        App.getConfig().logConfig();

        run_finished = false;
        events = new AtomicInteger();

        job_tag = App.getConfig().getString("tag", "unspecified");
        num     = App.getConfig().getInteger("num", 1);
        delay   = App.getConfig().getInteger("delay", 0);
        repeat  = App.getConfig().getBoolean("repeat", false);
        String analysis_name = App.getConfig().getString("analysis", "");

        App.log().append("analysis:       " + analysis_name + "\n")
                .append("num of images:  " + num + "\n")
                .append("repeat mode:    " + repeat + "\n")
                .append("job tag:        " + job_tag + "\n")
                .append("delay:          " + delay + "\n");

        switch (analysis_name.toLowerCase()) {
            case "pixelstats":
                analysis = new PixelStats();
                break;
            case "photo":
                analysis = new Photo();
                break;
            case "cosmics":
                analysis = new Cosmics();
                break;

            default:
                analysis = null;
        }

        App.log().append("starting run " + run_num + "\n");
        App.log().append("Finished initialization.\n");

        if ((!delay_applied) && (delay > 0)) {
            App.log().append("Delaying start of first run by " + delay + " seconds.\n");
            SystemClock.sleep(delay * 1000);
            delay_applied = true;
        }

        App.getCamera().start(this);
    }

    private void Stop() {
        App.log().append("run stopping\n");
        App.getCamera().stop(!repeat);

        if (analysis != null){
            analysis.ProcessRun();
        }

        App.log().append("events:   " + events.intValue() + "\n");

        String date = new SimpleDateFormat("hh:mm aaa yyyy-MMM-dd ", Locale.getDefault()).format(new Date());

        int run_num = App.getPref().getInt("run_num", 0);
        App.log().append("ending run " + run_num + " at " + date + "\n");
        run_num++;
        App.getEdit()
                .putInt("run_num", run_num)
                .commit();

        if (repeat && run_finished) {
            if(checkBatteryPct(MIN_BATTERY_PCT)) {
                App.updateState(App.STATE.RUNNING);
                return;
            }

            // periodically check to see if we have enough charge to start a new run
            Timer idleTimer = new Timer();
            TimerTask idleTimerTask = new TimerTask() {
                @Override
                public void run() {
                    if(checkBatteryPct(RESTART_BATTERY_PCT)) {
                        this.cancel();
                        App.updateState(App.STATE.RUNNING);
                    }
                }
            };
            idleTimer.scheduleAtFixedRate(idleTimerTask, BATTERY_CHECK_TIME, BATTERY_CHECK_TIME);
            App.updateState(App.STATE.CHARGING);
        } else {
            App.updateState(App.STATE.READY);
        }

    }

    private boolean checkBatteryPct(float thresh) {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, ifilter);

        // get battery updates
        if(batteryStatus == null) return true; // if this fails for whatever reason, just keep going

        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        float batteryPct = level / (float) scale;
        return batteryPct > thresh;
    }


    @Override
    public void onFrame(@NonNull final Camera.Frame frame, final int num_frames) {

        final int verbosity = getEventVerbosity(num_frames);

        switch (verbosity) {
            case 2:
                App.log().append("row stride: " + frame.image.getPlanes()[0].getRowStride() + "\n")
                        .append("pixel stride: " + frame.image.getPlanes()[0].getPixelStride() + "\n");
            case 1:
                App.log().append("Frame acquired \n");
        }

        Long exp = frame.result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Long dur = frame.result.get(CaptureResult.SENSOR_FRAME_DURATION);
        Integer iso = frame.result.get(CaptureResult.SENSOR_SENSITIVITY);

        if(exp != null && dur != null && iso != null &&
                (exp != last_exposure || dur != last_duration || iso != last_iso)) {
            last_exposure = exp;
            last_duration = dur;
            last_iso = iso;
            App.log().append("capture complete with exposure " + exp + " duration " + dur + " sensitivity " + iso + "\n");
        }

        if (num_frames == num + 1) {
            frame.image.close();
            run_finished = true;
            App.updateState(App.STATE.STOPPING);
        } else if(num_frames <= num) {
            try {
                AsyncTask.THREAD_POOL_EXECUTOR.execute(new Runnable() {
                    @Override
                    public void run() {
                        if (analysis != null){
                            analysis.ProcessImage(frame.image);
                        }

                        try {
                            frame.image.close();
                        } catch (IllegalStateException e) {
                            Log.e(TAG,"Image close failure.");
                            return;
                        }
                        int num_events = events.incrementAndGet();

                        if (verbosity >= 0){
                            String msg = "finished processing " + num_events + " events.\n";
                            App.log().append(msg);
                        }
                    }
                });
            } catch (IllegalStateException e) {
                Log.e(TAG, "Failed to start image processing thread.");
                try {
                    frame.image.close();
                } catch (IllegalStateException e2) {
                    Log.e(TAG, "Image close failure.");
                }
            }
        }
    }

    public static int getEventVerbosity(int event) {
        if(event == 1) return 2;
        if(event < 10) return 1;
        if((event < 100 && event % 10 == 0) || event % 100 == 0) return 0;
        return -1;
    }

}

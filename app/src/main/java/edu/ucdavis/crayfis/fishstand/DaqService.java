package edu.ucdavis.crayfis.fishstand;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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

import edu.ucdavis.crayfis.fishstand.analysis.Analysis;
import edu.ucdavis.crayfis.fishstand.analysis.Cosmics;
import edu.ucdavis.crayfis.fishstand.analysis.TriggeredImage;
import edu.ucdavis.crayfis.fishstand.analysis.Photo;
import edu.ucdavis.crayfis.fishstand.analysis.PixelStats;
import edu.ucdavis.crayfis.fishstand.camera.Frame;

public class DaqService extends Service implements Frame.OnFrameCallback {

    private static final float MIN_BATTERY_PCT = .3f;
    private static final float RESTART_BATTERY_PCT = .9f;
    private static final long BATTERY_CHECK_TIME = 600000L;

    private Macro macro;
    private boolean upload;

    private Analysis analysis;

    private Handler broadcast_handler;
    private HandlerThread broadcast_thread;

    private LocalBroadcastManager broadcast_manager;
    private final BroadcastReceiver state_change_receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final App.STATE new_state = (App.STATE) intent.getSerializableExtra(App.EXTRA_NEW_STATE);
            final String config_name = intent.getStringExtra(App.EXTRA_CONFIG_FILE);
            upload = intent.getBooleanExtra(App.EXTRA_UPLOAD_FILES, false);

            if(upload)
                startService(new Intent(DaqService.this, UploadService.class));

            broadcast_handler.post(new Runnable() {
                @Override
                public void run() {
                    switch (new_state) {
                        case RUNNING:
                            // try to load a macro if we aren't already in the middle of one
                            if(macro == null) {
                                if(config_name == null)
                                    macro = Macro.create("default.mac");
                                else if(config_name.endsWith(".mac"))
                                    macro = Macro.create(config_name);

                                // parse to the first config (if it exists)
                                // this loads any environment vars at the start of the file
                                if(macro != null && !macro.hasNext())
                                    macro = null;

                            }

                            final Config cfg;

                            if(macro != null && macro.hasNext()) {
                                // first, try working with a macro file
                                cfg = macro.next();
                            } else {
                                // otherwise, we must have a single config file
                                try {
                                    cfg = config_name == null ?
                                            new Config("default.cfg") :
                                            new Config(config_name);
                                } catch (Exception e) {
                                    Toast.makeText(DaqService.this,
                                            "Invalid file content",
                                            Toast.LENGTH_LONG)
                                            .show();
                                    App.updateState(App.STATE.READY);
                                    return;
                                }
                            }
                            Run(cfg);
                            break;
                        case STOPPING:
                            Stop();
                            break;
                        case READY:
                            macro = null;
                    }
                }
            });
        }
    };

    private AtomicInteger processing;
    private AtomicInteger events;

    private String job_tag;
    private int num;
    private boolean run_finished; // to make sure we can still exit with stop button
    private int delay;

    private long last_exposure;
    private long last_duration;
    private int last_iso;

    public static final String TAG = "DaqService";

    @Override
    public void onCreate() {

        broadcast_thread = new HandlerThread("Broadcasts");
        broadcast_thread.start();
        broadcast_handler = new Handler(broadcast_thread.getLooper());

        broadcast_manager = LocalBroadcastManager.getInstance(this);
        broadcast_manager.registerReceiver(state_change_receiver, new IntentFilter(App.ACTION_STATE_CHANGE));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand");
        //App.log().append("DaqService received onStartCommand\n");

        // show notifications

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
                .setContentText("FishStand data acquisition is underway")
                .setSmallIcon(R.drawable.ic_daq_icon)
                //.setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATIONS.FOREGROUND_ID, notification);

        return START_STICKY;
    }

    @Override
    public void onDestroy(){
        Log.i(TAG, "onDestroy");
        //App.log().append("DaqService received onDestroy\n");

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
        String CHANNEL_ID = "edu.ucdavis.crayfis.fishstand.DaqService";
        String CHANNEL_NAME = "FishStand DAQ";
    }

    @Override
    public IBinder onBind(Intent intent) {
        // Used only in case if services are bound (Bound Services).
        return null;
    }

    public void Run(Config cfg) {

        int run_num = App.getPref().getInt("run_num", 0);
        App.log().newRun(run_num, upload);
        App.log().append("init called.\n");
        cfg.logConfig();

        App.getCamera().configure(cfg);

        events = new AtomicInteger();
        processing = new AtomicInteger();

        job_tag = cfg.getString("tag", "unspecified");
        num     = cfg.getInteger("num", 1);
        delay   = cfg.getInteger("delay", 0);
        String analysis_name = cfg.getString("analysis", "");

        App.log().append("analysis:       " + analysis_name + "\n")
                .append("num of images:  " + num + "\n")
                .append("job tag:        " + job_tag + "\n")
                .append("delay:          " + delay + "\n");

        switch (analysis_name.toLowerCase()) {
            case "pixelstats":
                analysis = new PixelStats(cfg, upload);
                break;
            case "photo":
                analysis = new Photo(cfg, upload);
                break;
            case "cosmics":
                analysis = new Cosmics(cfg, upload);
                break;
            case "triggered_image":
                analysis = new TriggeredImage(cfg, upload);
                break;

            default:
                analysis = null;
        }

        App.log().append("starting run " + run_num + "\n");
        App.log().append("Finished initialization.\n");

        if ((!run_finished) && (delay > 0)) {
            App.log().append("Delaying start of first run by " + delay + " seconds.\n");
            SystemClock.sleep(delay * 1000);
        }
        run_finished = false;
        App.getCamera().start(this);
    }

    private void Stop() {
        App.log().append("run stopping\n");
        App.getCamera().stop();
        EndRun();
    }

    private void EndRun(){
        // wait for all jobs to finish...
        if (processing.get() > 0){
            Runnable r = new Runnable(){public void run(){EndRun();};};
            broadcast_handler.postDelayed(r,1000);
            return;
        }

        boolean restart = macro != null && macro.hasNext() && run_finished;

        if (analysis != null){
            analysis.ProcessRun();
        }

        App.getCamera().close();

        App.log().append("events:   " + events.intValue() + "\n");

        String date = new SimpleDateFormat("hh:mm aaa yyyy-MMM-dd ", Locale.getDefault()).format(new Date());

        int run_num = App.getPref().getInt("run_num", 0);
        App.log().append("ending run " + run_num + " at " + date + "\n");
        run_num++;
        App.getEdit()
                .putInt("run_num", run_num)
                .commit();

        if (restart) {
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
    public void onFrame(@NonNull final Frame frame, final int num_frames) {

        final int verbosity = getEventVerbosity(num_frames);

        Long exp = frame.getTotalCaptureResult().get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Long dur = frame.getTotalCaptureResult().get(CaptureResult.SENSOR_FRAME_DURATION);
        Integer iso = frame.getTotalCaptureResult().get(CaptureResult.SENSOR_SENSITIVITY);

        if(exp != null && dur != null && iso != null &&
                (exp != last_exposure || dur != last_duration || iso != last_iso)
                || num_frames == 1) {
            last_exposure = exp;
            last_duration = dur;
            last_iso = iso;

            App.log().append("capture complete with exposure " + exp
                    + " duration " + dur
                    + " sensitivity " + iso + "\n");
            long request = App.getCamera().getExposure();
            if (Math.abs(exp - request) / request > .1){
                App.log().append("reported exposure " + exp + " does not match request " + request + "\n");
            }
        }

        if (num_frames > num && num > 0) {
            frame.close();
            if (num_frames == num+1) {
                run_finished = true;
                App.updateState(App.STATE.STOPPING);
            }
        } else {
            try {
                AsyncTask.THREAD_POOL_EXECUTOR.execute(new Runnable() {
                    @Override
                    public void run() {
                        if (analysis != null){
                            processing.incrementAndGet();
                            analysis.ProcessFrame(frame);
                            processing.decrementAndGet();
                        }

                        try {
                            frame.close();
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
                    frame.close();
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

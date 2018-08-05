package edu.ucdavis.crayfis.fishstand;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.AsyncTask;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;
import android.os.IBinder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by mulhearn on 4/28/18.
 */

public class DaqService extends Service {
    Analysis analysis;

    private LocalBroadcastManager broadcast_manager;
    private final BroadcastReceiver state_change_receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            App.STATE new_state = (App.STATE) intent.getSerializableExtra(App.EXTRA_NEW_STATE);
            switch (new_state) {
                case RUNNING:
                    Run();
                    break;
                case STOPPING:
                    Stop();
                    break;
                case READY:
                    // without repeat, we must be destroying the service
                    if(!repeat) broadcast_manager.unregisterReceiver(state_change_receiver);
            }
            App.getMessage().updateState();
        }
    };

    private AtomicInteger requests, events;
    private long run_start, run_end;

    private String job_tag;
    private int num;
    private Boolean repeat;
    private int delay;
    private Boolean delay_applied;  // has the initial delay already been applied?

    public static final String TAG = "DaqService";

    @Override
    public void onCreate() {
        broadcast_manager = LocalBroadcastManager.getInstance(this);
        broadcast_manager.registerReceiver(state_change_receiver, new IntentFilter(App.ACTION_STATE_CHANGE));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand called");

        if (App.getAppState() != App.STATE.READY){
            Log.i(TAG, "could not start DAQ service, state is not READY");
        }

        delay_applied = false;
        App.updateState(App.STATE.RUNNING);
        showNotification();
        Toast.makeText(this, "DAQ Started.", Toast.LENGTH_SHORT).show();

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy(){
        repeat = false;
        App.updateState(App.STATE.STOPPING);

        stopForeground(true);
    }

    // Required notification for running service in Foreground:
    public interface NOTIFICATION_ID {
        public static int FOREGROUND_SERVICE = 101;
    }

    private void showNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, 0);

        Bitmap icon = BitmapFactory.decodeResource(getResources(),
                R.mipmap.ic_launcher);

        Notification notification = new Notification.Builder(this)
                .setContentTitle("FishStand Cosmics")
                .setTicker("FishStand Cosmics")
                .setContentText("Cosmics running is underway")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setLargeIcon(Bitmap.createScaledBitmap(icon, 128, 128, false))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID.FOREGROUND_SERVICE,
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

        App.log().append("starting run " + run_num + "\n");

        if (App.getCamera().ireader == null) {
            App.updateState(App.STATE.READY);
            App.log().append("ireader is null after init...camera configuration failed.\n");
            return;
        }
        // clear any stale photo:
        Image img = App.getCamera().ireader.acquireLatestImage();
        if (img != null) {
            App.log().append("discarding unexpected image.\n");
            img.close();
        }

        Init();

        App.log().append("Finished initialization.\n");

        if ((!delay_applied) && (delay > 0)) {
            App.log().append("Delaying start of first run by " + delay + " seconds.\n");
            SystemClock.sleep(delay * 1000);
            delay_applied = true;
        }

        try {
            final CaptureRequest.Builder captureBuilder = App.getCamera().cdevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureBuilder.addTarget(App.getCamera().ireader.getSurface());
            captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, App.getCamera().max_exp);
            captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, App.getCamera().max_analog);
            captureBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, App.getCamera().max_frame);
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            captureBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f); // put focus at infinity
            captureBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF); // need to see if any effect
            captureBuilder.set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_OFF); // need to see if any effect!
            captureBuilder.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_OFF); // need to see if any effect!

            if (analysis != null){
                analysis.Next(captureBuilder);
            }

            App.getCamera().csession.setRepeatingRequest(captureBuilder.build(), doNothingCaptureListener, App.getHandler());

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void Stop() {
        Log.d(TAG, "Stop()");
        App.log().append("run stopping\n");
        try {
            App.getCamera().csession.stopRepeating();
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

        if (analysis != null){
            analysis.ProcessRun();
        }

        String summary = "";
        //summary += "run start:  " + run_start + "\n";
        //summary += "run end:    " + run_end + "\n";
        //long duration = run_end - run_start;
        //if ((events > 0) && (duration > 0)){
        //    double frame_rate = ((double) duration) / ((double) events);
        //    summary += "framerate:  " + frame_rate + "\n";
        //}
        summary += "requests:  " + requests.intValue() + "\n";
        summary += "success:   " + events.intValue() + "\n";
        App.log().append(summary);

        String date = new SimpleDateFormat("hh:mm aaa yyyy-MMM-dd ", Locale.getDefault()).format(new Date());

        int run_num = App.getPref().getInt("run_num", 0);
        App.log().append("ending run " + run_num + " at " + date + "\n");
        run_num++;
        App.getEdit()
                .putInt("run_num", run_num)
                .commit();

        if (repeat){
            App.updateState(App.STATE.RUNNING);
        } else {
            App.updateState(App.STATE.READY);
        }
    }


    // interface for ImageReader's OnImageAvailableListener callback:
    ImageReader.OnImageAvailableListener daqImageListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(final ImageReader reader) {
            if (events.intValue() < 10 && App.getAppState() == App.STATE.RUNNING) {
                App.log().append("Image available called.\n");
            }

            final Image img = reader.acquireNextImage();
            if (img == null) {
                Log.e(TAG,"Image returned from reader was Null.");
                return;
            }

            if (requests.incrementAndGet() == num) {
                img.close();
                App.updateState(App.STATE.STOPPING);
            } else {
                try {
                    AsyncTask.THREAD_POOL_EXECUTOR.execute(new Runnable() {
                        @Override
                        public void run() {
                            processImage(img);
                        }
                    });
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Failed to start image processing thread.");
                    try {
                        img.close();
                    } catch (IllegalStateException e2) {
                        Log.e(TAG, "Image close failure.");
                    }
                }
            }
        }
    };

    private boolean verbose_event(int event) {
        return event < 10
                || (event < 100 && event%10 == 0)
                || event%100 == 0;
    }


    private void processImage(Image img) {

        if (analysis != null){
            analysis.ProcessImage(img, events.intValue());
        }

        try {
            img.close();
        } catch (IllegalStateException e) {
            Log.e(TAG,"Image close failure.");
            return;
        }
        if (verbose_event(events.incrementAndGet())){
            String msg = "finished processing " + events + " events.\n";
            App.log().append(msg);
        }
    }

    public void Init() {
        App.log().append("init called.\n");
        App.getCamera().ireader.setOnImageAvailableListener(daqImageListener, App.getHandler());
        requests = new AtomicInteger();
        events = new AtomicInteger();

        App.getConfig().parseConfig();
        App.getConfig().logConfig();

        job_tag = App.getConfig().getString("tag", "unspecified");
        num     = App.getConfig().getInteger("num", 1);
        delay   = App.getConfig().getInteger("delay", 0);
        repeat  = App.getConfig().getBoolean("repeat", false);
        String analysis_name = App.getConfig().getString("analysis", "");

        String logstr =  "analysis:       " + analysis_name + "\n";
        logstr += "num of images:  " + num + "\n";
        logstr += "repeat mode:    " + repeat + "\n";
        logstr += "job tag:        " + job_tag + "\n";
        logstr += "delay:          " + delay + "\n";
        App.log().append(logstr);

        switch (analysis_name.toLowerCase()) {
            case "pixelstats":
                analysis = PixelStats.create();
                break;
            case "photo":
                analysis = Photo.create();
                break;
            default:
                analysis = null;
        }
        if (analysis != null){
            analysis.Init();
        }
    }

    final private CameraCaptureSession.CaptureCallback doNothingCaptureListener = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);

            long exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            long iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
            if (events.intValue() < 10) {
                App.log().append("capture complete with exposure " + exp + " sensitivity " + iso + "\n");
            }
        }
    };



}

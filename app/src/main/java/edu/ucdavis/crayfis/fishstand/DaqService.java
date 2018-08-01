package edu.ucdavis.crayfis.fishstand;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import android.widget.Toast;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Created by mulhearn on 4/28/18.
 */

public class DaqService extends Service implements Runnable {
    Analysis analysis;

    // State of the DAQ:
    public enum STATE {
        RUNNING,   // A run is underway
        STOPPING,  // A run stop has been requested, but worker threads may not have finishd yet
        READY;     // Ready for a new run.
    };

    private final Object count_lock = new Object();
    private int requests, processing, events;
    private long run_start, run_end;

    private String job_tag;
    private int num;
    private Boolean repeat;
    private int delay;
    private Boolean delay_applied;  // has the initial delay already been applied?

    public static STATE state = STATE.READY;
    public static final String TAG = "DaqService";

    private BroadcastReceiver forceStop = null;

    // Foreground Service / Main Activity interaction via Intents and onStartCommand
    interface ACTION {
        String MAIN_ACTION = "edu.ucdavis.fishstand.daq.action.main";
        String STARTFOREGROUND_ACTION = "edu.ucdavis.fishstand.daq.action.startforeground";
        String STOPFOREGROUND_ACTION = "edu.ucdavis.fishstand.daq.action.stopforeground";
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand called");

        if (intent == null){
            Log.e(TAG, "onStartCommand called with null intent.");
            return START_STICKY;
        }

        if (intent.getAction().equals(ACTION.STARTFOREGROUND_ACTION)) {
            Log.i(TAG, "Received Start Foreground Intent ");
            if (state!=STATE.READY){
                Log.i(TAG, "could not start DAQ service, state is not READY");
                return START_STICKY;
            }

            delay_applied = false;
            new Thread(this).start();
            showNotification();
            Toast.makeText(this, "DAQ Started.", Toast.LENGTH_SHORT).show();

        } else if (intent.getAction().equals(
                ACTION.STOPFOREGROUND_ACTION)) {
            Log.i(TAG, "Received Stop Foreground Intent");
            Toast.makeText(this, "DAQ stopping.", Toast.LENGTH_SHORT).show();
            externalStop();
        }
        return START_STICKY;
    }

    // handle an externally driven stop request:
    private void externalStop(){
        repeat = false;
        if (state == STATE.RUNNING) {
            state = STATE.STOPPING;
        }
        if (forceStop != null) {
            App.getMessage().unregister(forceStop);
        }
    }

    // Required notification for running service in Foreground:
    public interface NOTIFICATION_ID {
        public static int FOREGROUND_SERVICE = 101;
    }
    private void showNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setAction(ACTION.MAIN_ACTION);
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
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "In onDestroy");
        //Toast.makeText(this, "Service Detroyed!", Toast.LENGTH_SHORT).show();
    }

    @Override
    public IBinder onBind(Intent intent) {
        // Used only in case if services are bound (Bound Services).
        return null;
    }

    public void run(){
        state = STATE.RUNNING;
        App.getMessage().updateState();

        int run_num = App.getPref().getInt("run_num", 0);
        App.log().append("starting run " + run_num + "\n");


        if (App.getCamera().ireader == null) {
            state=STATE.READY;
            App.log().append("ireader is null after init...camera configuration failed.\n");
            return;
        }
        // clear any stale photo:
        Image img = App.getCamera().ireader.acquireLatestImage();
        if (img != null){
            App.log().append("discarding unexpected image.\n");
            img.close();
        }


        App.getStorage().newLog(run_num);

        Init();

        App.getStorage().appendLog("Finished initialization.\n");

        if ((!delay_applied) && (delay>0)){
            String str = "Delaying start of first run by " + delay + " seconds.\n";
            App.getStorage().appendLog(str);
            App.log().append(str);
            SystemClock.sleep(delay*1000);
            delay_applied = true;
        }

        while(state == STATE.RUNNING){
            Next();
            SystemClock.sleep(200);
            if (requests >= num){
                state = STATE.STOPPING;
            }
        }
        Stop();

        String date = new SimpleDateFormat("hh:mm aaa yyyy-MMM-dd ", Locale.getDefault()).format(new Date());

        App.getStorage().appendLog("ending run at " + date + "\n");

        App.log().append("ending run " + run_num + "\n");
        run_num = run_num + 1;
        SharedPreferences.Editor edit = App.getEdit();
        edit.putInt("run_num", run_num);
        edit.commit();

        if (repeat){
            new Thread(this).start();
            return;
        }

        state = STATE.READY;
        App.getMessage().updateState();
        stopForeground(true);
        stopSelf();
    }

    // delegation of CameraCaptureSession's StateCallback, called from Camera
    static public void onReady(CameraCaptureSession session) {
    }
    static public void onActive(CameraCaptureSession session) {
    }
    static public void onClosed(CameraCaptureSession session) {
    }
    static public void onConfigureFailed(CameraCaptureSession session) {
    }
    static public void onSurfacePrepared(CameraCaptureSession session, Surface surface) {
    }

    // interface for ImageReader's OnImageAvailableListener callback:
    ImageReader.OnImageAvailableListener daqImageListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(final ImageReader reader) {
            if (events < 10) {
                App.log().append("Image available called.  processing=" + processing + "\n");
            }
            final Image img = reader.acquireNextImage();
            if (img == null) {
                Log.e(TAG,"Image returned from reader was Null.");
                return;
            }
            try {
                Runnable r = new Runnable() {
                    public void run() {
                        processImage(img);
                    }
                };
                (new Thread(r)).start();
            } catch (IllegalStateException e) {
                Log.e(TAG,"Failed to start image processing thread.");
                try {
                    img.close();
                    synchronized(count_lock) {
                        processing = processing - 1;
                    }
                } catch (IllegalStateException e2) {
                    Log.e(TAG,"Image close failure.");
                }
            }
        }
    };

    private boolean verbose_event(int event) {
        if (event < 10) return true;
        if ((event < 100) && (event%10 == 0)) return true;
        if ((event%100 == 0)) return true;
        return false;
    }


    private void processImage(Image img) {

        if (analysis != null){
            analysis.ProcessImage(img, events);
        }

        try {
            img.close();
        } catch (IllegalStateException e) {
            Log.e(TAG,"Image close failure.");
            //synchronized(count_lock) {
            //    processing = processing - 1;
            //}
            return;
        }
        boolean verbose = false;
        synchronized(count_lock) {
            events = events + 1;
            processing = processing - 1;
            verbose = verbose_event(events);
        }
        if (verbose){
            String msg = "finished processing " + events + " events.\n";
            App.log().append(msg);
            App.getStorage().appendLog(msg);
        }
    }

    public void Init() {
        App.log().append("init called.\n");
        App.getCamera().ireader.setOnImageAvailableListener(daqImageListener, App.getHandler());
        requests = 0;
        processing = 0;
        events = 0;

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
        App.getStorage().appendLog(logstr);

        switch (analysis_name) {
            case "pixelstats":
                analysis = PixelStats.create();
                break;
            case "photo":
                analysis = Photo.create();
                break;
            default:
                analysis = null;
                break;
        }
        if (analysis != null){
            analysis.Init();
        }
    }

    private void Stop() {
        App.log().append("run stopping\n");

        // give jobs a minute to finish up:
        int count = 0;
        while((processing > 0)&&(count<60)) {
            SystemClock.sleep(1000);
            count++;
        }
        if (processing == 0) {
            App.log().append("all image processing jobs have completed\n");
        } else {
            App.log().append("timeout waiting for all image processing jobs to complete.\n");
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
        summary += "requests:  " + requests + "\n";
        summary += "success:   " + events + "\n";
        App.log().append(summary);
    }

    private void Next() {
        // Next only applies during the RUNNING state:
        if (DaqService.state != DaqService.STATE.RUNNING) return;

        if (processing < App.getCamera().ireader.getMaxImages()) {
            try {
                final CaptureRequest.Builder captureBuilder = App.getCamera().cdevice.createCaptureRequest(CameraDevice.TEMPLATE_MANUAL);
                captureBuilder.addTarget(App.getCamera().ireader.getSurface());
                captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, App.getCamera().max_exp);
                captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, App.getCamera().max_analog);
                captureBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, App.getCamera().max_frame);
                captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                float fl = (float) 0.0;
                captureBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, fl); // put focus at infinity
                captureBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF); // need to see if any effect
                captureBuilder.set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_OFF); // need to see if any effect!
                captureBuilder.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_OFF); // need to see if any effect!

                //Image img = App.getCamera().ireader.acquireLatestImage();
                //if (img != null){
                //    App.log().append("discarding unexpected image.\n");
                //    img.close();
                //}
                if (analysis != null){
                    analysis.Next(captureBuilder);
                }

                if (events < 10) {
                    App.log().append("Next: requesting new image capture\n");
                }

                App.getCamera().csession.capture(captureBuilder.build(), doNothingCaptureListener, App.getHandler());
                synchronized(count_lock) {
                    processing++;
                }
                requests++;

            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
    }

    final private CameraCaptureSession.CaptureCallback doNothingCaptureListener = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
        long exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        long iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
        if (events < 10) {
            App.log().append("capture complete with exposure " + exp + " sensitivity " + iso + "\n");
        }
        super.onCaptureCompleted(session, request, result);
        }
    };



}

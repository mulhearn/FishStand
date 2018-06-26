package edu.ucdavis.crayfis.fishstand;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
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

import com.google.android.gms.drive.DriveFile;
import com.google.android.gms.drive.DriveFolder;

/**
 * Created by mulhearn on 4/28/18.
 */

public class DaqService extends Service implements Runnable {
    GoogleDrive drive;


    // Foreground Service / Main Activity interaction via Intents and onStartCommand
    public interface ACTION {
        public static String MAIN_ACTION = "edu.ucdavis.fishstand.daq.action.main";
        public static String STARTFOREGROUND_ACTION = "edu.ucdavis.fishstand.daq.action.startforeground";
        public static String STOPFOREGROUND_ACTION = "edu.ucdavis.fishstand.daq.action.stopforeground";
    }
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent.getAction().equals(ACTION.STARTFOREGROUND_ACTION)) {
            Log.i(TAG, "Received Start Foreground Intent ");
            if (state!=STATE.READY){
                Log.i(TAG, "could not start DAQ service, state is not READY");
                return START_STICKY;
            }
            new Thread(this).start();
            showNotification();
            Toast.makeText(this, "DAQ Started.", Toast.LENGTH_SHORT).show();
        } else if (intent.getAction().equals(
                ACTION.STOPFOREGROUND_ACTION)) {
            Log.i(TAG, "Received Stop Foreground Intent");
            if (state == STATE.RUNNING) {
                state = STATE.STOPPING;
                Toast.makeText(this, "DAQ stopping.", Toast.LENGTH_SHORT).show();
            }
        }
        return START_STICKY;
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


    // State of the DAQ:
    public enum STATE {
        RUNNING,   // A run is underway
        STOPPING,  // A run stop has been requested, but worker threads may not have finishd yet
        READY;     // Ready for a new run.
    };

    private enum IMAGE_JOB_STATUS {SUCCESS, FAILED, STOPPED, LOST}

    public class DaqException extends Exception {
        public DaqException(String msg){
            super(msg);
        }
    }

    private final Object count_lock = new Object();
    private int requests, processing, events;
    private long run_start, run_end;

    public static STATE state = STATE.READY;
    public static final String TAG = "fishstand-cosmics";

    @Override
    public void onCreate() {
        super.onCreate();
        drive = new GoogleDrive();
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

        App.log().append("starting run " + App.getPref().getInt("run_num", 0) + "\n");

        if (App.getCamera().ireader == null) {
            state=STATE.READY;
            App.log().append("ireader is null after init...camera configuration failed.\n");
            return;
        }

        Init();

        while(state == STATE.RUNNING){
            Next();
            SystemClock.sleep(200);
            if (requests >= 10){
                state = STATE.STOPPING;
            }
        }
        Stop();

        int run_num = App.getPref().getInt("run_num", 0);
        App.log().append("ending run " + run_num + "\n");
        run_num = run_num + 1;
        SharedPreferences.Editor edit = App.getEdit();
        edit.putInt("run_num", run_num);
        edit.commit();
        state = STATE.READY;
        App.getMessage().updateState();
        stopForeground(true);
        stopSelf();
    }

    // delegation of CameraCaptureSession's StateCallback, called from CameraConfig
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
            App.log().append("image available called.  processing=" + processing + "\n");
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


    private void processImage(Image img) {
        Log.i(TAG,"processing image.");

        SystemClock.sleep(5000);

        try {
            img.close();
        } catch (IllegalStateException e) {
            Log.e(TAG,"Image close failure.");
            //synchronized(count_lock) {
            //    processing = processing - 1;
            //}
            return;
        }
        events = events + 1;
        synchronized(count_lock) {
            processing = processing - 1;
        }
    }

    public void Init() {
        App.log().append("init called.\n");
        App.getCamera().ireader.setOnImageAvailableListener(daqImageListener, App.getHandler());
        requests = 0;
        processing = 0;
        events = 0;
    }

    private void Stop() {
        App.log().append("run stopping\n");
        while(processing > 0) {
            SystemClock.sleep(1000);
        }
        App.log().append("all image processing jobs have completed\n");

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

                Image img = App.getCamera().ireader.acquireLatestImage();
                if (img != null){
                    App.log().append("discarding unexpected image.\n");
                    img.close();
                }
                //if (app.getChosenAnalysis().Next(captureBuilder)) {
                App.log().append("Next: requesting new image capture\n");
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
            //LensShadingMap map = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP);
            App.log().append("capture complete with exposure " + exp + " sensitivity " + iso + "\n");
            //if (map != null) {
            //    int max = map.getGainFactorCount();
            //    log.append("shading map has dimension " + map.getColumnCount() + " by " + map.getRowCount() + " factors " + max + "\n");
            //    float g[] = new float[max];
            //    map.copyGainFactors(g,0);
            //    for (int i=0; i<max; i++){
            //        log.append("" + i + ":  " + g[i] + "\n");
            //    }
            //}
            super.onCaptureCompleted(session, request, result);
        }
    };



}

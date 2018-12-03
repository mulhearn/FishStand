package edu.ucdavis.crayfis.fishstand;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Settings;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import com.amazonaws.mobile.client.AWSMobileClient;
import com.amazonaws.mobile.client.Callback;
import com.amazonaws.mobile.client.UserStateDetails;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferObserver;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferService;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferUtility;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;

import java.io.File;
import java.io.FilenameFilter;
import java.util.concurrent.Semaphore;

public class UploadService extends Service {

    private static final String BUCKET_NAME = "fishstand-data";
    private static final String TAG = "UploadService";

    // notifications info
    private static final String CHANNEL_ID = "edu.ucdavis.crayfis.fishstand.UploadService";
    private static final String CHANNEL_NAME = "FishStand Uploads";
    private static final int FOREGROUND_ID = 102;

    private String deviceId;

    private Handler uploadHandler;
    private HandlerThread uploadThread;

    private TransferUtility transferUtility;
    private NotificationCompat.Builder notificationBuilder;

    private Semaphore uploadLock = new Semaphore(1);

    @Override
    public void onCreate() {
        // start upload service for S3
        startService(new Intent(this, TransferService.class));

        AWSMobileClient awsMobileClient = AWSMobileClient.getInstance();
        awsMobileClient.initialize(getApplicationContext(), new Callback<UserStateDetails>() {
            @Override
            public void onResult(UserStateDetails result) {
                Log.d(TAG, result.getUserState().toString());
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "Credentials error: " + e);
                e.printStackTrace();
            }
        });

        AmazonS3 s3 = new AmazonS3Client(awsMobileClient);
        transferUtility = TransferUtility.builder()
                .context(this)
                .awsConfiguration(awsMobileClient.getConfiguration())
                .s3Client(s3)
                .build();

        uploadThread = new HandlerThread("Uploads");
        uploadThread.start();
        uploadHandler = new Handler(uploadThread.getLooper());
    }

    @Override
    public void onDestroy() {
        uploadThread.quitSafely();
        try {
            uploadThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        stopService(new Intent(this, TransferService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        uploadHandler.post(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    File[] cache = Storage.listFiles(new FilenameFilter() {
                        @Override
                        public boolean accept(File file, String fname) {
                            return !fname.endsWith(".cfg")
                                    && !fname.endsWith(".mac");
                        }
                    }, true);

                    if (cache != null && cache.length > 0) {
                        if (upload(cache[0])) cache[0].delete();
                    } else {
                        break;
                    }
                }

                if (App.getAppState() != App.STATE.READY) {
                    uploadHandler.postDelayed(this, 60000L);
                } else {
                    stopSelf();
                }
            }
        });

        // now start running in foreground

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel notificationChannel
                    = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW);

            NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(notificationChannel);
        }

        notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("FishStand Uploads")
                .setTicker("FishStand Uploads")
                .setSmallIcon(R.drawable.ic_upload_icon)
                //.setLargeIcon(Bitmap.createScaledBitmap(icon, 128, 128, false))
                //.setContentIntent(pendingIntent)
                .setOngoing(true);

        startForeground(FOREGROUND_ID, notificationBuilder.build());

        return START_STICKY;
    }


    public IBinder onBind(Intent intent) {
        return null;
    }

    synchronized boolean upload(File f) {
        NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        manager.notify(FOREGROUND_ID, notificationBuilder
                .setContentText("Uploading " + f.getName())
                .build());

        String keyname = String.format("public/%s/%s", deviceId, f.getName());
        TransferObserver uploadObserver = transferUtility.upload(BUCKET_NAME, keyname, f);

        uploadObserver.setTransferListener(new TransferListener() {
            @Override
            public void onStateChanged(int id, TransferState state) {
                if (state == TransferState.COMPLETED
                        || state == TransferState.FAILED) {
                    uploadLock.release();
                }
            }

            @Override
            public void onProgressChanged(int id, long bytesCurrent, long bytesTotal) {
            }

            @Override
            public void onError(int id, Exception ex) {
                notifyAll();
            }
        });

        while (uploadObserver.getState() != TransferState.COMPLETED
                && uploadObserver.getState() != TransferState.FAILED) {
            try {
                uploadLock.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                e.printStackTrace();
            }
        }

        return uploadObserver.getState() == TransferState.COMPLETED;
    }
}

package edu.ucdavis.crayfis.fishstand;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

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
import java.net.URI;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class UploadService extends Service {

    private static final String BUCKET_NAME = "fishstand-data";
    private static final String TAG = "UploadService";

    public static final String EXTRA_POOL_SIZE = "extra_upload_pool_size";
    public static final String EXTRA_KEEP_ALIVE_TIME_MS = "extra_upload_keep_alive";

    private static final int DEFAULT_POOL_SIZE = 3;
    private static final long DEFAULT_KEEP_ALIVE_TIME_MS = 10000L;

    private static final long NOTIFICATION_UPDATE_INTERVAL = 1000L;

    // notifications info
    private static final String CHANNEL_ID = "edu.ucdavis.crayfis.fishstand.UploadService";
    private static final String CHANNEL_NAME = "FishStand Uploads";
    private static final int FOREGROUND_ID = 102;

    private TransferUtility transferUtility;

    private ThreadPoolExecutor uploadThreadPool;
    private final SparseArray<String> currentUploadNames = new SparseArray<>();
    private final SparseArray<Pair<Long, Long>> currentUploadStatuses = new SparseArray<>();

    private UploadBinder binder;

    private final Timer notificationTimer = new Timer();

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

    }

    @Override
    public void onDestroy() {

        uploadThreadPool.shutdown();
        stopService(new Intent(this, TransferService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        // check for a restart from START_STICKY
        if(intent == null) return START_STICKY;

        // start notification updates
        createUpdatingNotifications(notificationTimer, NOTIFICATION_UPDATE_INTERVAL);

        int poolSize = intent.getIntExtra(EXTRA_POOL_SIZE, DEFAULT_POOL_SIZE);
        long keepAliveTime = intent.getLongExtra(EXTRA_KEEP_ALIVE_TIME_MS, DEFAULT_KEEP_ALIVE_TIME_MS);

        uploadThreadPool = new ThreadPoolExecutor(poolSize, poolSize, keepAliveTime, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());

        // get old files in the uploads directory at this time
        File[] cache = Storage.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File file, String fname) {
                return !fname.endsWith(".cfg")
                        && !fname.endsWith(".mac")
                        && file.isFile();
            }
        }, true);


        for(final File f : cache)
            // upload each file, with EXTRA_POOL_SIZE jobs running concurrently
            uploadThreadPool.execute(new UploadTask(f));

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        if(binder == null) {
            binder = new UploadBinder();
        }
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        binder = null;
        return false;
    }


    private void createUpdatingNotifications(Timer timer, long interval) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel notificationChannel
                    = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW);

            NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(notificationChannel);
        }

        final NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("FishStand Uploads")
                .setTicker("FishStand Uploads")
                .setSmallIcon(R.drawable.ic_upload_icon)
                .setOngoing(true);

        TimerTask notificationUpdateTask = new TimerTask() {
            @Override
            public void run() {
                NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                StringBuilder content = new StringBuilder();
                for(int i=0; i<currentUploadNames.size(); i++) {
                    int key = currentUploadNames.keyAt(i);
                    String name = currentUploadNames.get(key);
                    Pair<Long, Long> status = currentUploadStatuses.get(key);
                    content.append(name);
                    if(status != null) {
                        content.append(" ")
                                .append(status.first)
                                .append("/")
                                .append(status.second)
                                .append("\n");
                    }
                }
                manager.notify(FOREGROUND_ID, notificationBuilder
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(content.toString()))
                        .build());
            }
        };

        timer.scheduleAtFixedRate(notificationUpdateTask, 0, interval);

        startForeground(FOREGROUND_ID, notificationBuilder.build());
    }

    private class UploadTask implements Runnable {

        private final File uploadFile;
        private final Semaphore uploadLock = new Semaphore(1);
        private final String deviceId
                = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        private UploadTask(File f) {
            uploadFile = f;
        }
        
        @Override
        public void run() {

            Log.d(TAG, uploadFile.getAbsolutePath());

            URI base = Storage.getPath(true).toURI();
            String relative = base.relativize(uploadFile.toURI()).getPath();
            String[] dirs = relative.split("/");

            String tag = dirs[0];
            String analysis = dirs[1];
            String filename = dirs[2];

            String keyname = String.format("public/%s/%s/%s/%s", tag, deviceId, analysis, filename);

            try {
                TransferObserver uploadObserver = transferUtility.upload(BUCKET_NAME, keyname, uploadFile);

                currentUploadNames.put(uploadObserver.getId(), uploadFile.getName());

                uploadObserver.setTransferListener(new TransferListener() {
                    @Override
                    public void onStateChanged(int id, TransferState state) {
                        if (state == TransferState.COMPLETED
                                || state == TransferState.FAILED) {
                            currentUploadNames.remove(id);
                            currentUploadStatuses.remove(id);
                            uploadLock.release();
                        }
                    }

                    @Override
                    public void onProgressChanged(int id, long bytesCurrent, long bytesTotal) {
                        currentUploadStatuses.put(id, Pair.create(bytesCurrent, bytesTotal));
                    }

                    @Override
                    public void onError(int id, Exception ex) {

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

                if (uploadObserver.getState() == TransferState.COMPLETED) uploadFile.delete();

            } catch (IllegalArgumentException e) {
                // TODO: already uploaded file?  not sure why this happens sometimes
                e.printStackTrace();
            }

            // stop if this is the last upload
            if(binder == null && uploadThreadPool.getActiveCount() <= 1) {
                Log.i(TAG, "Stopping UploadService.");

                notificationTimer.cancel();
                stopForeground(true);
                stopSelf();
            }
        }
    }

    public class UploadBinder extends Binder {
        private UploadBinder() { }

        public void uploadFile(File f) {
            Log.d(TAG, "Uploading " + f.getName());
            uploadThreadPool.execute(new UploadTask(f));
        }
    }
}

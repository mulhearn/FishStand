package edu.ucdavis.crayfis.fishstand;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

//
//  App:  Application wide access to singletons via static functions, e.g.:
//
//            Context context = App.getContext();
//


public class App extends Application implements Runnable, Storage.CallBack {
    @SuppressLint("StaticFieldLeak")
    private static App instance;

    private static final String TAG = "App";

    @Override
    public void onCreate(){
        super.onCreate();
        instance = this;
        context = getApplicationContext();
        message = new Message(context);
        pref = context.getSharedPreferences("MyPref", 0);
        if (!pref.contains("run_num")) {
            SharedPreferences.Editor editor = pref.edit();
            editor.putInt("run_num", 0);
            editor.apply();
        }
        logstr = new LogString();
        logstr.setUpdate(new Runnable(){public void run(){message.updateLog();};});
        camera = null;
        handler = null;

        goOffline();
    }
    // The singleton application context:
    private Context context;
    static public Context getContext(){ return instance.context; }


    // The storage interface, for Google Drive or local drive.
    private Storage storage;
    public static enum StorageType   {OFFLINE_STORAGE, ONLINE_STORAGE};
    public static enum StorageStatus {INITIALIZING, READY}
    StorageType storage_type;
    StorageStatus storage_status;

    public static StorageType getStorageType(){
        return instance.storage_type;
    }

    public static StorageStatus getStorageStatus(){
        return instance.storage_status;
    }

    static Storage getStorage(){
        return instance.storage;
    }

    public static void goOffline(){
        instance.storage_status = StorageStatus.INITIALIZING;
        instance.storage_type = StorageType.OFFLINE_STORAGE;
        instance.storage = new LocalDrive("FishStand");
        instance.storage_status = StorageStatus.READY;
        App.getMessage().updateStorage();
    }

    public static void goOnline(final Activity activity, final int availableRequestCode){
        instance.storage_status = StorageStatus.INITIALIZING;
        instance.storage_type = StorageType.ONLINE_STORAGE;
        App.getMessage().updateStorage();
        instance.storage = GoogleDrive.newGoogleDrive(activity, availableRequestCode, instance, "FishStand");
    }

    public void reportStorageReady(){
        storage_status = StorageStatus.READY;
        App.getMessage().updateStorage();
    }

    public void reportStorageFailure(String msg){
        Log.e(TAG, "Storage Failure detected.  Halting DAQ and going offline.");
        Log.e(TAG, "Message:  " + msg);
        getMessage().forceStop();
        goOffline();
    }

    private Message message;
    static public Message getMessage(){return instance.message; }

    private SharedPreferences pref;
    static public SharedPreferences getPref(){return instance.pref; }
    static public SharedPreferences.Editor getEdit(){return instance.pref.edit(); }

    private LogString logstr;
    static public LogString log(){ return instance.logstr; }

    private Handler handler;
    static public Handler getHandler(){
        if (instance.handler == null){
            new Thread(instance).start();
            while(instance.handler == null){}
        }
        return instance.handler;
    }

    private CameraConfig camera;
    static public CameraConfig getCamera(){
        if (instance.camera == null) {
            instance.camera = new CameraConfig();
            instance.camera.Init();
        }
        return instance.camera;
    }

    public void run(){
        Looper.prepare();
        handler = new Handler();
        Looper.loop();
    }

}



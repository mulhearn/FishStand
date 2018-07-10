package edu.ucdavis.crayfis.fishstand;

import android.annotation.SuppressLint;
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


public class App extends Application implements Runnable {
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
    public static enum StorageType {OFFLINE_STORAGE, ONLINE_STORAGE};
    StorageType storage_type;

    public static StorageType getStorageType(){
        return instance.storage_type;
    }

    static Storage getStorage(){
        instance.storage_type = StorageType.OFFLINE_STORAGE;
        return instance.storage;
    }
    public static void goOffline(){
        // for now we'll simply pretend to be online.
        instance.storage_type = StorageType.OFFLINE_STORAGE;
        instance.storage = new LocalDrive();
        App.getMessage().updateStorage();
    }

    public static void goOnline(){
        // eventunally should have initializing type, to handle waiting gracefully...
        //instance.storage_type = StorageType.INITIALIZING;

        Runnable r = new Runnable() {
            public void run() {
                // for now we'll simply pretend to be online.
                Storage storage = new LocalDrive();
                try {
                    instance.storage.Init();
                } catch (Storage.DriveException e){
                    Log.e(TAG, e.getMessage());
                    Log.e(TAG, "Failure to initiallize online storage, remaining offline.");
                }
                instance.storage = storage;
                instance.storage_type = StorageType.ONLINE_STORAGE;
                App.getMessage().updateStorage();
            }
        };
        (new Thread(r)).start();
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



package edu.ucdavis.crayfis.fishstand;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.renderscript.RenderScript;
import android.support.v4.content.LocalBroadcastManager;

//
//  App:  Application wide access to singletons via static functions, e.g.:
//
//            Context context = App.getContext();
//


public class App extends Application {
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
        logfile = new LogFile();
        logfile.setUpdate(new Runnable(){public void run(){message.updateLog();};});
        camera = null;
        config = null;
    }
    // The singleton application context:
    private Context context;
    static public Context getContext(){ return instance.context; }

    private Message message;
    static public Message getMessage(){return instance.message; }

    private SharedPreferences pref;
    static public SharedPreferences getPref(){return instance.pref; }
    static public SharedPreferences.Editor getEdit(){return instance.pref.edit(); }

    private LogFile logfile;
    static public LogFile log(){ return instance.logfile; }

    private RenderScript rs;
    public static RenderScript getRenderScript() {
        if (instance.rs == null) {
            instance.rs = RenderScript.create(instance);
        }
        return instance.rs;
    }

    private Camera camera;
    static public Camera getCamera(){
        if (instance.camera == null) {
            instance.camera = new Camera();
        }
        return instance.camera;
    }

    private Config config;
    static public Config getConfig(){
        if (instance.config == null) {
            instance.config = new Config();
        }
        return instance.config;
    }

    // State of the DAQ:
    public enum STATE {
        RUNNING,   // A run is underway
        STOPPING,  // A run stop has been requested, but worker threads may not have finishd yet
        CHARGING,  // Waiting for battery to recharge
        READY;     // Ready for a new run.
    }

    public static final String ACTION_STATE_CHANGE = "state_change";
    public static final String EXTRA_NEW_STATE = "new_state";
    private STATE state = STATE.READY;
    public static STATE getAppState() {
        return instance.state;
    }
    public static synchronized void updateState(STATE new_state) {
        instance.state = new_state;
        Intent intent = new Intent(ACTION_STATE_CHANGE);
        intent.putExtra(EXTRA_NEW_STATE, new_state);
        LocalBroadcastManager.getInstance(instance).sendBroadcast(intent);
        getMessage().updateState();
    }

}



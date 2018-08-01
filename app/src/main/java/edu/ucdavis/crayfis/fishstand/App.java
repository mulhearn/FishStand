package edu.ucdavis.crayfis.fishstand;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

//
//  App:  Application wide access to singletons via static functions, e.g.:
//
//            Context context = App.getContext();
//


public class App extends Application implements Runnable {
    @SuppressLint("StaticFieldLeak")
    private static App instance;

    private static final String TAG = "App";

    public static final String WORK_DIR = "FishStand";

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
        config = null;
        handler = null;
        storage = new LocalDrive(WORK_DIR);
    }
    // The singleton application context:
    private Context context;
    static public Context getContext(){ return instance.context; }

    // The storage interface:  DEPRECATED!
    private Storage storage;
    static Storage getStorage(){
        return instance.storage;
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

    private Camera camera;
    static public Camera getCamera(){
        if (instance.camera == null) {
            instance.camera = new Camera();
            instance.camera.Init();
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

    public void run(){
        Looper.prepare();
        handler = new Handler();
        Looper.loop();
    }

}



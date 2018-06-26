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

    @Override
    public void onCreate(){
        super.onCreate();
        instance = this;
        context = getApplicationContext();
        drive = new GoogleDrive();
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
    }

    private Context context;
    static public Context getContext(){ return instance.context; }

    private GoogleDrive drive;
    static public GoogleDrive getDrive(){ return instance.drive; }

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



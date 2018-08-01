package edu.ucdavis.crayfis.fishstand;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    // permissions request code:
    private static final int MY_PERMISSIONS_REQUEST = 100;

    // Activity request code for use in intializing online storage:
    private static final int AVAILABLE_REQUEST_CODE = 0;

    private BroadcastReceiver logUpdater;     // update log
    private BroadcastReceiver stateUpdater;   // update start/stop button based on DAQ state
    private BroadcastReceiver storageUpdater; // update file storage button based on storage type

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (!App.getPref().contains("run_num")) {
            SharedPreferences.Editor editor = App.getPref().edit();
            editor.putInt("run_num", 0);
            editor.commit();
        }

        // check and request missing permissions
        List<String> needed = new ArrayList<String>();
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA);
        }
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (needed.size() > 0) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[needed.size()]), MY_PERMISSIONS_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == MY_PERMISSIONS_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_DENIED) {
                Context context = getApplicationContext();
                CharSequence text = "Fishstand cannot Run without Camera and External Storage permission.  Exiting";
                int duration = Toast.LENGTH_LONG;
                Toast toast = Toast.makeText(context, text, duration);
                toast.show();
                finish();
            }
        }
    }


    @Override
    protected void onStart() {
        // call the superclass method first
        super.onStart();
        stateUpdater = App.getMessage().onStateUpdate(new Runnable() {
            public void run() {
                Button button = (Button) findViewById(R.id.button1);
                if (DaqService.state == DaqService.STATE.READY) {
                    button.setText("Start DAQ");
                } else {
                    button.setText("Stop DAQ");
                }
                TextView status = (TextView) findViewById(R.id.status1);
                SharedPreferences pref = getApplicationContext().getSharedPreferences("MyPref", 0);
                int run_num = pref.getInt("run_num", 1);
                if (DaqService.state == DaqService.STATE.RUNNING) {
                    status.setText("run " + run_num + " running");
                } else if (DaqService.state == DaqService.STATE.READY) {
                    status.setText("run " + run_num + " ready");
                } else {
                    status.setText("run " + run_num + " stopping...");
                }
            }
        });
        App.getMessage().updateState();

        storageUpdater = App.getMessage().onStorageUpdate(new Runnable() {
            public void run() {
                Button button = (Button) findViewById(R.id.button2);
                TextView status = (TextView) findViewById(R.id.status2);
                if (App.getStorageStatus() == App.StorageStatus.INITIALIZING){
                    button.setText("Go offline");
                    status.setText("Online storage is initializing...");
                } else {
                    if (App.getStorageType() == App.StorageType.OFFLINE_STORAGE) {
                        button.setText("Go online");
                        status.setText("Using offline file storage.");
                    } else {
                        button.setText("Go offline");
                        status.setText("Using online file storage.");
                    }
                }
            }
        });
        App.getMessage().updateStorage();



        logUpdater = App.getMessage().onLogUpdate(new Runnable() {
            public void run() {
                TextView logtxt = (TextView) findViewById(R.id.log_text);
                logtxt.setText(App.log().getTxt());
            }
        });
        App.getMessage().updateLog();
    }


    @Override
    protected void onStop() {
        super.onStop();
        App.getMessage().unregister(stateUpdater);
        App.getMessage().unregister(storageUpdater);
        App.getMessage().unregister(logUpdater);
    }

    public void buttonClicked(View v) {
        if (v == findViewById(R.id.button1)) {
            Intent service = new Intent(MainActivity.this, DaqService.class);
            if (DaqService.state == DaqService.STATE.READY) {
                Log.i(TAG, "button starting foreground action...");
                service.setAction(DaqService.ACTION.STARTFOREGROUND_ACTION);
            } else {
                Log.i(TAG, "button stopping foreground action...");
                service.setAction(DaqService.ACTION.STOPFOREGROUND_ACTION);
            }
            startService(service);
            return;
        }
        if (v == findViewById(R.id.button2)) {
            if (App.getStorageType() == App.StorageType.OFFLINE_STORAGE) {
                App.goOnline(this, AVAILABLE_REQUEST_CODE);
            } else {
                App.goOffline();
            }
            return;
        }
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        GoogleDrive.handleOnActivityResult(requestCode, resultCode, data);
    }

}
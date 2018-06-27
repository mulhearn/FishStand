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
    // activity codes:
    private static final int REQUEST_CODE_GOOGLE_SIGN_IN = 0;
    private static final int REQUEST_CODE_GOOGLE_INIT    = 1;
    // permissions request code:
    private static final int MY_PERMISSIONS_REQUEST = 100;


    private BroadcastReceiver stateUpdater; // update start/stop button based on DAQ state
    private BroadcastReceiver logUpdater;   // update log

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        App.getDrive().signIn(this, REQUEST_CODE_GOOGLE_SIGN_IN);

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
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case REQUEST_CODE_GOOGLE_SIGN_IN:
                if (resultCode == RESULT_OK) {
                    Log.i(TAG, "Google sign-in success.");
                    App.getDrive().initDrive();
                    break;
                } else {
                    Log.e(TAG, "Google sign-in failure.");
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
        App.getMessage().unregister(logUpdater);
    }

    public void buttonClicked(View v) {
        Log.i(TAG, "button pushed...");
        if (! App.getDrive().isInitialized()){
            Log.i(TAG, "Google Drive not initialized... refusing to start...");
            return;
        }

        Intent service = new Intent(MainActivity.this, DaqService.class);
        if (DaqService.state == DaqService.STATE.READY) {
            Log.i(TAG, "button starting foreground action...");
            service.setAction(DaqService.ACTION.STARTFOREGROUND_ACTION);
        } else {
            Log.i(TAG, "button stopping foreground action...");
            service.setAction(DaqService.ACTION.STOPFOREGROUND_ACTION);
        }
        startService(service);
    }
}
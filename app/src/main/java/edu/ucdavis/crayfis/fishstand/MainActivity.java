package edu.ucdavis.crayfis.fishstand;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    // permissions request code:
    private static final int MY_PERMISSIONS_REQUEST = 100;

    private BroadcastReceiver logUpdater;     // update log
    private BroadcastReceiver stateUpdater;   // update start/stop button based on DAQ state

    private Intent service;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (!App.getPref().contains("run_num")) {
            App.getPref().edit()
                    .putInt("run_num", 0)
                    .apply();
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

        service = new Intent(MainActivity.this, DaqService.class);
        startService(service);
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
                stopService(service);
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
                Button button = findViewById(R.id.start_stop_btn);
                TextView status = findViewById(R.id.status1);

                SharedPreferences pref = getApplicationContext().getSharedPreferences("MyPref", 0);
                int run_num = pref.getInt("run_num", 1);
                switch (App.getAppState()) {
                    case READY:
                        button.setEnabled(true);
                        button.setText("Start DAQ");
                        status.setText("run " + run_num + " ready");
                        break;
                    case RUNNING:
                        button.setEnabled(true);
                        button.setText("Stop DAQ");
                        status.setText("run " + run_num + " running");
                        break;
                    case STOPPING:
                        button.setEnabled(false);
                        status.setText("run " + run_num + " stopping...");
                        break;
                    case CHARGING:
                        button.setEnabled(true);
                        status.setText("waiting for battery to charge");
                }
            }
        });
        App.getMessage().updateState();

        TextView status = findViewById(R.id.status2);
        status.setText("unused status line...");

        logUpdater = App.getMessage().onLogUpdate(new Runnable() {
            public void run() {
                TextView logtxt = findViewById(R.id.log_text);
                logtxt.setText(App.log().getTxt());
            }
        });
        App.getMessage().updateLog();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        App.getMessage().unregister(stateUpdater);
        App.getMessage().unregister(logUpdater);
        if(App.getAppState() == App.STATE.READY) {
            stopService(service);
        }
    }

    public void onStartStopClicked(View v) {
        EditText text = findViewById(R.id.config_name);
        final String filename = text.getText().toString().trim();
        if(!filename.endsWith(".cfg") && !filename.endsWith(".mac")) {
            Toast.makeText(this, "Invalid file extension", Toast.LENGTH_LONG)
                    .show();
            return;
        }

        switch (App.getAppState()) {
            case READY:
                Log.i(TAG, "button starting run");
                App.updateState(App.STATE.RUNNING, filename);
                break;
            case RUNNING:
                Log.i(TAG, "button stopping run");
                App.updateState(App.STATE.STOPPING);
                break;
            case CHARGING:
                Log.i(TAG, "button stopping repeat");
                App.updateState(App.STATE.READY);
        }
    }

    public void onEditClicked(View v) {
        EditText text = findViewById(R.id.config_name);
        final String filename = text.getText().toString().trim();
        if(!filename.endsWith(".cfg") && !filename.endsWith(".mac")) {
            Toast.makeText(this, "Invalid file extension", Toast.LENGTH_LONG)
                    .show();
            return;
        }

        Config.editConfig(this, filename);
    }
}
package edu.ucdavis.crayfis.fishstand;

import android.content.Intent;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_CODE_GOOGLE_SIGN_IN = 0;
    private static final int REQUEST_CODE_GOOGLE_INIT    = 1;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        App.getDrive().signIn(this, REQUEST_CODE_GOOGLE_SIGN_IN);
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
}
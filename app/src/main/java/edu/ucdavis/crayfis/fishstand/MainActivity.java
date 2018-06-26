package edu.ucdavis.crayfis.fishstand;

import android.content.Intent;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.drive.Drive;
import com.google.android.gms.drive.DriveClient;
import com.google.android.gms.drive.DriveResourceClient;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_CODE_SIGN_IN = 0;

    private DriveClient mDriveClient;
    private DriveResourceClient mDriveResourceClient;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
	signIn();
    }

    // Start sign-in activity.
    private void signIn() {
	Log.i(TAG, "Start sign in");
	GoogleSignInClient GoogleSignInClient = buildGoogleSignInClient();
	startActivityForResult(GoogleSignInClient.getSignInIntent(), REQUEST_CODE_SIGN_IN);
    }

    // Build a Google SignIn client. */
    private GoogleSignInClient buildGoogleSignInClient() {
	GoogleSignInOptions signInOptions =
	    new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
	    .requestScopes(Drive.SCOPE_FILE)
	    .build();
	return GoogleSignIn.getClient(this, signInOptions);
    }


    @Override protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
	super.onActivityResult(requestCode, resultCode, data);
	switch (requestCode) {
	case REQUEST_CODE_SIGN_IN:
	    Log.i(TAG, "Sign in request code");
	    // Called after user is signed in.
	    if (resultCode == RESULT_OK) {
		Log.i(TAG, "Signed in successfully.");
		// Use the last signed in account here since it already have a Drive scope.
		mDriveClient = Drive.getDriveClient(this, GoogleSignIn.getLastSignedInAccount(this));
		// Build a drive resource client.
		mDriveResourceClient =
		    Drive.getDriveResourceClient(this, GoogleSignIn.getLastSignedInAccount(this));
	    }
	    break;
	}
    }
}
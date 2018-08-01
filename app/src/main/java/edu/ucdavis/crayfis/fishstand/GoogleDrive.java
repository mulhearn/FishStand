package edu.ucdavis.crayfis.fishstand;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.drive.DriveClient;
import com.google.android.gms.drive.DriveContents;
import com.google.android.gms.drive.DriveFile;
import com.google.android.gms.drive.DriveFolder;
import com.google.android.gms.drive.DriveId;
import com.google.android.gms.drive.Drive;
import com.google.android.gms.drive.DriveResourceClient;
import com.google.android.gms.drive.MetadataChangeSet;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static android.app.Activity.RESULT_OK;

//
// GoogleDrive:  wrapper for Google Drive API
//

public class GoogleDrive implements Storage {
    static GoogleDrive instance = null;

    private int googleSignInCode = -1;
    private CallBack callBack;
    private String work_dir = "";

    private boolean initialized = false;
    private DriveClient driveClient;
    private DriveResourceClient driveResourceClient;
    private static final String TAG = "GoogleDrive";

    private DriveContents config_contents;
    private DriveFile log_file;
    private DriveContents output_contents;

    // initialization:

    private GoogleDrive(){
    }

    static public GoogleDrive newGoogleDrive(final Activity activity, int availableCode, Storage.CallBack callBack, String work_dir){
        instance = new GoogleDrive();
        instance.googleSignInCode = availableCode;
        instance.callBack = callBack;
        instance.work_dir = work_dir;

        Log.i(TAG, "Signing in to Google");
        GoogleSignInOptions signInOptions =
          new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestScopes(com.google.android.gms.drive.Drive.SCOPE_FILE)
                    .build();
        GoogleSignInClient GoogleSignInClient = GoogleSignIn.getClient(activity, signInOptions);
        activity.startActivityForResult(GoogleSignInClient.getSignInIntent(), instance.googleSignInCode);
        Log.i(TAG, "Return from signing in to Google");
        return instance;
    }

    // call this within the supporting activity's onActivityResult to handle Google Drive Intent sign-in:
    static public void handleOnActivityResult(final int requestCode, final int resultCode, final Intent data) {
        if (requestCode != instance.googleSignInCode) return;
        if (resultCode == RESULT_OK) {
            Log.i(TAG, "Google sign-in success.");
            //handle the rest of the initialization in a background thread:
            Runnable r = new Runnable() {
             public void run() {
                    instance.init();
                }
            };
            (new Thread(r)).start();
        } else {
            Log.e(TAG, "Google sign-in failure.");
            instance.callBack.reportStorageFailure("Failed to sign in.");
        }
    }

    // config file:

    public InputStream getConfig() {
        retrieveConfigContents();
        return config_contents.getInputStream();
    }

    public void closeConfig() {
        Task<Void> commit = driveResourceClient.discardContents(config_contents);
        try {
            Tasks.await(commit, 30000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
            callBack.reportStorageFailure("could not release config contents to Google Drive.");
        }
    }

    // output files:

    public OutputStream newOutput(String filename, String mime_type) {
        createOutputContents(filename, mime_type);
        return output_contents.getOutputStream();
    }

    public void closeOutput() {
        Task<Void> commit = driveResourceClient.commitContents(output_contents, null);
        try {
            Tasks.await(commit, 30000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            output_contents = null;
            Log.e(TAG, e.getMessage());
            callBack.reportStorageFailure("could not release config contents to Google Drive.");
        }
    }

    // log file:

    public void newLog(int run_num) {

        String date = new SimpleDateFormat("hh:mm aaa yyyy-MMM-dd ", Locale.getDefault()).format(new Date());

        String filename = "log_" + run_num + ".txt";
        log_file = createFile(filename, "text/plain");
        String str = "Run " + run_num + " started on " + date + "\n";
        appendLog(str);

        Task<DriveContents> contents_task = driveResourceClient.openFile(log_file, DriveFile.MODE_WRITE_ONLY);
        try {
            DriveContents contents = Tasks.await(contents_task, 10, TimeUnit.SECONDS);
            OutputStream out = contents.getOutputStream();
            Writer writer = new OutputStreamWriter(out);
            writer.write("Run " + run_num + " started on " + date + "\n");
            writer.flush();
            Task<Void> commit = driveResourceClient.commitContents(contents, null);
            Tasks.await(commit, 10, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
            callBack.reportStorageFailure("failure opening log file");
        }
    }

    public void appendLog(String str) {
        Task<DriveContents> contents_task = driveResourceClient.openFile(log_file, DriveFile.MODE_READ_WRITE);
        try {
            DriveContents contents = Tasks.await(contents_task, 10, TimeUnit.SECONDS);

            ParcelFileDescriptor pfd = contents.getParcelFileDescriptor();
            long bytesToSkip = pfd.getStatSize();
            try (InputStream in = new FileInputStream(pfd.getFileDescriptor())) {
                // Skip to end of file
                while (bytesToSkip > 0) {
                    long skipped = in.skip(bytesToSkip);
                    bytesToSkip -= skipped;
                }
            }
            try (OutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
                out.write(str.getBytes());
            }
            Task<Void> commit = driveResourceClient.commitContents(contents, null);
            Tasks.await(commit, 10, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
            callBack.reportStorageFailure("failure appending log file");
        }
    }

    // private utility methods:

    private void retrieveConfigContents() {

        SharedPreferences pref = App.getPref();
        SharedPreferences.Editor editor = App.getEdit();
        String date = new SimpleDateFormat("hh:mm aaa yyyy-MMM-dd ", Locale.getDefault()).format(new Date());

        // Create an initial config file if it doesn't already exist:
        if (!pref.contains("config_id")) {
            DriveFile configfile = createFile("config.txt", "text/plain");
            editor.putString("config_id", configfile.getDriveId().encodeToString());
            editor.commit();

            Task<DriveContents> contents_task = driveResourceClient.openFile(configfile, DriveFile.MODE_WRITE_ONLY);
            try {
                DriveContents contents = Tasks.await(contents_task, 10, TimeUnit.SECONDS);
                OutputStream out = contents.getOutputStream();
                Writer writer = new OutputStreamWriter(out);
                writer.write("# Fishstand Run Configuration File\n");
                writer.write("# Created on Run " + date + "\n");
                writer.write("tag initial\n");
                writer.write("num 1\n");
                writer.write("repeat false\n");
                writer.write("analysis none\n");
                writer.write("delay 0\n");
                writer.flush();
                Task<Void> commit = driveResourceClient.commitContents(contents, null);
                Tasks.await(commit, 10, TimeUnit.SECONDS);
            } catch (Exception e) {
                Log.e(TAG, e.getMessage());
                callBack.reportStorageFailure("could not create initial config file");
            }
        }

        String str_id = "";
        str_id = pref.getString("config_id", "");
        //Log.i(TAG, "found working dir ID " + str_id);
        DriveFile configfile = DriveId.decodeFromString(str_id).asDriveFile();
        Task<DriveContents> contents_task = driveResourceClient.openFile(configfile, DriveFile.MODE_READ_ONLY);
        try {
            config_contents = Tasks.await(contents_task, 600, TimeUnit.SECONDS);
        } catch (Exception e) {
            config_contents = null;
            Log.e(TAG, e.getMessage());
            callBack.reportStorageFailure("could not open config file contents");
        }
    }

    private void createOutputContents(String filename, String mime_type) {
        DriveFile configfile = createFile(filename, mime_type);
        Task<DriveContents> contents_task = driveResourceClient.openFile(configfile, DriveFile.MODE_WRITE_ONLY);
        try {
            output_contents = Tasks.await(contents_task, 500, TimeUnit.MILLISECONDS);
            //Task<Void> commit = driveResourceClient.commitContents(output_contents, null);
            //Tasks.await(commit, 30000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            output_contents = null;
            Log.e(TAG, e.getMessage());
            callBack.reportStorageFailure("could not create initial config file");
        }
    }

    private void init(){
        driveClient = null;
        driveResourceClient = null;

        GoogleSignInAccount act = GoogleSignIn.getLastSignedInAccount(App.getContext());

        if (act != null) {
            driveClient = Drive.getDriveClient(App.getContext(), act);
            driveResourceClient = Drive.getDriveResourceClient(App.getContext(), act);
        }

        if ((driveClient != null)&&(driveResourceClient != null)){
            Task<Void> sync_task = driveClient.requestSync();
            try {
                Tasks.await(sync_task);
            } catch (Exception e){
                Log.i(TAG, "Google Drive sync request failed. Drive contents might be out of sync.");
                Log.i(TAG, "message:  " + e.getMessage());
            }
            initialized = true;
            Log.i(TAG, "Google Drive is now initialized.");
            callBack.reportStorageReady();
        } else {
            Log.e(TAG, "failure initializing Google Drive, reverting to offline storage.");
            App.goOffline();
        }
    }

    private DriveFile createFile(String filename, String mime_type) {
        try {
            MetadataChangeSet change_set = new MetadataChangeSet.Builder()
                    .setTitle(filename)
                    .setMimeType(mime_type)
                    .setStarred(false)
                    .build();
            DriveFolder workdir = getWorkingFolder();
            Task<DriveFile> task = driveResourceClient.createFile(workdir, change_set, null);
            DriveFile file = Tasks.await(task, 500, TimeUnit.MILLISECONDS);
            return file;
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
            callBack.reportStorageFailure("could not create file " + filename);
        }
        return null;
    }



    public boolean isInitialized(){return initialized; }
    public DriveClient getClient(){return driveClient; }
    public DriveResourceClient getResourceClient(){return driveResourceClient; }

    private DriveFolder getWorkingFolder() {

        SharedPreferences pref = App.getPref();
        SharedPreferences.Editor editor = App.getEdit();

        try {
            // Find or create the working directory
            if (!pref.contains("workdir_id")) {

                Task<DriveFolder> rootdir_task = driveResourceClient.getRootFolder();
                DriveFolder rootdir = Tasks.await(rootdir_task,500, TimeUnit.MILLISECONDS);

                MetadataChangeSet change_set = new MetadataChangeSet.Builder()
                        .setTitle(work_dir)
                        .setMimeType(DriveFolder.MIME_TYPE)
                        .setStarred(false)
                        .build();

                Task<DriveFolder> workdir_task = driveResourceClient.createFolder(rootdir, change_set);
                DriveFolder workdir = Tasks.await(workdir_task, 500, TimeUnit.MILLISECONDS);
                editor.putString("workdir_id", workdir.getDriveId().encodeToString());
                editor.commit();

                return workdir;
            } else {
                String str_id = "";
                str_id = pref.getString("workdir_id", "");
                //Log.i(TAG, "found working dir ID " + str_id);
                DriveFolder workdir = DriveId.decodeFromString(str_id).asDriveFolder();
                return workdir;
            }
        } catch (Exception e) {
            callBack.reportStorageFailure("could not retrieve working folder");
        }
        return null;
    }

}

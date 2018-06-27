package edu.ucdavis.crayfis.fishstand;

import android.app.Activity;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
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

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

//
// GoogleDrive:  wrapper for Google Drive API
//


public class GoogleDrive {
    private boolean initialized = false;
    private DriveClient driveClient;
    private DriveResourceClient driveResourceClient;
    private static final String TAG = "GoogleDrive";


    public boolean isInitialized(){return initialized; }
    public DriveClient getClient(){return driveClient; }
    public DriveResourceClient getResourceClient(){return driveResourceClient; }

    // Sign in to Google:
    public void signIn(Activity activity, int code) {
        Log.i(TAG, "Signing in to Google");
        GoogleSignInOptions signInOptions =
                new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                        .requestScopes(Drive.SCOPE_FILE)
                        .build();
        GoogleSignInClient GoogleSignInClient = GoogleSignIn.getClient(activity, signInOptions);
        activity.startActivityForResult(GoogleSignInClient.getSignInIntent(), code);
    }

    // Initialize Google Drive
    public void initDrive(){

        // handle the initialization in the background
        App.getHandler().post(
                new Runnable() {
                    public void run() {
                        driveClient = Drive.getDriveClient(App.getContext(), GoogleSignIn.getLastSignedInAccount(App.getContext()));
                        driveResourceClient = Drive.getDriveResourceClient(App.getContext(), GoogleSignIn.getLastSignedInAccount(App.getContext()));

                        if ((driveClient != null)&&(driveResourceClient != null)){
                            try {
                                Task<Void> sync_task = driveClient.requestSync();
                                Tasks.await(sync_task);
                            } catch (Exception e){
                                Log.e(TAG, "Google Drive not initialized");
                                Log.e(TAG, "message:  " + e.getMessage());
                                return;
                            }

                            Log.i(TAG, "Google Drive is now initialized.");
                            initialized = true;
                        }
                    }
                });
    }

    public class DriveException extends Exception {
        public DriveException(String msg){
            super(msg);
        }
    }

    public DriveFolder getWorkingFolder() throws DriveException {

        if (! initialized){ throw new DriveException("not initialized"); }

        SharedPreferences pref = App.getPref();
        SharedPreferences.Editor editor = App.getEdit();

        try {
            // Find or create the working directory
            if (!pref.contains("workdir_id")) {

                Task<DriveFolder> rootdir_task = driveResourceClient.getRootFolder();
                DriveFolder rootdir = Tasks.await(rootdir_task,500, TimeUnit.MILLISECONDS);

                MetadataChangeSet change_set = new MetadataChangeSet.Builder()
                        .setTitle("FishStandTest")
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
        } catch (ExecutionException e) {
            throw new DriveException("invalid state");
        } catch (InterruptedException e) {
            throw new DriveException("interrupted");
        } catch (TimeoutException e) {
            throw new DriveException("timeout");
        }
    }

    public DriveFile getConfigFile() throws DriveException {

        if (! initialized){ throw new DriveException("not initialized"); }

        SharedPreferences pref = App.getPref();
        SharedPreferences.Editor editor = App.getEdit();
        String date = new SimpleDateFormat("hh:mm aaa yyyy-MMM-dd ", Locale.getDefault()).format(new Date());

        try {
            // Find or create the config file
            if (!pref.contains("config_id")) {

                Task<DriveContents> contents_task = driveResourceClient.createContents();
                DriveContents contents = Tasks.await(contents_task, 500, TimeUnit.MILLISECONDS);

                OutputStream outputStream = contents.getOutputStream();
                Writer writer = new OutputStreamWriter(outputStream);
                writer.write("# Fishstand Run Configuration File\n");
                writer.write("# Created on Run " + date + "\n");
                writer.write("tag initial\n");
                writer.write("num 1\n");
                writer.write("repeat false\n");
                writer.write("analysis none\n");
                writer.flush();

                MetadataChangeSet change_set = new MetadataChangeSet.Builder()
                        .setTitle("config.txt")
                        .setMimeType("text/plain")
                        .setStarred(false)
                        .build();

                DriveFolder workdir = getWorkingFolder();
                Task<DriveFile> config_task = driveResourceClient.createFile(workdir, change_set, contents);
                DriveFile config = Tasks.await(config_task, 500, TimeUnit.MILLISECONDS);
                editor.putString("config_id", config.getDriveId().encodeToString());
                editor.commit();
                return config;
            } else {
                String str_id = "";
                str_id = pref.getString("config_id", "");
                //Log.i(TAG, "found working dir ID " + str_id);
                DriveFile config = DriveId.decodeFromString(str_id).asDriveFile();
                return config;
            }
        } catch (ExecutionException e) {
            Log.e(TAG, e.getMessage());
            throw new DriveException("invalid state");
        } catch (InterruptedException e) {
            Log.e(TAG, e.getMessage());
            throw new DriveException("interrupted");
        } catch (TimeoutException e) {
            Log.e(TAG, e.getMessage());
            throw new DriveException("timeout");
        } catch (IOException e) {
            Log.e(TAG, e.getMessage());
            throw new DriveException("io exception");
        }
    }

    public DriveFile createLogFile() throws DriveException {

        if (! initialized){ throw new DriveException("not initialized"); }

        SharedPreferences pref = App.getPref();
        int run_num = pref.getInt("run_num", 0);
        //SharedPreferences.Editor editor = pref.edit();
        String date = new SimpleDateFormat("hh:mm aaa yyyy-MMM-dd ", Locale.getDefault()).format(new Date());

        try {
            Task<DriveContents> contents_task = driveResourceClient.createContents();
            DriveContents contents = Tasks.await(contents_task, 500, TimeUnit.MILLISECONDS);

            OutputStream outputStream = contents.getOutputStream();
            Writer writer = new OutputStreamWriter(outputStream);
            writer.write("Run " + run_num + " started on " + date + "\n");
            writer.flush();

            MetadataChangeSet change_set = new MetadataChangeSet.Builder()
                    .setTitle("log_" + run_num + ".txt")
                    .setMimeType("text/plain")
                    .setStarred(false)
                    .build();
            DriveFolder workdir = getWorkingFolder();
            Task<DriveFile> log_task = driveResourceClient.createFile(workdir, change_set, contents);
            DriveFile file = Tasks.await(log_task, 500, TimeUnit.MILLISECONDS);

            return file;
        } catch (ExecutionException e) {
            throw new DriveException("invalid state");
        } catch (InterruptedException e) {
            throw new DriveException("interrupted");
        } catch (TimeoutException e) {
            throw new DriveException("timeout");
        } catch (IOException e) {
            throw new DriveException("io exception");
        }
    }




}

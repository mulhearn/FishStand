package edu.ucdavis.crayfis.fishstand;

import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class LocalDrive implements Storage {
    private static final String TAG = "LocalDrive";
    private String work_dir = "";
    private Writer log_writer = null;

    public LocalDrive(String work_dir){
        this.work_dir = work_dir;
    }

    public InputStream getConfig() {

        // editing of the config file is done with the iA Writer app, so config file is initially placed in
        // expected location for that application:
        //File path = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        //        "iA Writer");

        File path = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Sync/FishStand");

        path.mkdirs();
        final String filename = "config.txt";
        File config_file = new File(path, filename);

        if (! config_file.exists()) {
            String date = new SimpleDateFormat("hh:mm aaa yyyy-MMM-dd ", Locale.getDefault()).format(new Date());

            try {
                BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(config_file));
                Writer writer = new OutputStreamWriter(bos);
                writer.write("# config\n");
                writer.write("# Fishstand Run Configuration File\n");
                writer.write("# Created on Run " + date + "\n");
                writer.write("tag initial\n");
                writer.write("num 1\n");
                writer.write("repeat false\n");
                writer.write("analysis none\n");
                writer.write("delay 0\n");
                writer.flush();
                return getConfig();
            } catch (Exception e){
                Log.e(TAG, e.getMessage());
            }
        }
        try {
            return new FileInputStream(config_file);
        } catch (FileNotFoundException e){
            Log.e(TAG, e.getMessage());
        }
        return null;
    }

    public void closeConfig() {
        //nothing needed here for local storage...
    }

    public void newLog(int run_num) {
        String date = new SimpleDateFormat("hh:mm aaa yyyy-MMM-dd ", Locale.getDefault()).format(new Date());

        String filename = "log_" + run_num + ".txt";
        OutputStream out = newOutputFile(filename);
        log_writer = new OutputStreamWriter(out);
        try {
            log_writer.write("Run " + run_num + " started on " + date + "\n");
            log_writer.flush();
        } catch(IOException e){
            Log.e(TAG, e.getMessage());
        }
    }

    public void appendLog(String msg) {
        try {
            log_writer.write(msg);
            log_writer.flush();
        } catch(IOException e){
            Log.e(TAG, e.getMessage());
        }
    }

    public OutputStream newOutput(String filename, String mime_type) {
        return newOutputFile(filename);
    }

    public void closeOutput() {
        //nothing needed here for local storage...
    }

    private OutputStream newOutputFile(String filename) {
        File path = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                work_dir);
        path.mkdirs();

        File outfile = new File(path, filename);
        try {
            BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(outfile));
            return bos;
        } catch (FileNotFoundException e){
            Log.e(TAG, e.getMessage());
        }
        return null;
    }
}

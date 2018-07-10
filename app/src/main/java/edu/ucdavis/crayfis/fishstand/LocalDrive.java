package edu.ucdavis.crayfis.fishstand;


import android.content.SharedPreferences;
import android.graphics.Bitmap;
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

    public void Init() throws DriveException{
        // no initialization for offline storage...
    }

    public InputStream getConfig() throws DriveException{

        // editing of the config file is done with the iA Writer app, so config file is initially placed in
        // expected location for that application:
        File path = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "iA Writer");
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
                writer.flush();
                return getConfig();
            } catch (Exception e){
                Log.e(TAG, e.getMessage());
                throw new DriveException("Could not create config file.");
            }
        }
        try {
            return new FileInputStream(config_file);
        } catch (FileNotFoundException e){
            Log.e(TAG, e.getMessage());
            throw new DriveException("Could not open config file.");
        }
    }

    public OutputStream newLog() throws DriveException{
        SharedPreferences pref = App.getPref();
        int run_num = pref.getInt("run_num", 0);
        String date = new SimpleDateFormat("hh:mm aaa yyyy-MMM-dd ", Locale.getDefault()).format(new Date());

        String filename = "log_" + run_num + ".txt";
        OutputStream out = newOutputFile(filename);
        Writer writer = new OutputStreamWriter(out);
        try {
            writer.write("Run " + run_num + " started on " + date + "\n");
            writer.flush();
        } catch(IOException e){
            Log.e(TAG, e.getMessage());
            throw new DriveException("Could not write to log file.");
        }
        return out;
    }

    public void closeLog() throws DriveException{
        //nothing needed here for local storage...
    }

    public OutputStream newOutput(String suffix, String mime_type) throws DriveException {
        SharedPreferences pref = App.getPref();
        int run_num = pref.getInt("run_num", 0);

        String filename = "run_" + run_num + "_" + suffix;
        return newOutputFile(filename);
    }

    public void closeOutput() throws DriveException{
        //nothing needed here for local storage...
    }

    private OutputStream newOutputFile(String filename) throws DriveException {
        File path = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "FishStand");
        path.mkdirs();

        File outfile = new File(path, filename);
        try {
            BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(outfile));
            return bos;
        } catch (FileNotFoundException e){
            Log.e(TAG, e.getMessage());
            throw new DriveException("Could not create new output file.");
        }
    }


}

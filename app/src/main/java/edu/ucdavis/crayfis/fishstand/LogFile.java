package edu.ucdavis.crayfis.fishstand;

import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class LogFile {
    private static final String TAG = "Log";
    private Writer log_writer = null;

    private String logtxt = "";
    private Runnable update = new Runnable() {public void run() {} };

    public void newRun(int run_num, UploadService.UploadBinder binder) {
        logtxt = "";
        update.run();
        if(log_writer != null) {
            try {
                log_writer.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        String date = new SimpleDateFormat("hh:mm aaa yyyy-MMM-dd ", Locale.getDefault()).format(new Date());
        String filename = "run_" + run_num + ".log";
        OutputStream out = Storage.newOutput(filename, binder);
        if (out == null) return;
        log_writer = new OutputStreamWriter(out);

        try {
            log_writer.write("Run " + run_num + " started on " + date + "\n");
            log_writer.flush();
        } catch(IOException e){
            Log.e(TAG, e.getMessage());
        }
    }

    public void finishRun() {
        if(log_writer != null) {
            try {
                log_writer.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            log_writer = null;
        }
    }

    public LogFile append(String msg) {
        logtxt += msg;
        update.run();

        if (log_writer == null){
            return null;
        }
        try {
            log_writer.write(msg);
            log_writer.flush();
        } catch(IOException e){
            Log.e(TAG, e.getMessage());
        }
        return this;
    }

    public void setUpdate(Runnable update){
        this.update = update;
    }

    public String getTxt(){ return logtxt;}

}

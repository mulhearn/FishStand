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
    static final String TAG = "Log";
    private Writer log_writer = null;

    private String logtxt = "";

    public void newRun(int run_num) {
        logtxt = "";
        update.run();

        String date = new SimpleDateFormat("hh:mm aaa yyyy-MMM-dd ", Locale.getDefault()).format(new Date());
        String filename = "log_" + run_num + ".txt";
        OutputStream out = Storage.newOutput(filename);
        if (out == null){
            return;
        }
        log_writer = new OutputStreamWriter(out);

        try {
            log_writer.write("Run " + run_num + " started on " + date + "\n");
            log_writer.flush();
        } catch(IOException e){
            Log.e(TAG, e.getMessage());
        }
    }

    public void append(String msg) {
        logtxt = logtxt + msg;
        update.run();

        if (log_writer == null){
            return;
        }
        try {
            log_writer.write(msg);
            log_writer.flush();
        } catch(IOException e){
            Log.e(TAG, e.getMessage());
        }
    }

    private Runnable update = new Runnable() {public void run() {} };

    public void setUpdate(Runnable update){
        this.update = update;
    }

    public String getTxt(){ return logtxt;}

}

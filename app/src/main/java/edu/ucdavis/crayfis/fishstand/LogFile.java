package edu.ucdavis.crayfis.fishstand;

import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

public class LogFile {
    private static final String TAG = "Log";
    private Writer log_writer = null;

    private String logtxt = "";
    private Runnable update = new Runnable() {public void run() {} };

    private Timer updateTimer;

    private int part = 0;

    public void newRun(final int run_num, Config cfg, final UploadService.UploadBinder binder) {
        logtxt = "";
        part = 0;
        update.run();
        if(log_writer != null) {
            try {
                log_writer.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        final String jobTag = cfg.getString("tag", "unspecified");
        final String analysis = cfg.getString("analysis", null);

        String filename = (binder == null) ? "run_" + run_num + ".log"
                : "run_" + run_num + "_part_" + part + ".log";

        OutputStream out = Storage.newOutput(filename, jobTag, analysis, binder);
        if (out == null) return;
        log_writer = new OutputStreamWriter(out);

        // see if periodic updates should be uploaded
        final long updateTime = cfg.getLong("log_update_time", 0L);
        if(binder != null && updateTime > 0) {

            updateTimer = new Timer();
            updateTimer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    if(log_writer == null) {
                        // run is already over
                        updateTimer.cancel();
                        return;
                    }

                    append(App.getCamera().getStatus());
                    try {
                        // this should upload the previous file
                        log_writer.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    // start a new file
                    String filename = "run_" + run_num + "_part_" + ++part + ".log";
                    OutputStream out = Storage.newOutput(filename, jobTag, analysis, binder);
                    log_writer = new OutputStreamWriter(out);

                }
            }, updateTime, updateTime);
        }

        try {
            String date = new SimpleDateFormat("hh:mm aaa yyyy-MMM-dd ", Locale.getDefault()).format(new Date());
            log_writer.write("Run " + run_num + " started on " + date + "\n");
            log_writer.flush();
        } catch(IOException e){
            Log.e(TAG, e.getMessage());
        }
    }

    public void finishRun() {
        if(updateTimer != null) {
            updateTimer.cancel();
        }
        if(log_writer != null) {
            try {
                log_writer.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            log_writer = null;
        }
    }

    public LogFile append(String... messages) {
        Long appendTime = System.currentTimeMillis();

        StringBuilder sb = new StringBuilder();
        for(String msg: messages) {
            sb.append(msg + "\n");
        }
        logtxt += sb.toString();
        update.run();

        if (log_writer == null){
            return null;
        }
        try {
            boolean first = true;
            for(String msg: messages) {
                if(first) {
                    log_writer.write(appendTime + ": ");
                    first = false;
                } else {
                    log_writer.write("             : ");
                }
                log_writer.write(msg + "\n");
            }
            log_writer.flush();
        } catch(IOException e){
            Log.e(TAG, e.getMessage());
        }
        return this;
    }

    public LogFile append(Iterable<String> messages) {
        Long appendTime = System.currentTimeMillis();

        StringBuilder sb = new StringBuilder();
        for(String msg: messages) {
            sb.append(msg + "\n");
        }
        logtxt += sb.toString();
        update.run();

        if (log_writer == null){
            return null;
        }
        try {
            boolean first = true;
            for(String msg: messages) {
                if(first) {
                    log_writer.write(appendTime + ": ");
                    first = false;
                } else {
                    log_writer.write("             : ");
                }
                log_writer.write(msg + "\n");
            }
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

package edu.ucdavis.crayfis.fishstand.analysis;

import android.hardware.camera2.CaptureResult;
import android.os.SystemClock;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.Type;
import android.support.annotation.Nullable;
import android.util.Log;

import java.io.DataOutputStream;
import java.io.OutputStream;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import edu.ucdavis.crayfis.fishstand.App;
import edu.ucdavis.crayfis.fishstand.Calib;
import edu.ucdavis.crayfis.fishstand.Config;
import edu.ucdavis.crayfis.fishstand.UploadService;
import edu.ucdavis.crayfis.fishstand.camera.Frame;
import edu.ucdavis.crayfis.fishstand.ScriptC_cosmics;
import edu.ucdavis.crayfis.fishstand.Storage;

public class Cosmics implements Analysis {
    private static final String TAG = "Cosmics";
    public static final String NAME = "cosmics";

    private AtomicInteger images = new AtomicInteger();

    private Random rand;

    private UploadService.UploadBinder binder;
    private int gzip;

    // parameters:
    private int region_dx;
    private int region_dy;
    private int region_size;
    private int num_thresh;
    private int max_pixel;
    private int num_zerobias;
    private int raw_thresh;
    private int run_num;
    private final boolean yuv_mode;
    private int threshold[];
    private int prescale[];
    private int width;
    private int height;
    private int images_per_file;
    private int hist_prescale;
    private int hot_hash;
    private int wgt_hash;

    // Renderscript and Allocations
    private final ScriptC_cosmics script;
    // - weights are only read by script:
    private final Allocation weights_alloc;
    // - output histograms
    private final Allocation hist_uncal_alloc;
    private final Allocation hist_unhot_alloc;
    private final Allocation hist_calib_alloc;
    // - output results
    private final Allocation pixel_output_alloc;
    private short pixel_output[];

    // Locks for script allocations and output file writing:
    private final ReentrantLock script_lock = new ReentrantLock();
    private final ReentrantLock output_lock = new ReentrantLock();

    // output files:
    private int out_part;
    private int out_image;
    private DataOutputStream output;

    private final String jobTag;

    public Cosmics(Config cfg, @Nullable UploadService.UploadBinder binder) {

        gzip = cfg.getInteger("gzip", 0);
        this.binder = binder;

        rand = new Random();

        run_num = App.getPref().getInt("run_num", 0);
        width = App.getCamera().getResX();
        height = App.getCamera().getResY();

        yuv_mode = cfg.getBoolean("yuv", false);
        final int max_hist = yuv_mode ? 255 : 1023;

        images_per_file = cfg.getInteger("images_per_file", 1000);
        hist_prescale = cfg.getInteger("hist_prescale", 10);

        raw_thresh   = cfg.getInteger("raw_thresh", 10);
        max_pixel =  cfg.getInteger("max_pixel", 100);
        num_zerobias =  cfg.getInteger("num_zerobias", 10);
        int threshold_default[] = {10};
        int prescale_default[] = {100};
        threshold = cfg.getIntegerArray("threshold", threshold_default);
        prescale = cfg.getIntegerArray("prescale", prescale_default);
        num_thresh = threshold.length;

        String[] logMessages = new String[num_thresh];
        for (int i=0; i<num_thresh; i++){
            logMessages[i] = "threshold " + i + " value: " + threshold[i] + " prescale:  " + prescale[i];
        }
        App.log().append(logMessages);

        region_dx = cfg.getInteger("region_dx", 2);
        region_dy = cfg.getInteger("region_dy", 2);
        region_size = 3+(2*region_dx+1)*(2*region_dy+1); // x and y coordinates of region, plus the region data

        RenderScript rs = App.getRenderScript();
        script = new ScriptC_cosmics(rs);
        script.invoke_set_parameters(max_hist, max_pixel,raw_thresh,Calib.DENOM);
        for (int i=0; i<threshold.length; i++) {
            script.invoke_add_threshold(threshold[i], prescale[i]);
        }
        hist_uncal_alloc = Allocation.createSized(rs, Element.U64(rs), max_hist +1, Allocation.USAGE_SCRIPT);
        hist_unhot_alloc = Allocation.createSized(rs, Element.U64(rs), max_hist +1, Allocation.USAGE_SCRIPT);
        hist_calib_alloc = Allocation.createSized(rs, Element.U64(rs), max_hist +1, Allocation.USAGE_SCRIPT);
        pixel_output_alloc = Allocation.createSized(rs, Element.U16(rs), 3*max_pixel+1, Allocation.USAGE_SCRIPT);
        pixel_output = new short[3*max_pixel+1];
        weights_alloc = Allocation.createTyped(rs, new Type.Builder(rs, Element.U16(rs))
                .setX(width)
                .setY(height)
                .create());
        Calib calib = new Calib(width,height);
        hot_hash = calib.getHotHash();
        wgt_hash = calib.getWgtHash();
        weights_alloc.copyFromUnchecked(calib.getCombinedWeights());
        script.bind_hist_uncal(hist_uncal_alloc);
        script.bind_hist_unhot(hist_unhot_alloc);
        script.bind_hist_calib(hist_calib_alloc);
        script.bind_pixel_output(pixel_output_alloc);

        out_part = 0;
        out_image = 0;
        output = null;
        jobTag = cfg.getString("tag", "unspecified");
    }

    public void ProcessFrame(Frame frame) {
        int image_num = images.incrementAndGet();

        Allocation buf = frame.getAllocation();

        script_lock.lock();
        script.invoke_start();
        if ((hist_prescale > 0)&&(image_num%hist_prescale == 0)){
            if(yuv_mode)  script.forEach_histogram_uchar(buf, weights_alloc);
            else     script.forEach_histogram_ushort(buf, weights_alloc);
        }

        if(yuv_mode)  script.forEach_process_uchar(buf, weights_alloc);
        else     script.forEach_process_ushort(buf, weights_alloc);
        script.invoke_finish();

        pixel_output_alloc.copyTo(pixel_output);
        int num_trigger = pixel_output[0];
        int dropped = 0;
        if (num_trigger > max_pixel){
            dropped = num_trigger - max_pixel;
            num_trigger = max_pixel;
        }
        script_lock.unlock();

        // now figure out how much space we need including zero-bias:
        int num_region = num_trigger + num_zerobias;

        long timestamp = frame.getTotalCaptureResult().get(CaptureResult.SENSOR_TIMESTAMP);
        short[] region_buf = new short[region_size * num_region];
        for (int i=0; i<num_trigger; i++){
            short px = region_buf[i*region_size]    = pixel_output[3*i+1]; // px
            short py = region_buf[i*region_size+1]  = pixel_output[3*i+2]; // py
            region_buf[i*region_size+2]  = pixel_output[3*i+3]; // highest
            frame.copyRegion(px, py, region_dx, region_dy, region_buf, i*region_size+3);
        }

        for (int i=num_trigger; i<num_region; i++){
            int px = rand.nextInt(width);
            int py = rand.nextInt(height);
            region_buf[i*region_size]    = (short) px;
            region_buf[i*region_size+1]  = (short) py;
            region_buf[i*region_size+2]  = (short) 0;
            frame.copyRegion(px, py, region_dx, region_dy, region_buf, i*region_size+3);
        }

        // we are done with the image allocation owned by the frame, so we can close the frame:
        frame.close();

        UpdateOutput(num_region, dropped, timestamp, region_buf);
    }

    private void UpdateOutput(int num_region, int dropped, long timestamp, short[] region_buf) {
        output_lock.lock();

        // close file after enough regions...
        // output = null;

        if (output == null) {
            String filename = "run_" + run_num + "_cosmics_part_" + out_part + ".dat";
            App.log().append("starting new output file " + filename);
            OutputStream out = Storage.newOutput(filename, jobTag, "cosmics", gzip, binder);
            output = new DataOutputStream(out);
            final int HEADER_SIZE = 11;
            final int VERSION = 1;
            try {
                output.writeInt(HEADER_SIZE);
                output.writeInt(VERSION);
                //additional header items (should match above size!)
                output.writeInt(App.getCamera().getResX());
                output.writeInt(App.getCamera().getResY());
                output.writeInt(App.getCamera().getISO());
                output.writeInt((int) App.getCamera().getExposure());
                output.writeInt(hot_hash);
                output.writeInt(wgt_hash);
                output.writeInt(region_dx);
                output.writeInt(region_dy);
                output.writeInt(Calib.DENOM);
                output.writeInt(num_zerobias);
                output.writeInt(num_thresh);
                for (int i=0; i<num_thresh; i++){
                    output.writeInt(threshold[i]);
                }
                for (int i=0; i<num_thresh; i++){
                    output.writeInt(prescale[i]);
                }
            } catch (java.io.IOException e) {
                output = null;
                output_lock.unlock();
                return;
            }
        }

        try {
            if (timestamp == 0){
                timestamp = 1;
            }
            output.writeLong(timestamp);
            // timestamp is elapsed nanoseconds since boot, convert to ms since epoch:
            long millistamp = System.currentTimeMillis() + (timestamp - SystemClock.elapsedRealtimeNanos())/1000000L;
            output.writeLong(millistamp);
            output.writeInt(num_region);
            output.writeInt(dropped);
            for (short x: region_buf)
                output.writeShort(x);
        } catch (java.io.IOException e) {
            output = null;
            output_lock.unlock();
            return;
        }

        out_image++;
        if (out_image >= images_per_file){
            CloseOutput();
            out_image = 0;
            out_part++;
        }

        output_lock.unlock();
    }
    private void CloseOutput(){
        if (output != null) {
            output_lock.lock();
            try {
                output.writeLong(0);  // mark end of file
                output.flush();
                output.close();
            } catch (java.io.IOException e) {
                output = null;
                output_lock.unlock();
                return;
            }
            output_lock.unlock();
        }
    }


    public void ProcessRun() {

        App.log().append("Cosmics run ending...");

        CloseOutput();

        int size = hist_uncal_alloc.getBytesSize() / 8;
        long[] hist_uncal_copy = new long[size];
        long[] hist_unhot_copy = new long[size];
        long[] hist_calib_copy = new long[size];

        script_lock.lock();
        hist_uncal_alloc.copyTo(hist_uncal_copy);
        hist_unhot_alloc.copyTo(hist_unhot_copy);
        hist_calib_alloc.copyTo(hist_calib_copy);
        script_lock.unlock();

        try {
            int run_num = App.getPref().getInt("run_num", 0);

            final int HEADER_SIZE = 7;
            final int VERSION = 1;

            String filename = "run_" + run_num + "_cosmics_hist.dat";
            App.log().append("writing file " + filename);
            OutputStream out = Storage.newOutput(filename, jobTag, NAME, gzip, binder);
            DataOutputStream writer = new DataOutputStream(out);
            writer.writeInt(HEADER_SIZE);
            writer.writeInt(VERSION);
            //additional header items (should match above size!)
            writer.writeInt(images.get());
            writer.writeInt(App.getCamera().getResX());
            writer.writeInt(App.getCamera().getResY());
            writer.writeInt(App.getCamera().getISO());
            writer.writeInt((int) App.getCamera().getExposure());
            writer.writeInt(hist_prescale);
            writer.writeInt(hist_uncal_copy.length);
            for (long bin: hist_uncal_copy) {
                writer.writeLong(bin);
            }
            for (long bin: hist_unhot_copy) {
                writer.writeLong(bin);
            }
            for (long bin: hist_calib_copy) {
                writer.writeLong(bin);
            }
            writer.close();
        } catch(Exception e) {
            Log.e(TAG, e.getMessage());
            Log.e(TAG, "Failed to save results.");
            return;
        }
        App.log().append("all output files have been written.");
    }
}
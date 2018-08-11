package edu.ucdavis.crayfis.fishstand;

import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.Type;
import android.util.Log;

import java.io.DataOutputStream;
import java.io.OutputStream;
import java.nio.ShortBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import io.crayfis.android.ScriptC_pixelstats;

public class PixelStats implements Analysis {
    private static final String TAG = "PixelStats";

    private static final int NUM_FILES = 0;

    private AtomicInteger images = new AtomicInteger();
    private int num_pixels;

    // FIXME: what do with these when we aren't cycling?
    private long run_exposure = 0;
    private int run_sensitivity = 0;

    private Allocation abuf;
    private Allocation sum;
    private Allocation ssq;
    private ScriptC_pixelstats script;

    private int first = 0;
    private static final boolean CYCLE = false;
    private long[] exposures     = {500000, 5000000, 50000000, 500000000};
    private int[] sensitivities = {640};

    public void Init() {
        int run_offset = App.getPref().getInt("run_num", 0);
        if(first == 0) first = run_offset;

        if (CYCLE) {
            int iset = run_offset - first;
            run_exposure    = exposures[iset % exposures.length];
            run_sensitivity = sensitivities[iset % sensitivities.length];
        }

	    int nx = App.getCamera().raw_size.getWidth();
        int ny = App.getCamera().raw_size.getHeight();
        num_pixels = nx * ny;

        App.log().append("num_pixels:   " + num_pixels + "\n")
                .append("allocating memory, please be patient...\n");

        Type type16 = new Type.Builder(App.getRenderScript(), Element.U16(App.getRenderScript()))
                .setX(nx)
                .setY(ny)
                .create();

        Type type32 = new Type.Builder(App.getRenderScript(), Element.U32(App.getRenderScript()))
                .setX(nx)
                .setY(ny)
                .create();

        Type type64 = new Type.Builder(App.getRenderScript(), Element.U64(App.getRenderScript()))
                .setX(nx)
                .setY(ny)
                .create();

        abuf = Allocation.createTyped(App.getRenderScript(), type16);
        sum = Allocation.createTyped(App.getRenderScript(), type32);
        ssq = Allocation.createTyped(App.getRenderScript(), type64);

        script = new ScriptC_pixelstats(App.getRenderScript());
        script.set_g_sum(sum);
        script.set_g_ssq(ssq);

        App.log().append("finished allocating memory.\n");
    }

    public void Next(CaptureRequest.Builder request) {
        if(CYCLE) {
            request.set(CaptureRequest.SENSOR_EXPOSURE_TIME, run_exposure);
            request.set(CaptureRequest.SENSOR_SENSITIVITY, run_sensitivity);
        }
    }

    public void ProcessImage(Image img) {
        images.incrementAndGet();

        Image.Plane iplane = img.getPlanes()[0];
        ShortBuffer buf = iplane.getBuffer().asShortBuffer();

        final short[] vals;
        if(buf.hasArray()) {
            vals = buf.array();
        } else {
            vals = new short[buf.capacity()];
            buf.get(vals);
            buf.rewind();
        }

        synchronized (abuf) {
            abuf.copyFromUnchecked(vals);
            script.forEach_add(abuf);
        }
    }

    public void ProcessRun() {
        abuf.destroy();

        int[] sum_buf = new int[num_pixels];
        sum.copyTo(sum_buf);
        sum.destroy();

        long[] ssq_buf = new long[num_pixels];
        ssq.copyTo(ssq_buf);
        ssq.destroy();

        if (NUM_FILES > 0) {
            WriteOutput(sum_buf, ssq_buf);
        }
        long outer_sum = 0;
        for (int ipix=0; ipix<num_pixels;ipix++){
            outer_sum += sum_buf[ipix];
        }
        double avg = 0;
        double denom = (double) num_pixels * images.intValue();
        Log.d(TAG, "Total images: " + images.intValue());
        Log.d(TAG, "avg = " + outer_sum + " / " + denom);
        try {
            avg = outer_sum / (double) num_pixels / images.intValue();
        } catch (ArithmeticException e) {
            avg = 0;
        }
        App.log().append("run exposure:     " + run_exposure + "\n")
                .append("run sensitivity:  " + run_sensitivity + "\n")
                .append("mean pixel value:  " + avg + "\n");
    }


    private void WriteOutput(int[] sum_buf, long[] ssq_buf) {
        try {
            int run_num = App.getPref().getInt("run_num", 0);

            int pixels_per_file = num_pixels / NUM_FILES;
            if (num_pixels % NUM_FILES != 0){
                pixels_per_file = pixels_per_file + 1;
            }

            for (int ifile = 0; ifile < NUM_FILES; ifile++) {
                int pixel_start = pixels_per_file * ifile;
                int pixel_end   = pixel_start + pixels_per_file;
                if (pixel_end > num_pixels){
                    pixel_end = num_pixels;
                }

                String filename = "run_" + run_num + "_part_" + ifile + "_pixelstats.dat";
                App.log().append("writing file " + filename + "\n");
                OutputStream out = Storage.newOutput(filename);
                DataOutputStream writer = new DataOutputStream(out);
                final int HEADER_SIZE = 12;
                final int VERSION = 1;
                writer.writeInt(HEADER_SIZE);
                writer.writeInt(VERSION);
                //additional header items (should match above size!)
                writer.writeInt(images.intValue());
                writer.writeInt(NUM_FILES);
                writer.writeInt(ifile);
                writer.writeInt(App.getCamera().raw_size.getWidth());
                writer.writeInt(App.getCamera().raw_size.getHeight());
                writer.writeInt(run_sensitivity);
                writer.writeInt((int) (run_exposure/1000));
                writer.writeInt(pixel_start);
                writer.writeInt(pixel_end);

                // payload
                for (int i = pixel_start; i < pixel_end; i++) {
                    writer.writeInt(sum_buf[i]);
                }

                for (int i = pixel_start; i < pixel_end; i++) {
                    writer.writeLong(ssq_buf[i]);
                }
                writer.flush();
            }
        } catch(Exception e) {
            Log.e(TAG, e.getMessage());
            Log.e(TAG, "Failed to save results.");
            return;
        }
        App.log().append("all output files have been written.\n");
    }
}
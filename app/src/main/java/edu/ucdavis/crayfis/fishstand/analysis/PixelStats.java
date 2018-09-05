package edu.ucdavis.crayfis.fishstand.analysis;

import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.Type;
import android.util.Log;

import java.io.DataOutputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import edu.ucdavis.crayfis.fishstand.App;
import edu.ucdavis.crayfis.fishstand.Config;
import edu.ucdavis.crayfis.fishstand.camera.Frame;
import edu.ucdavis.crayfis.fishstand.ScriptC_pixelstats;
import edu.ucdavis.crayfis.fishstand.Storage;

public class PixelStats implements Analysis {
    private static final String TAG = "PixelStats";

    private AtomicInteger images = new AtomicInteger();
    private int num_pixels;

    private Allocation sum;
    private Allocation ssq;
    private Allocation max;
    private Allocation second;
    private ScriptC_pixelstats script;

    private final boolean YUV;
    private final long FILE_SIZE;
    private final int DOWNSAMPLE_STEP;

    private static final ReentrantLock script_lock = new ReentrantLock();

    public PixelStats(Config cfg) {

        YUV = cfg.getBoolean("yuv", false);
        FILE_SIZE = cfg.getLong("filesize", 5000000);
        DOWNSAMPLE_STEP = cfg.getInteger("samplefile", 171);

	    int nx = App.getCamera().getResX();
        int ny = App.getCamera().getResY();
        num_pixels = nx * ny;

        App.log().append("num_pixels:   " + num_pixels + "\n")
                .append("allocating memory, please be patient...\n");

        RenderScript rs = App.getRenderScript();

        Type type16 = new Type.Builder(rs, Element.U16(rs))
                .setX(nx)
                .setY(ny)
                .create();

        Type type32 = new Type.Builder(rs, Element.U32(rs))
                .setX(nx)
                .setY(ny)
                .create();

        Type type64 = new Type.Builder(rs, Element.U64(rs))
                .setX(nx)
                .setY(ny)
                .create();

        sum = Allocation.createTyped(rs, type32);
        ssq = Allocation.createTyped(rs, type64);
        max = Allocation.createTyped(rs, type16);
        second = Allocation.createTyped(rs, type16);

        script = new ScriptC_pixelstats(App.getRenderScript());
        script.set_g_sum(sum);
        script.set_g_ssq(ssq);
        script.set_g_max(max);
        script.set_g_second(second);

        App.log().append("finished allocating memory.\n");
    }

    public void ProcessFrame(Frame frame) {
        images.incrementAndGet();

        Allocation buf = frame.getAllocation();

        script_lock.lock();
        if (YUV) script.forEach_add_YUV(buf);
        else script.forEach_add_RAW(buf);

        script_lock.unlock();
        frame.close();
    }

    public void ProcessRun() {

        final int[] sum_buf;
        final long[] ssq_buf;
        final short[] max_buf;
        final short[] second_buf;

        synchronized (this) {
            sum_buf = new int[num_pixels];
            sum.copyTo(sum_buf);
            sum.destroy();

            ssq_buf = new long[num_pixels];
            ssq.copyTo(ssq_buf);
            ssq.destroy();

            max_buf = new short[num_pixels];
            max.copyTo(max_buf);
            max.destroy();

            second_buf = new short[num_pixels];
            second.copyTo(second_buf);
            second.destroy();
        }

        if (FILE_SIZE > 0) {
            WriteOutput(sum_buf, ssq_buf, max_buf, second_buf, images.intValue());
        }
        long outer_sum = 0;
        for (int ipix=0; ipix<num_pixels;ipix++){
            outer_sum += sum_buf[ipix];
        }
        double avg;
        try {
            avg = outer_sum / (double) num_pixels / images.intValue();
        } catch (ArithmeticException e) {
            avg = -1;
        }
        App.log().append("run exposure:     " + App.getCamera().getExposure() + "\n")
                .append("run sensitivity:  " + App.getCamera().getISO() + "\n")
                .append("mean pixel value:  " + avg + "\n");
    }


    private void WriteOutput(int[] sum_buf, long[] ssq_buf, short[] max_buf, short[] second_buf, int num_images) {
        try {
            int run_num = App.getPref().getInt("run_num", 0);

            // int (sum) + long (ssq) + short (max) + short (second) = 16 bytes
            int pixels_per_file = (int) (FILE_SIZE / 16);
            int num_pixels = sum_buf.length;
            int num_files = num_pixels / pixels_per_file;
            if(num_pixels % pixels_per_file != 0) {
                num_files++;
            }

            final int HEADER_SIZE = 10;
            final int VERSION = 1;

            for (int ifile = 0; ifile < num_files; ifile++) {
                int pixel_start = pixels_per_file * ifile;
                int pixel_end   = pixel_start + pixels_per_file;
                if (pixel_end > num_pixels){
                    pixel_end = num_pixels;
                }

                String filename = "run_" + run_num + "_part_" + ifile + "_pixelstats.dat";
                App.log().append("writing file " + filename + "\n");
                OutputStream out = Storage.newOutput(filename);
                DataOutputStream writer = new DataOutputStream(out);
                writer.writeInt(HEADER_SIZE);
                writer.writeInt(VERSION);
                //additional header items (should match above size!)
                writer.writeInt(num_images);
                writer.writeInt(num_files);
                writer.writeInt(ifile);
                writer.writeInt(App.getCamera().getResX());
                writer.writeInt(App.getCamera().getResY());
                writer.writeInt(App.getCamera().getISO());
                writer.writeInt((int) App.getCamera().getExposure());
                writer.writeInt(pixel_start);
                writer.writeInt(pixel_end);
                writer.writeInt(1); // downsample step

                // payload
                for (int i = pixel_start; i < pixel_end; i++) {
                    writer.writeInt(sum_buf[i]);
                }

                for (int i = pixel_start; i < pixel_end; i++) {
                    writer.writeLong(ssq_buf[i]);
                }

                for (int i=pixel_start; i < pixel_end; i++) {
                    writer.writeShort(max_buf[i]);
                }

                for (int i=pixel_start; i < pixel_end; i++) {
                    writer.writeShort(second_buf[i]);
                }
                writer.flush();
                writer.close();
                out.close();
            }

            // add an extra downsampled file
            if (DOWNSAMPLE_STEP > 0) {
                String filename = "run_" + run_num + "_sample" + "_pixelstats.dat";
                App.log().append("writing file " + filename + "\n");
                OutputStream out = Storage.newOutput(filename);
                DataOutputStream writer = new DataOutputStream(out);
                writer.writeInt(HEADER_SIZE);
                writer.writeInt(VERSION);
                //additional header items (should match above size!)
                writer.writeInt(num_images);
                writer.writeInt(num_files);
                writer.writeInt(-1); // ifile
                writer.writeInt(App.getCamera().getResX());
                writer.writeInt(App.getCamera().getResY());
                writer.writeInt(App.getCamera().getISO());
                writer.writeInt((int) App.getCamera().getExposure());
                writer.writeInt(0); // pixel_start
                writer.writeInt((num_pixels-1)/DOWNSAMPLE_STEP + 1); // pixel_end
                writer.writeInt(DOWNSAMPLE_STEP);

                for (int i=0; i < num_pixels; i+=DOWNSAMPLE_STEP) {
                    writer.writeInt(sum_buf[i]);
                }

                for (int i=0; i < num_pixels; i+=DOWNSAMPLE_STEP) {
                    writer.writeLong(ssq_buf[i]);
                }

                for (int i=0; i < num_pixels; i+=DOWNSAMPLE_STEP) {
                    writer.writeShort(max_buf[i]);
                }
                for (int i=0; i < num_pixels; i+=DOWNSAMPLE_STEP) {
                    writer.writeShort(second_buf[i]);
                }
                writer.flush();
                writer.close();
                out.close();
            }
        } catch(Exception e) {
            Log.e(TAG, e.getMessage());
            Log.e(TAG, "Failed to save results.");
            return;
        }
        App.log().append("all output files have been written.\n");
    }
}
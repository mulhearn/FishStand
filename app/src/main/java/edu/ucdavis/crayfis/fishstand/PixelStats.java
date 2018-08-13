package edu.ucdavis.crayfis.fishstand;

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

    private static final long FILE_SIZE = 5000000L;
    private static final int DOWNSAMPLE_STEP = 171;

    private AtomicInteger images = new AtomicInteger();
    private int num_pixels;

    private Allocation abuf;
    private Allocation sum;
    private Allocation ssq;
    private ScriptC_pixelstats script;

    public void Init() {

	    int nx = App.getCamera().getResX();
        int ny = App.getCamera().getResY();
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

        final int[] sum_buf = new int[num_pixels];
        sum.copyTo(sum_buf);
        sum.destroy();

        final long[] ssq_buf = new long[num_pixels];
        ssq.copyTo(ssq_buf);
        ssq.destroy();

        if (FILE_SIZE > 0) {
            WriteOutput(sum_buf, ssq_buf, images.intValue());
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


    private static void WriteOutput(int[] sum_buf, long[] ssq_buf, int num_images) {
        try {
            int run_num = App.getPref().getInt("run_num", 0);

            // int (sum) + long (ssq) = 12 bytes
            int pixels_per_file = (int) (FILE_SIZE / 12);
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
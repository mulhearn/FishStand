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

    private final boolean UPLOAD;

    private final AtomicInteger images = new AtomicInteger();

    private final int num_partition;
    private final int partition_index;
    private final int partition_start;
    private final int partition_end;
    private final int partition_size;

    private final Allocation sum_alloc;
    private final Allocation ssq_alloc;
    private final Allocation mxv_alloc;
    private final Allocation sec_alloc;

    private final ScriptC_pixelstats script;

    private final boolean YUV;
    private final long FILE_SIZE;
    private final int DOWNSAMPLE_STEP;

    private final ReentrantLock script_lock = new ReentrantLock();

    public PixelStats(Config cfg, boolean upload) {

        UPLOAD = upload;

        YUV = cfg.getBoolean("yuv", false);
        FILE_SIZE = cfg.getLong("filesize", 5000000);
        DOWNSAMPLE_STEP = cfg.getInteger("samplefile", 171);

        int nx = App.getCamera().getResX();
        int ny = App.getCamera().getResY();
        int num_pixel = nx * ny;

        num_partition   = cfg.getInteger("num_partition", 1);
        partition_index = cfg.getInteger("partition_index", 0);

        int pixels_per_partition = num_pixel / num_partition;
        if (num_pixel % num_partition != 0){
            pixels_per_partition += 1;
        }
        partition_start = pixels_per_partition * partition_index;
        partition_end   = Math.min(pixels_per_partition * (partition_index+1), num_pixel);
        partition_size = partition_end - partition_start;

        if (num_partition > 1){
            App.log().append("processing partition " + partition_index + " of " + num_partition + ".\n");

        }

        App.log().append("will process " + partition_size + " pixels of " + num_pixel + " total\n")
                .append("allocating memory, please be patient...\n");

        RenderScript rs = App.getRenderScript();

        sum_alloc = Allocation.createSized(rs, Element.U32(rs), partition_size, Allocation.USAGE_SCRIPT);
        ssq_alloc = Allocation.createSized(rs, Element.U64(rs), partition_size, Allocation.USAGE_SCRIPT);
        mxv_alloc = Allocation.createSized(rs, Element.U16(rs), partition_size, Allocation.USAGE_SCRIPT);
        sec_alloc = Allocation.createSized(rs, Element.U16(rs), partition_size, Allocation.USAGE_SCRIPT);

        script = new ScriptC_pixelstats(App.getRenderScript());
        script.bind_sum(sum_alloc);
        script.bind_ssq(ssq_alloc);
        script.bind_mxv(mxv_alloc);
        script.bind_sec(sec_alloc);
        script.invoke_set_image_width(nx);
        script.invoke_set_partition(partition_start, partition_end);

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

        final int[] sum;
        final long[] ssq;
        final short[] mxv;
        final short[] sec;

        synchronized (this) {
            sum = new int[partition_size];
            sum_alloc.copyTo(sum);
            sum_alloc.destroy();

            ssq = new long[partition_size];
            ssq_alloc.copyTo(ssq);
            ssq_alloc.destroy();

            mxv = new short[partition_size];
            mxv_alloc.copyTo(mxv);
            mxv_alloc.destroy();

            sec = new short[partition_size];
            sec_alloc.copyTo(sec);
            sec_alloc.destroy();
        }

        if (FILE_SIZE > 0) {
            WriteOutput(sum, ssq, mxv, sec, images.intValue());
        }
        long outer_sum = 0;
        for (int ipix=0; ipix<partition_size;ipix++){
            outer_sum += sum[ipix];
        }
        double avg;
        try {
            avg = outer_sum / (double) partition_size / images.intValue();
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

            final int HEADER_SIZE = 12;
            final int VERSION = 1;

            for (int ifile = 0; ifile < num_files; ifile++) {
                int pixel_start = pixels_per_file * ifile;
                int pixel_end   = pixel_start + pixels_per_file;
                if (pixel_end > num_pixels){
                    pixel_end = num_pixels;
                }

                String filename = "run_" + run_num + "_part_" + ifile + "_pixelstats.dat";
                App.log().append("writing file " + filename + "\n");
                OutputStream out = Storage.newOutput(filename, UPLOAD);
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
                writer.writeInt(num_partition);
                writer.writeInt(partition_index);
                writer.writeInt(partition_start + pixel_start);
                writer.writeInt(partition_start + pixel_end);
                writer.writeInt(1); // downsample step

                // payload
                for (int i = pixel_start; i < pixel_end; i++) {
                    writer.writeInt(sum_buf[i]);
                }

                for (int i = pixel_start; i < pixel_end; i++) {
                    writer.writeLong(ssq_buf[i]);
                }

                if (max_buf != null) {
                    for (int i = pixel_start; i < pixel_end; i++) {
                        writer.writeShort(max_buf[i]);
                    }
                }
                if (second_buf != null) {
                    for (int i = pixel_start; i < pixel_end; i++) {
                        writer.writeShort(second_buf[i]);
                    }
                }
                writer.flush();
                writer.close();
                out.close();
            }

            // add an extra downsampled file
            if (DOWNSAMPLE_STEP > 0) {
                String filename = "run_" + run_num + "_sample" + "_pixelstats.dat";
                App.log().append("writing file " + filename + "\n");
                OutputStream out = Storage.newOutput(filename, UPLOAD);
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
                writer.writeInt(num_partition);
                writer.writeInt(partition_index);
                writer.writeInt(partition_start); // pixel_start
                writer.writeInt(partition_end); // pixel_end
                writer.writeInt(DOWNSAMPLE_STEP);
                for (int i=0; i < partition_size; i+=DOWNSAMPLE_STEP) {
                    writer.writeInt(sum_buf[i]);
                }

                for (int i=0; i < num_pixels; i+=DOWNSAMPLE_STEP) {
                    writer.writeLong(ssq_buf[i]);
                }
                for (int i = 0; i < num_pixels; i += DOWNSAMPLE_STEP) {
                    writer.writeShort(max_buf[i]);
                }
                for (int i = 0; i < num_pixels; i += DOWNSAMPLE_STEP) {
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
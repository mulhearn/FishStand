package edu.ucdavis.crayfis.fishstand;

import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.util.Log;

import java.io.DataOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

public class PixelStats implements Analysis {
    public static final String TAG = "PixelStats";

    int num_files = 10;
    int num_pixels;
    //int step_pixels = 117;
    int step_pixels = 17;

    int num_regions = 20;
    int region_size;
    int images = 0;

    int offset = 0;
    long run_exposure    = 0;
    int run_sensitivity = 0;

    long[] sum = null;
    long[] ssq = null;

    //int num_settings = 0;
    //int[] exposures     = {};
    //int[] sensitivities = {};

    //int num_settings = 6;
    //long[] exposures     = {32000, 100000, 320000, 1000000, 3200000, 10000000};
    //int[] sensitivities = {640,640,640,640,640,640};


    int num_settings = 4;
    long[] exposures     = {500000, 5000000, 50000000, 500000000};
    int[] sensitivities = {640,640,640,640};

    int num_repeats = 5;
    int fixed_offset = -1;
    int first        = 0;

    boolean write_files=true;

    public void Init() {
        int run_offset = App.getPref().getInt("run_num", 0);

        first = 0;

        if (first > 0){
            if (run_offset > first){
                run_offset = run_offset - first;
            } else {
                run_offset = 0;
            }
        }

        if (num_settings == 0) {
            offset = run_offset % step_pixels;
            run_exposure    = App.getCamera().max_exp;
            run_sensitivity = App.getCamera().max_analog;
        } else {
            int cycle = run_offset % (step_pixels * num_settings * num_repeats);
            offset = cycle / (num_settings * num_repeats);
            int iset = cycle % num_settings;
            run_exposure    = exposures[iset];
            run_sensitivity = sensitivities[iset];
        }

        if (fixed_offset >= 0){
            offset = fixed_offset;
        }

	    int nx = App.getCamera().raw_size.getWidth();
        int ny = App.getCamera().raw_size.getHeight();
	    num_pixels = (nx * ny - offset) / step_pixels;
        if ((nx*ny - offset) % step_pixels != 0)
            num_pixels = num_pixels+1;

        region_size = num_pixels / num_regions;
        if (num_pixels % num_regions != 0){
            region_size = region_size + 1;
        }

        String str = "";
        str += "num_pixels:   " + num_pixels + "\n";
        str += "num_regions:  " + num_regions + "\n";
        str += "step_pixels:  " + step_pixels + "\n";
        str += "region_size:  " + region_size + "\n";
        str += "allocating memory, please be patient...\n";
        App.log().append(str);
        sum = new long[num_pixels];
        ssq = new long[num_pixels];
        App.log().append("finished allocating memory.\n");
    }

    public void Next(CaptureRequest.Builder request) {
        request.set(CaptureRequest.SENSOR_EXPOSURE_TIME, run_exposure);
        request.set(CaptureRequest.SENSOR_SENSITIVITY, run_sensitivity);
    }

    public void ProcessImage(Image img) {
        synchronized(this){
            images++;
        }

        Image.Plane iplane = img.getPlanes()[0];
        ByteBuffer buf = iplane.getBuffer();
        int pw = iplane.getPixelStride();

        RegionLock lock = new RegionLock(num_regions);

        Boolean todo[] = lock.newToDoList();
        do {
            int region = lock.lockRegion(todo);
            while(region < 0){
                // no unfinished and available region, so wait a spell:
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                region = lock.lockRegion(todo);
            }

            int start = region*region_size;
            int end   = (region+1)*region_size;
            if (end > num_pixels)
                end = num_pixels;
            for (int ipix = start; ipix < end; ipix++){
                int index = (offset + ipix * step_pixels ) * pw;
                char b = buf.getChar(index);
                sum[ipix] += b;
                ssq[ipix] += b*b;
            }
            lock.releaseRegion(region);
        } while(lock.stillWorking(todo));
    }

    public void ProcessRun() {
        if (write_files) {
            WriteOutput();
        }
        long outer_sum = 0;
        for (int ipix=0; ipix<num_pixels;ipix++){
            outer_sum += sum[ipix];
        }
        double avg = 0;
        double denom = (double) (num_pixels * images);
        if (denom > 0){
            avg = outer_sum / denom;
        }
        String logstr = "run exposure:     " + run_exposure + "\n";
        logstr += "run sensitivity:  " + run_sensitivity + "\n";
        logstr += "mean pixel value:  " + avg + "\n";
        logstr += "pixel step:        " + step_pixels + "\n";
        logstr += "pixel offset:      " + offset + "\n";

        App.log().append(logstr);
    }


    private void WriteOutput() {
        try {
            int run_num = App.getPref().getInt("run_num", 0);

            int pixels_per_file = num_pixels / num_files;
            if (num_pixels % num_files != 0){
                pixels_per_file = pixels_per_file + 1;
            }

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
                final int HEADER_SIZE = 12;
                final int VERSION = 1;
                writer.writeInt(HEADER_SIZE);
                writer.writeInt(VERSION);
                //additional header items (should match above size!)
                writer.writeInt(images);
                writer.writeInt(num_files);
                writer.writeInt(ifile);
                writer.writeInt(App.getCamera().raw_size.getWidth());
                writer.writeInt(App.getCamera().raw_size.getHeight());
                writer.writeInt(run_sensitivity);
                writer.writeInt((int) (run_exposure/1000));
                writer.writeInt(step_pixels);
                writer.writeInt(offset);
                writer.writeInt(num_pixels);
                writer.writeInt(pixel_start);
                writer.writeInt(pixel_end);

                // payload
                for (int i = pixel_start; i < pixel_end; i++) {
                    writer.writeLong(sum[i]);
                }
                for (int i = pixel_start; i < pixel_end; i++) {
                    writer.writeLong(ssq[i]);
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
package edu.ucdavis.crayfis.fishstand;

import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.Type;
import android.util.Log;

import java.io.DataOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

public class Cosmics implements Analysis {
    private static final String TAG = "Cosmics";

    private static final long FILE_SIZE = 5000000L;

    private AtomicInteger images = new AtomicInteger();

    private char max_hist = 1024;
    private AtomicLongArray hist;

    private int run;
    private int num_pixels;

    private int file_count;

    public void Init() {
        hist = new AtomicLongArray(max_hist+1);
        file_count = 0;

        run = App.getPref().getInt("run_num", 0);
        //if(first == 0) first = run_offset;

	    int nx = App.getCamera().getResX();
        int ny = App.getCamera().getResY();
        num_pixels = nx * ny;

        App.log().append("num_pixels:   " + num_pixels + "\n");
    }

    public void Next(CaptureRequest.Builder request) {
    }

   public void ProcessImage(Image img) {
        images.incrementAndGet();

        Image.Plane iplane = img.getPlanes()[0];
        int pw = iplane.getPixelStride();
        int rw = iplane.getRowStride();

        int ps = iplane.getPixelStride();
        ByteBuffer buf = iplane.getBuffer();


        for (int index = 0; index < num_pixels; index++) {
            char b = buf.getChar(index*ps);
            if (b > max_hist){
                b = max_hist;
            }
            hist.incrementAndGet(b);
        }
    }

    public void ProcessRun() {

        App.log().append("Cosmics run ending...\n")
                .append("run exposure:     " + App.getCamera().getExposure() + "\n")
                .append("run sensitivity:  " + App.getCamera().getISO() + "\n");
        WriteOutput();
    }




    private void WriteOutput (){
        try {
            int run_num = App.getPref().getInt("run_num", 0);


            final int HEADER_SIZE = 7;
            final int VERSION = 1;

            String filename = "run_" + run_num + "_cosmics_summary.dat";
            App.log().append("writing file " + filename + "\n");
            OutputStream out = Storage.newOutput(filename);
            DataOutputStream writer = new DataOutputStream(out);
            writer.writeInt(HEADER_SIZE);
            writer.writeInt(VERSION);
            //additional header items (should match above size!)
            writer.writeInt(images.intValue());
            writer.writeInt(file_count);
            writer.writeInt(App.getCamera().getResX());
            writer.writeInt(App.getCamera().getResY());
            writer.writeInt(App.getCamera().getISO());
            writer.writeInt((int) App.getCamera().getExposure());
            writer.writeInt(hist.length());
            for (int i = 0; i < hist.length(); i++) {
                writer.writeLong(hist.get(i));
            }
            writer.flush();
            writer.close();
            out.close();
        } catch(Exception e) {
            Log.e(TAG, e.getMessage());
            Log.e(TAG, "Failed to save results.");
            return;
        }
        App.log().append("all output files have been written.\n");
    }
}
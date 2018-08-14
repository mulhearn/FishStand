package edu.ucdavis.crayfis.fishstand;

import android.media.Image;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.Type;
import android.util.Log;

import java.io.DataOutputStream;
import java.io.OutputStream;
import java.nio.ShortBuffer;
import java.util.concurrent.atomic.AtomicInteger;

public class Cosmics implements Analysis {
    private static final String TAG = "Cosmics";

    //private static final long FILE_SIZE = 5000000L;
    private static final int FILE_COUNT = 0;

    private AtomicInteger images = new AtomicInteger();

    private Allocation abuf;
    private Allocation ahist;
    private static char max_hist = 1023;
    private Allocation ax;
    private Allocation ay;
    private Allocation aval;
    private Allocation an;
    private static final int MAX_N = 120;
    private ScriptC_cosmics script;

    private int THRESH = 1000;

    public Cosmics() {

	    int nx = App.getCamera().getResX();
        int ny = App.getCamera().getResY();
        int num_pixels = nx * ny;

        App.log().append("num_pixels:   " + num_pixels + "\n");

        RenderScript rs = App.getRenderScript();
        script = new ScriptC_cosmics(rs);

        Type type16 = new Type.Builder(rs, Element.U16(rs))
                .setX(nx)
                .setY(ny)
                .create();

        abuf = Allocation.createTyped(rs, type16);
        ahist = Allocation.createSized(rs, Element.U64(rs), max_hist+1, Allocation.USAGE_SCRIPT);
        ax = Allocation.createSized(rs, Element.U32(rs), MAX_N, Allocation.USAGE_SCRIPT);
        ay = Allocation.createSized(rs, Element.U32(rs), MAX_N, Allocation.USAGE_SCRIPT);
        aval = Allocation.createSized(rs, Element.U16(rs), MAX_N, Allocation.USAGE_SCRIPT);
        an = Allocation.createSized(rs, Element.U32(rs), 1, Allocation.USAGE_SCRIPT);

        script.bind_gHist(ahist);
        script.bind_gPixX(ax);
        script.bind_gPixY(ay);
        script.bind_gPixVal(aval);
        script.bind_gPixN(an);
        script.set_gMaxN(MAX_N);
        script.set_gThresh(THRESH);
    }

    public void ProcessImage(Image img) {
        images.incrementAndGet();

        Image.Plane plane = img.getPlanes()[0];
        ShortBuffer buf = plane.getBuffer().asShortBuffer();

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
            script.forEach_histogram(abuf);
            int[] pixN = new int[1];
            an.copyTo(pixN);
            Log.d(TAG, "pixN = " + pixN[0]);
            if(pixN[0] > 0) {
                int[] pixX = new int[pixN[0]];
                int[] pixY = new int[pixN[0]];
                int[] pixVal = new int[pixN[0]];
                ax.copy1DRangeToUnchecked(0, pixN[0], pixX);
                ay.copy1DRangeToUnchecked(0, pixN[0], pixY);
                aval.copy1DRangeToUnchecked(0, pixN[0], pixVal);

                for(int i=0; i<pixN[0]; i++) {
                    App.log().append("(" + pixX[i] + ", " + pixY[i] + "): " + pixVal[i] + "\n");
                }
                script.invoke_reset();
            }
        }
    }

    public void ProcessRun() {

        App.log().append("Cosmics run ending...\n")
                .append("run exposure:     " + App.getCamera().getExposure() + "\n")
                .append("run sensitivity:  " + App.getCamera().getISO() + "\n");

        long[] hist_array = new long[ahist.getBytesSize()/8];
        ahist.copyTo(hist_array);

        int total_pix = 0;
        int sum = 0;
        for(int i=0; i<hist_array.length; i++) {
            total_pix += hist_array[i];
            sum += hist_array[i] * i;
            //Log.d(TAG, "hist[" + i + "] = " + hist_array[i]);
        }
        App.log().append("Mean: " + sum / (double)total_pix + "\n");

        if(FILE_COUNT > 0) {
            WriteOutput(hist_array, images.intValue());
        }
    }

    private static void WriteOutput(final long[] hist, int num_images){
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
            writer.writeInt(num_images);
            writer.writeInt(FILE_COUNT);
            writer.writeInt(App.getCamera().getResX());
            writer.writeInt(App.getCamera().getResY());
            writer.writeInt(App.getCamera().getISO());
            writer.writeInt((int) App.getCamera().getExposure());
            writer.writeInt(hist.length);
            for (long bin: hist) {
                writer.writeLong(bin);
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
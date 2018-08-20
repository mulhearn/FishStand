package edu.ucdavis.crayfis.fishstand.analysis;

import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.Type;
import android.util.Log;
import android.util.Pair;

import java.io.DataOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import edu.ucdavis.crayfis.fishstand.App;
import edu.ucdavis.crayfis.fishstand.Config;
import edu.ucdavis.crayfis.fishstand.camera.Frame;
import edu.ucdavis.crayfis.fishstand.ScriptC_cosmics;
import edu.ucdavis.crayfis.fishstand.Storage;

public class Cosmics implements Analysis {
    private static final String TAG = "Cosmics";

    //private static final long FILE_SIZE = 5000000L;
    private static final int FILE_COUNT = 1;

    private AtomicInteger images = new AtomicInteger();

    private final boolean YUV;
    private final int MAX_N;
    private final double PASS_RATE;

    private Allocation ahist;
    private Allocation ax;
    private Allocation ay;
    private Allocation aval;
    private Allocation an;
    private Allocation aweights;
    private ScriptC_cosmics script;

    private int[] pix_x_evt;
    private int[] pix_y_evt;
    private int[] pix_val_evt;

    private ArrayList<Short> pixX = new ArrayList<>();
    private ArrayList<Short> pixY = new ArrayList<>();
    private ArrayList<Short> pixVal = new ArrayList<>();

    // TODO: how do we populate this list?
    private ArrayList<Pair<Integer, Integer>> hotXY = new ArrayList<>();

    private static int thresh = 1023;

    private static final ReentrantLock ALLOCATION_LOCK = new ReentrantLock();

    public Cosmics(Config cfg) {

        YUV = cfg.getBoolean("yuv", false);
        MAX_N = cfg.getInteger("max_n", 120);
        PASS_RATE = cfg.getDouble("pass_rate", 0.1);

        final int MAX_HIST = YUV ? 255 : 1023;

	    int nx = App.getCamera().getResX();
        int ny = App.getCamera().getResY();

        RenderScript rs = App.getRenderScript();
        script = new ScriptC_cosmics(rs);

        ahist = Allocation.createSized(rs, Element.U64(rs), MAX_HIST+1, Allocation.USAGE_SCRIPT);
        ax = Allocation.createSized(rs, Element.U32(rs), MAX_N, Allocation.USAGE_SCRIPT);
        ay = Allocation.createSized(rs, Element.U32(rs), MAX_N, Allocation.USAGE_SCRIPT);
        aval = Allocation.createSized(rs, Element.U32(rs), MAX_N, Allocation.USAGE_SCRIPT);
        an = Allocation.createSized(rs, Element.U32(rs), 1, Allocation.USAGE_SCRIPT);

        script.bind_gHist(ahist);
        script.bind_gPixX(ax);
        script.bind_gPixY(ay);
        script.bind_gPixVal(aval);
        script.bind_gPixN(an);
        script.set_gMaxN(MAX_N);
        script.set_gThresh(thresh);

        //hotXY.add(new Pair<>(1243, 924));
        //hotXY.add(new Pair<>(1243, 923));
        //hotXY.add(new Pair<>(1165, 741));

        if(hotXY.size() > 0) {
            aweights = Allocation.createTyped(rs, new Type.Builder(rs, Element.F16(rs))
                    .setX(nx)
                    .setY(ny)
                    .create());

            script.forEach_setToUnity(aweights, aweights);
            for(int i=0; i<hotXY.size(); i++) {
                Pair<Integer, Integer> xy = hotXY.get(i);
                aweights.copy2DRangeFrom(xy.first, xy.second, 1, 1,
                        new short[]{Float.valueOf(0f).shortValue()});
            }
        }

        App.log().append("thresh: " + thresh + "\n");
    }

    public void ProcessFrame(Frame frame) {
        images.incrementAndGet();

        final int pixN;


        Allocation buf = frame.asAllocation(ALLOCATION_LOCK);

        // RS doesn't seem to allow overloading
        if(aweights == null) {
            if(YUV) script.forEach_histogram_YUV(buf);
            else script.forEach_histogram_RAW(buf);
        } else {
            if(YUV) script.forEach_weighted_histogram_YUV(buf, aweights);
            else script.forEach_weighted_histogram_RAW(buf, aweights);
        }
        final int[] pixNcontainer = new int[1];
        an.copyTo(pixNcontainer);
        pixN = Math.min(pixNcontainer[0], MAX_N);
        script.invoke_reset();

        if(pixN > 0) {
            pix_x_evt = new int[pixN];
            pix_y_evt = new int[pixN];
            pix_val_evt = new int[pixN];
            ax.copy1DRangeToUnchecked(0, pixN, pix_x_evt);
            ay.copy1DRangeToUnchecked(0, pixN, pix_y_evt);
            aval.copy1DRangeToUnchecked(0, pixN, pix_val_evt);
        }

        ALLOCATION_LOCK.unlock();

        // TODO: should we add nearby pixels too?
        for(int i=0; i<pixN; i++) {
            pixX.add((short)pix_x_evt[i]);
            pixY.add((short)pix_y_evt[i]);
            pixVal.add((short)pix_val_evt[i]);
            Log.i(TAG,"(" + pix_x_evt[i] + ", " + pix_y_evt[i] + "): " + pix_val_evt[i] + "\n");
        }
    }

    public void ProcessRun() {

        App.log().append("Cosmics run ending...\n")
                .append("run exposure:     " + App.getCamera().getExposure() + "\n")
                .append("run sensitivity:  " + App.getCamera().getISO() + "\n");

        long[] hist_array = new long[ahist.getBytesSize() / 8];

        synchronized (this) {
            ahist.copyTo(hist_array);
        }

        long sum = 0;
        long pix = 0;
        int target_pix = (int) (PASS_RATE*images.intValue());

        boolean new_thresh = false;
        for(int i=hist_array.length-1; i>=0; i--) {
            sum += hist_array[i] * i;
            pix += hist_array[i];
            if (pix > target_pix && !new_thresh) {
                thresh = i;
                new_thresh = true;
            }
            //Log.d(TAG, "hist[" + i + "] = " + hist_array[i] + " total = " + pix);
        }
        App.log().append("Mean: " + sum / (double)pix + "\n");

        if(FILE_COUNT > 0) {
            WriteOutput(hist_array, images.intValue(), pixX, pixY, pixVal);
        }
    }

    private static void WriteOutput(final long[] hist, int num_images,
                                    ArrayList<Short> pixX, ArrayList<Short> pixY, ArrayList<Short> pixVal){
        try {
            int run_num = App.getPref().getInt("run_num", 0);


            final int HEADER_SIZE = 7;
            final int VERSION = 1;

            String filename = "run_" + run_num + "_cosmics_hist.dat";
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

            if(pixX.size() > 0) {
                // now write the events
                filename = "run_" + run_num + "_cosmics_events.dat";
                App.log().append("writing file " + filename + "\n");
                out = Storage.newOutput(filename);
                writer = new DataOutputStream(out);
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

                for (int i = 0; i < pixX.size(); i++) {
                    writer.writeShort(pixX.get(i));
                    writer.writeShort(pixY.get(i));
                    writer.writeShort(pixVal.get(i));
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
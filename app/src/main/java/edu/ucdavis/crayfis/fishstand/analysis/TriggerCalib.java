package edu.ucdavis.crayfis.fishstand.analysis;

import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

import edu.ucdavis.crayfis.fishstand.App;
import edu.ucdavis.crayfis.fishstand.Config;
import edu.ucdavis.crayfis.fishstand.ScriptC_hist;
import edu.ucdavis.crayfis.fishstand.Storage;
import edu.ucdavis.crayfis.fishstand.UploadService;
import edu.ucdavis.crayfis.fishstand.camera.Frame;

public class TriggerCalib implements Analysis {

    public static final String NAME = "trigger_calib";

    private DataOutputStream os;
    private int iFile = 0;
    private int nFramesInFile = 0;
    private UploadService.UploadBinder binder;

    private final long FILESIZE;

    private final double[] SAMPLE_FRACS;
    private final int[] SAMPLE_N;
    private final ReentrantLock OUTPUT_LOCK;

    private final ScriptC_hist script;
    private final Allocation aout;

    private final boolean YUV;
    private final int gzip;

    private final Semaphore SCRIPT_LOCK = new Semaphore(1);

    private final String jobTag;

    public TriggerCalib(Config cfg, UploadService.UploadBinder binder) {

        gzip = cfg.getInteger("gzip", 0);
        this.binder = binder;

        SAMPLE_FRACS = cfg.getDoubleArray("sample_frac", new double[]{1.0});
        Arrays.sort(SAMPLE_FRACS);

        int nPix = App.getCamera().getResX() * App.getCamera().getResY();

        SAMPLE_N = new int[SAMPLE_FRACS.length];
        OUTPUT_LOCK = new ReentrantLock();
        FILESIZE = cfg.getLong("filesize", 5000000L);

        for(int i=0; i<SAMPLE_FRACS.length; i++) {
            SAMPLE_N[i] = Math.max((int) (SAMPLE_FRACS[i] * nPix), 1);
        }

        YUV = cfg.getBoolean("YUV", false);

        RenderScript rs = App.getRenderScript();
        script = new ScriptC_hist(rs);

        aout = Allocation.createSized(rs, Element.U32(rs), 1024);
        script.bind_ahist(aout);

        jobTag = cfg.getString("tag", "unspecified");
    }

    @Override
    public void ProcessFrame(Frame frame) {
        int[] hist = new int[1024];
        double[] thresholds = new double[SAMPLE_FRACS.length];

        SCRIPT_LOCK.acquireUninterruptibly();

        if(YUV)
            script.forEach_histogram_uchar(frame.getAllocation());
        else
            script.forEach_histogram_ushort(frame.getAllocation());
        aout.copyTo(hist);

        script.invoke_clear();
        SCRIPT_LOCK.release();
        frame.close();

        int iFrac = 0;
        long sum = 0;
        int count = 0;

        for(int i=1023; i>0; i--) {
            if(hist[i] + count > SAMPLE_N[iFrac]) {
                thresholds[iFrac] = 1.0 * (sum + (SAMPLE_N[iFrac] - count) * i) / SAMPLE_N[iFrac];

                if(++iFrac == SAMPLE_FRACS.length) break;
            }

            count += hist[i];
            sum += hist[i] * i;
        }

        OUTPUT_LOCK.lock();

        try {

            if(os == null || nFramesInFile++ * SAMPLE_FRACS.length * 8 > FILESIZE) {

                if(os != null) os.close();

                String filename = "run_" + App.getPref().getInt("run_num", 0) + "_part_" + iFile++ + "_trigger_calib.dat";
                os = new DataOutputStream(Storage.newOutput(filename, jobTag, NAME, gzip, binder));
                App.log().append("Writing to " + filename);
                writeHeader(os);
            }

            for(double thresh: thresholds) {
                os.writeDouble(thresh);
            }

        } catch (IOException e) {
            App.log().append("Error writing to file");
        }

        OUTPUT_LOCK.unlock();


    }

    private void writeHeader(DataOutputStream writer) throws IOException {

        final int HEADER_SIZE = 6;
        final int VERSION = 1;

        writer.writeInt(HEADER_SIZE);
        writer.writeInt(VERSION);
        writer.writeInt(App.getCamera().getResX());
        writer.writeInt(App.getCamera().getResY());
        writer.writeInt(App.getCamera().getISO());
        writer.writeInt((int) App.getCamera().getExposure());

        writer.writeInt(SAMPLE_FRACS.length);
        for(double f: SAMPLE_FRACS) {
            writer.writeDouble(f);
        }
    }

    @Override
    public void ProcessRun() {
        try {
            if(os != null) os.close();
        } catch (IOException e) {
            App.log().append("Error writing to file");
        }
    }
}

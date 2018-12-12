package edu.ucdavis.crayfis.fishstand.analysis;

import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.os.SystemClock;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.Semaphore;

import edu.ucdavis.crayfis.fishstand.App;
import edu.ucdavis.crayfis.fishstand.Config;
import edu.ucdavis.crayfis.fishstand.ScriptC_hist;
import edu.ucdavis.crayfis.fishstand.Storage;
import edu.ucdavis.crayfis.fishstand.UploadService;
import edu.ucdavis.crayfis.fishstand.camera.Frame;

public class TriggeredImage implements Analysis {

    private static final String TAG = "TriggeredImage";
    
    private UploadService.UploadBinder binder;

    private final int SAMPLE_N;
    private final int SAMPLE_THRESH;

    private final ScriptC_hist script;
    private final Allocation aout;

    private final boolean YUV;
    private final int gzip;

    private final Semaphore SCRIPT_LOCK = new Semaphore(1);

    public TriggeredImage(Config config, UploadService.UploadBinder binder) {

        gzip = config.getInteger("gzip", 0);
        this.binder = binder;
        
        double sampleFrac = config.getDouble("sample_frac", 1.0);
        double thresh = config.getDouble("thresh", 0.0);

        int nPix = App.getCamera().getResX() * App.getCamera().getResY();
        SAMPLE_N = Math.max((int) (sampleFrac * nPix), 1);
        SAMPLE_THRESH = (int) (SAMPLE_N * thresh);

        YUV = config.getBoolean("YUV", false);

        RenderScript rs = App.getRenderScript();
        script = new ScriptC_hist(rs);

        aout = Allocation.createSized(rs, Element.U32(rs), 1024);
        script.bind_ahist(aout);
    }

    public void ProcessFrame(Frame frame) {
        int[] hist = new int[1024];

        SCRIPT_LOCK.acquireUninterruptibly();

        if(YUV)
            script.forEach_histogram_uchar(frame.getAllocation());
        else
            script.forEach_histogram_ushort(frame.getAllocation());
        aout.copyTo(hist);

        script.invoke_clear();
        SCRIPT_LOCK.release();

        int sum = 0;
        int count = 0;

        // see if the frame passes the threshold
        for(int i=1023; i>0; i--) {
            if(hist[i] + count > SAMPLE_N) {
                sum += (SAMPLE_N - count) * i;
                break;
            } else {
                count += hist[i];
                sum += hist[i] * i;
            }
        }

        if(sum > SAMPLE_THRESH) {
            try {
                TotalCaptureResult result = frame.getTotalCaptureResult();
                long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) / 1000000L
                        + App.getCamera().getBaseTime();
                String filename = timestamp + frame.getFileExt();

                OutputStream out = Storage.newOutput(filename, gzip, binder);
                if(out != null) {
                    frame.saveAndClose(out);
                    out.close();
                }
            } catch (IOException e) {
                App.log().append("Could not save image");
            }
        }

        frame.close();
    }

    public void ProcessRun() {}
}

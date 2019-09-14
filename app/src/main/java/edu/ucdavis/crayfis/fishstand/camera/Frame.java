package edu.ucdavis.crayfis.fishstand.camera;

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.TotalCaptureResult;
import android.renderscript.Allocation;
import androidx.annotation.NonNull;
import android.view.Surface;

import java.util.List;

public interface Frame {

    interface OnFrameCallback {
        void onFrame(@NonNull Frame frame, int num_frames);
    }

    interface Producer {
        List<Surface> getSurfaces();
        CameraCaptureSession.CaptureCallback getCaptureCallback();
        void stop();
        void close();
    }

    Allocation getAllocation();
    // get raw data in region with inclusive edges at xc +/- dx and yc +/- dy
    // non-existant pixels are set to zero.
    void copyRegion(int xc, int yc, int dx, int dy, short target[], int offset);
    void close();
    TotalCaptureResult getTotalCaptureResult();
}

package edu.ucdavis.crayfis.fishstand.camera;

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.TotalCaptureResult;
import android.renderscript.Allocation;
import android.support.annotation.NonNull;

public interface Frame {

    interface OnFrameCallback {
        void onFrame(@NonNull Frame frame, int num_frames);
    }

    interface Producer {
        CameraCaptureSession.CaptureCallback getCaptureCallback();
        void stop();
    }

    Allocation getAllocation();
    byte[] getRawBytes(int xoff, int yoff, int w, int h);
    void close();
    TotalCaptureResult getTotalCaptureResult();
}

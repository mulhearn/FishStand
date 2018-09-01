package edu.ucdavis.crayfis.fishstand.camera;

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.TotalCaptureResult;
import android.renderscript.Allocation;
import android.support.annotation.NonNull;
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
    byte[] getRawBytes(int xoff, int yoff, int w, int h);
    void close();
    TotalCaptureResult getTotalCaptureResult();
}

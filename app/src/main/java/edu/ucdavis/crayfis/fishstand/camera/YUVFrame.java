package edu.ucdavis.crayfis.fishstand.camera;

import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.renderscript.Allocation;

import java.util.concurrent.Semaphore;

class YUVFrame extends Frame {

    static final Semaphore ioReceiveLock = new Semaphore(1);

    YUVFrame(TotalCaptureResult result, Allocation buffer) {
        super(result, buffer);
    }

    @Override
    public Image getImage() {
        return null;
    }

    @Override
    public void close() {
        ioReceiveLock.release();
    }

    @Override
    public Allocation asAllocation() {
        return alloc;
    }
}

package edu.ucdavis.crayfis.fishstand.camera;

import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.renderscript.Allocation;

import java.util.concurrent.Semaphore;

class YUVFrame extends Frame {

    static final Semaphore ioReceiveLock = new Semaphore(1);

    private byte[] copybuf;

    YUVFrame(TotalCaptureResult result, Allocation buffer) {
        super(result, buffer);
    }

    @Override
    public byte[] getRawBytes(int xoff, int yoff, int w, int h) {
        if(copybuf == null || copybuf.length != w*h) {
            copybuf = new byte[w*h];
        }
        alloc.copy2DRangeTo(xoff, yoff, w, h, copybuf);
        return copybuf;
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

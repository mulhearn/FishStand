package edu.ucdavis.crayfis.fishstand.camera;

import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.renderscript.Allocation;

import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Lock;

class YUVFrame implements Frame {

    public byte[] getRawBytes() {
        //return getRawBytes(0, 0, alloc.getType().getX(), alloc.getType().getY());
        return null;
    }

    public byte[] getRawBytes(int xoff, int yoff, int w, int h){
        return null;
    }

    public <T> T get(CaptureResult.Key<T> key) {
        //return result.get(key);
        return null;
    }

    static final Semaphore ioReceiveLock = new Semaphore(1);

    private byte[] copybuf;

    YUVFrame(TotalCaptureResult result, Allocation buffer) {
        //super(result, buffer);
    }

    public byte[] getRawBytesOld(int xoff, int yoff, int w, int h) {
        if(copybuf == null || copybuf.length != w*h) {
            copybuf = new byte[w*h];
        }
        //alloc.copy2DRangeTo(xoff, yoff, w, h, copybuf);
        //return copybuf;
        return null;
    }

    public void close() {
        ioReceiveLock.release();
    }

    public Allocation asAllocation(Lock lock) {
        //return alloc;
        return null;
    }
}

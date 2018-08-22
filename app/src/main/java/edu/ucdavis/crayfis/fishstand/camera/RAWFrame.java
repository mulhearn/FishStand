package edu.ucdavis.crayfis.fishstand.camera;

import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.renderscript.Allocation;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.concurrent.locks.Lock;

class RAWFrame implements Frame {

    // Frame interface:

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

    private final Image image;

    private final ByteBuffer buf;
    private final int row_stride;
    private final int pixel_stride;

    private byte[] copybuf;

    RAWFrame(Image image, TotalCaptureResult result, Allocation buffer) {
        this.image = image;
        Image.Plane plane = image.getPlanes()[0];
        buf = plane.getBuffer();
        row_stride = plane.getRowStride();
        pixel_stride = plane.getPixelStride();
    }

    public byte[] getRawBytesOld(int xoff, int yoff, int w, int h) {

        int row_bytes = pixel_stride*w;
        if(copybuf == null || copybuf.length != h*row_bytes) {
            copybuf = new byte[h*row_bytes];
        }

        for(int irow=0; irow<h; irow++) {
            buf.position(row_stride*(irow+yoff) + pixel_stride*xoff);
            buf.get(copybuf, row_bytes*irow, row_bytes);
        }

        return copybuf;
    }

    @Override
    public Allocation asAllocation(Lock lock) {
        ShortBuffer sbuf = buf.asShortBuffer();
        short[] vals;
        if(sbuf.hasArray()) {
            vals = sbuf.array();
        } else {
            vals = new short[sbuf.capacity()];
            sbuf.get(vals);
        }

        lock.lock();
        //alloc.copyFromUnchecked(vals);
        //return alloc;
        return null;
    }

    @Override
    public void close() {
        image.close();
    }
}

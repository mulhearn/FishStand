package edu.ucdavis.crayfis.fishstand.camera;

import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.renderscript.Allocation;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

class RAWFrame extends Frame {

    private final Image image;

    private final ByteBuffer buf;
    private final int row_stride;
    private final int pixel_stride;

    private byte[] copybuf;

    RAWFrame(Image image, TotalCaptureResult result, Allocation buffer) {
        super(result, buffer);
        this.image = image;
        Image.Plane plane = image.getPlanes()[0];
        buf = plane.getBuffer();
        row_stride = plane.getRowStride();
        pixel_stride = plane.getPixelStride();
    }

    @Override
    public byte[] getRawBytes(int xoff, int yoff, int w, int h) {

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
    public Allocation asAllocation() {
        ShortBuffer sbuf = buf.asShortBuffer();
        short[] vals;
        if(sbuf.hasArray()) {
            vals = sbuf.array();
        } else {
            vals = new short[sbuf.capacity()];
            sbuf.get(vals);
        }

        alloc.copyFromUnchecked(vals);
        return alloc;
    }

    @Override
    public void close() {
        image.close();
    }
}

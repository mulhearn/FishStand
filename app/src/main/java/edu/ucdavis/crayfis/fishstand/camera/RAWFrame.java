package edu.ucdavis.crayfis.fishstand.camera;

import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.renderscript.Allocation;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.locks.Lock;

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
        alloc.copyFromUnchecked(vals);
        return alloc;
    }

    @Override
    public void close() {
        image.close();
    }

    static class Builder implements Frame.Builder {

        private final Queue<Image> bImageQueue = new ArrayBlockingQueue<>(5);
        private final Queue<TotalCaptureResult> bResultQueue = new ArrayBlockingQueue<>(5);
        private final Allocation bBuf;

        Builder(Allocation buf) {
            bBuf = buf;
        }

        @Nullable
        Frame addImage(Image image) {
            while(bResultQueue.size() > 0) {
                TotalCaptureResult r = bResultQueue.poll();
                Long timestamp = r.get(CaptureResult.SENSOR_TIMESTAMP);
                if(timestamp == image.getTimestamp()) {
                    return new RAWFrame(image, r, bBuf);
                }
            }

            while(!bImageQueue.offer(image)) {
                bImageQueue.poll().close();
            }
            return null;
        }

        @Nullable
        public Frame addResult(@NonNull TotalCaptureResult result) {
            Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
            if (timestamp == null) return null;

            while (bImageQueue.size() > 0) {
                Image img = bImageQueue.poll();
                if (img.getTimestamp() == timestamp) {
                    return new RAWFrame(img, result, bBuf);
                }
                img.close();
            }

            while(!bResultQueue.offer(result)) {
                bResultQueue.poll();
            }
            return null;
        }
    }
}

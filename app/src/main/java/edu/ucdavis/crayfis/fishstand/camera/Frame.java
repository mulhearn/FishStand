package edu.ucdavis.crayfis.fishstand.camera;

import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.os.Build;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Class for matching TotalCaptureResult and buffer callbacks
 */
public abstract class Frame {

    private final TotalCaptureResult result;
    final Allocation alloc;

    Frame(TotalCaptureResult result, Allocation buffer) {
        this.result = result;
        this.alloc = buffer;
    }

    public byte[] getRawBytes() {
        return getRawBytes(0, 0, alloc.getType().getX(), alloc.getType().getY());
    }

    public abstract byte[] getRawBytes(int xoff, int yoff, int w, int h);

    public <T> T get(CaptureResult.Key<T> key) {
        return result.get(key);
    }

    /**
     * Get Image buffer corresponding to TotalCaptureResult as an Allocation
     *
     * @return RenderScript Allocation with image buffer
     */
    public abstract Allocation asAllocation();

    /**
     * Release locks/buffers
     */
    public abstract void close();

    /**
     * Builder pattern for Frames
     */
    static class Builder {
        private final Queue<Image> bImageQueue = new ArrayBlockingQueue<>(5);
        private final Queue<TotalCaptureResult> bResultQueue = new ArrayBlockingQueue<>(5);
        private final Allocation bBuf;
        
        private final boolean yuv;
        private final AtomicInteger buffersQueued = new AtomicInteger(0);
        private boolean bufferReady;

        /**
         * Constructor
         * @param buf RenderScript Allocation into which the image buffers will be copied
         */
        Builder(@NonNull Allocation buf) {
            bBuf = buf;
            yuv = bBuf.getElement().getDataType() != Element.DataType.UNSIGNED_16;
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
        Frame addBuffer() {
            buffersQueued.incrementAndGet();
            YUVFrame.ioReceiveLock.tryAcquire();
            if(!bufferReady) {
                bBuf.ioReceive();
                bufferReady = true;
            }

            while(bResultQueue.size() > 0) {
                TotalCaptureResult r = bResultQueue.poll();
                Long timestamp = r.get(CaptureResult.SENSOR_TIMESTAMP);
                if(Build.VERSION.SDK_INT < Build.VERSION_CODES.N
                        || timestamp == bBuf.getTimeStamp()) {
                    buffersQueued.decrementAndGet();
                    bufferReady = false;
                    return new YUVFrame(r, bBuf);
                }
            }

            YUVFrame.ioReceiveLock.release();

            return null;
        }

        @Nullable
        Frame addResult(TotalCaptureResult result) {
            Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
            if(timestamp == null) return null;
            if(yuv) {
                YUVFrame.ioReceiveLock.tryAcquire();
                while (buffersQueued.intValue() > 0) {
                    buffersQueued.decrementAndGet();
                    if(!bufferReady) {
                        bBuf.ioReceive();
                    } else {
                        bufferReady = false;
                    }
                    if(Build.VERSION.SDK_INT < Build.VERSION_CODES.N
                            || timestamp == bBuf.getTimeStamp()) {
                        return new YUVFrame(result, bBuf);
                    }
                }
                YUVFrame.ioReceiveLock.release();
            } else {
                while (bImageQueue.size() > 0) {
                    Image img = bImageQueue.poll();
                    if (img.getTimestamp() == timestamp) {
                        return new RAWFrame(img, result, bBuf);
                    }
                }
            }

            while(!bResultQueue.offer(result)) {
                bResultQueue.poll();
            }
            return null;
        }
    }

    public interface OnFrameCallback {
        void onFrame(@NonNull Frame frame, int num_frames);
    }
}

package edu.ucdavis.crayfis.fishstand.camera;

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;


public interface Frame {
    // Callback when a frame has been assembled from it's components by the builder
    interface OnFrameCallback {
        void onFrame(@NonNull Frame frame, int num_frames);
    }

    // Frame interface, common to RAW, YUV, ...
    byte[] getRawBytes();

    byte[] getRawBytes(int xoff, int yoff, int w, int h);


    <T> T get(CaptureResult.Key<T> key);

    //Get Image buffer corresponding to TotalCaptureResult as an Allocation
    //
    // @param lock Lock for Allocation to be invoked as late as possible in the copying of the buffer
    // @return RenderScript Allocation with image buffer
    //
    Allocation asAllocation(Lock lock);

    // Release locks/buffers
    void close();

    // The Builder class assembles a complete Frame from components made available via their respective callbacks:
    class Builder extends CameraCaptureSession.CaptureCallback implements ImageReader.OnImageAvailableListener, Allocation.OnBufferAvailableListener {

        public Builder(){}

        public void setOnFrameCallback(Frame.OnFrameCallback frame_callback){
            //this.frame_callback = frame_callback;
        }

        public void setAllocation(@NonNull Allocation buf){
        }

        public void setHandler(Handler handler){
        }

        // Constructor
        // @param buf RenderScript Allocation into which the image buffers will be copied
        //Builder(@NonNull Allocation buf, Frame.OnFrameCallback frame_callback) {
            //this.buf = buf;
            //yuv = buf.getElement().getDataType() != Element.DataType.UNSIGNED_16;
            //this.frame_callback = frame_callback;
        //}

        // Callback for Capture Result
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            //Frame frame = fbuilder.addResult(result);
            //if (frame != null) {
            //    fcallback.onFrame(frame, num_frames.incrementAndGet());
            //}
        }

        // Callback for Image Available
        public void onImageAvailable(ImageReader imageReader) {
            //Frame frame = fbuilder.addImage(imageReader.acquireNextImage());
            //if (frame != null) {
            //fcallback.onFrame(frame, num_frames.incrementAndGet());
            //}
        }

        // Callback for Buffer Available
        public void onBufferAvailable(final Allocation allocation) {
            // Needs to be handled in frame handler...

            //Frame frame = fbuilder.addBuffer();
            //if (frame != null) {
            //    fcallback.onFrame(frame, num_frames.incrementAndGet());
            //}
        };


    };

}

/*
//  Class for matching TotalCaptureResult and buffer callbacks
private abstract class FrameX {

    private final TotalCaptureResult result;
    final Allocation alloc;

    FrameX(TotalCaptureResult result, Allocation alloc) {
        this.result = result;
        this.alloc = alloc;
    }

    public byte[] getRawBytes() {
        return getRawBytes(0, 0, alloc.getType().getX(), alloc.getType().getY());
    }

    public abstract byte[] getRawBytes(int xoff, int yoff, int w, int h);

    public <T> T get(CaptureResult.Key<T> key) {
        return result.get(key);
    }

    //Get Image buffer corresponding to TotalCaptureResult as an Allocation
    //
    // @param lock Lock for Allocation to be invoked as late as possible in the copying of the buffer
    // @return RenderScript Allocation with image buffer
    //
    public abstract Allocation asAllocation(Lock lock);


    public abstract void close_imp();

    static class Builder extends CameraCaptureSession.CaptureCallback implements ImageReader.OnImageAvailableListener, Allocation.OnBufferAvailableListener {

        Builder(@NonNull Allocation buf, OnFrameCallback frame_callback) {
            this.buf = buf;
            yuv = buf.getElement().getDataType() != Element.DataType.UNSIGNED_16;
            this.frame_callback = frame_callback;
        }

        // Callback for Capture Result
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            //Frame frame = fbuilder.addResult(result);
            //if (frame != null) {
            //    fcallback.onFrame(frame, num_frames.incrementAndGet());
            //}
        }

        // Callback for CaptureResult
        public void onImageAvailable(ImageReader imageReader) {
            //Frame frame = fbuilder.addImage(imageReader.acquireNextImage());
            //if (frame != null) {
            //fcallback.onFrame(frame, num_frames.incrementAndGet());
            //}
        }

        public void onBufferAvailable(final Allocation allocation) {
            //Frame frame = fbuilder.addBuffer();
            //if (frame != null) {
            //    fcallback.onFrame(frame, num_frames.incrementAndGet());
            //}
        };

        private final boolean yuv;   // is this YUV format or RAW?

        private final Queue<Image> image_queue = new ArrayBlockingQueue<>(5);
        private final Queue<TotalCaptureResult> result_queue = new ArrayBlockingQueue<>(5);
        private final Allocation buf;

        private OnFrameCallback frame_callback;

        /*
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
}*/

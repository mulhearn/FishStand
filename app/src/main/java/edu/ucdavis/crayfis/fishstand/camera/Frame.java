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
import android.util.Log;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;


public interface Frame {
    static final String TAG = "Frame";
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
        Handler frame_handler;
        Frame.OnFrameCallback frame_callback;
        Allocation buf;
        Boolean raw;

        AtomicInteger image_count = new AtomicInteger();

        private final Deque<Image> image_deque = new ArrayDeque<Image>(5);
        private final Deque<TotalCaptureResult> result_deque = new ArrayDeque<TotalCaptureResult>(5);

        public Builder(){}

        public void setHandler(Handler frame_handler){
            this.frame_handler = frame_handler;
        }

        public void setOnFrameCallback(Frame.OnFrameCallback frame_callback){
            this.frame_callback = frame_callback;
        }

        public void setAllocation(@NonNull Allocation buf){
            this.buf = buf;
            raw = buf.getElement().getDataType() == Element.DataType.UNSIGNED_16;
        }

        private void findCompleteFrames() {
            if (raw) {
                Image image = image_deque.poll();
                TotalCaptureResult result = result_deque.poll();

                if ((image == null) || (result == null)) {
                    if (image != null) {
                        // attempt to push back the image:
                        if (!image_deque.offerFirst(image)) {
                            // offer was refused, so give up on this image:
                            image.close();
                            image_count.decrementAndGet();
                        }
                    }
                    if (result != null) {
                        result_deque.offerFirst(result);
                    }
                    return;
                }

                long result_timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
                long image_timestamp = image.getTimestamp();

                if (result_timestamp == image_timestamp) {
                    Log.i(TAG, "completed frame with timestamp  " + result_timestamp);

                    image.close();
                    image_count.decrementAndGet();
                    // look for more:
                    findCompleteFrames();
                    return;
                } else {
                    Log.i(TAG, "unmatched frame with timestamps " + result_timestamp + " " + image_timestamp);
                    Log.i(TAG, "difference:  " + (result_timestamp - image_timestamp));
                }
                // drop the oldest and try again:
                if (result_timestamp < image_timestamp) {
                    if (!image_deque.offerFirst(image)) {
                        // offer was refused, so close the image:
                        image.close();
                        image_count.decrementAndGet();
                    }
                } else {
                    image.close();
                    image_count.decrementAndGet();
                    result_deque.offerFirst(result);
                }
                findCompleteFrames();
            }
        }

        // Callback for Capture Result
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);

            while (!result_deque.offer(result)) {
                result_deque.poll();
                Log.e(TAG, "result queue is full, discarding oldest result");
            }
            findCompleteFrames();
        }

        // Callback for Image Available
        public void onImageAvailable(ImageReader imageReader) {
            // TODO:  Fix hard-coded max images:
            if (image_count.incrementAndGet() <= 10) {
                Image image = imageReader.acquireLatestImage();
                while (!image_deque.offer(image)) {
                    image_deque.poll().close();
                    image_count.decrementAndGet();
                    Log.e(TAG, "image queue is full, discarding oldest result");
                }
            } else {
                image_count.decrementAndGet();
            }
            findCompleteFrames();
        }

        // Callback for Buffer Available
        public void onBufferAvailable(final Allocation allocation) {
            // unlike onImageAvailable and onCaptureCompleted, this callback
            // was not called from the frame thread, so we post to the frame handler:
            frame_handler.post(new Runnable() {
                @Override
                public void run() {

                }
            });
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

package edu.ucdavis.crayfis.fishstand.camera;

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.Type;
import android.support.annotation.NonNull;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import edu.ucdavis.crayfis.fishstand.App;

public class RawFrame implements Frame {
    private static final String TAG = "RawFrame";
    // tracking the image count ensures we don't acquire more images then allocated image buffers.
    //   ... even though frame building is single threaded, processing images need not be.
    private static final AtomicInteger image_count = new AtomicInteger();
    private TotalCaptureResult result;
    private Image image;
    private Semaphore alloc_lock;
    private Allocation alloc;
    private boolean alloc_ready;


    public RawFrame(@NonNull Image image, @NonNull TotalCaptureResult result, Allocation alloc, Semaphore alloc_lock){
        this.result = result;
        this.image = image;
        this.alloc = alloc;
        this.alloc_lock = alloc_lock;
        alloc_ready = false;
    }

    public Allocation getAllocation(){
        if (alloc_ready){
            return alloc;
        }

        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        //int row_stride = plane.getRowStride();
        //int pixel_stride = plane.getPixelStride();

        ShortBuffer sbuf = buf.asShortBuffer();
        short[] vals;
        if(sbuf.hasArray()) {
            vals = sbuf.array();
        } else {
            vals = new short[sbuf.capacity()];
            sbuf.get(vals);
        }

        try {
            alloc_lock.acquire();  //acquireUninterruptibly() ?
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
            return null;
        }
        alloc.copyFromUnchecked(vals);
        alloc_ready = true;

        return alloc;
    }
    public byte[] getRawBytes(int xoff, int yoff, int w, int h){ return null; }

    public TotalCaptureResult getTotalCaptureResult(){ return result; }

    public void close() {
        if (alloc_ready) {
            alloc_lock.release();
            alloc_ready = false;
        }
        if (image != null) {
            image.close();
            image_count.decrementAndGet();
            image = null;
        }
    }
    static class Producer extends CameraCaptureSession.CaptureCallback implements ImageReader.OnImageAvailableListener, Frame.Producer {
        static final String TAG = "RawFrameProducer";

        // externally provided via contstructor:
        final int max_images;
        final Handler frame_handler;
        final Frame.OnFrameCallback frame_callback;

        // Storage for last acquired image and capture result:
        Image image = null;
        TotalCaptureResult result = null;

        // For now we have a single Allocation and single lock...
        private static final Semaphore allocation_lock = new Semaphore(1);
        Allocation alloc;

        // statistics on frame building
        int dropped_results = 0;
        int dropped_images = 0;
        int matches = 0;

        Producer(ImageReader ireader, Handler frame_handler, Frame.OnFrameCallback frame_callback) {
            max_images = ireader.getMaxImages();
            image_count.set(0);
            this.frame_handler = frame_handler;
            this.frame_callback = frame_callback;
            ireader.setOnImageAvailableListener(this, frame_handler);

            RenderScript rs = App.getRenderScript();
            alloc = Allocation.createTyped(rs, new Type.Builder(rs, Element.U16(rs))
                            .setX(ireader.getWidth())
                            .setY(ireader.getHeight())
                            .create(),
                    Allocation.USAGE_SCRIPT);
        }

        private void buildFrame() {
            if ((image == null) || (result == null)) {
                return;
            }
            long image_timestamp = image.getTimestamp();
            long result_timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);

            if (image_timestamp == result_timestamp) {
                Log.i(TAG, "found frame with timestamp " + image_timestamp);
                matches += 1;
                RawFrame frame = new RawFrame(image, result, alloc, allocation_lock);
                image = null;
                result = null;

                frame_callback.onFrame(frame, matches);
            }
        }


        // Callback for Capture Result
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            Log.i(TAG, "capture complete called");
            if (this.result != null) {
                dropped_results += 1;
            }
            this.result = result;
            buildFrame();
        }

        // Callback for Image Available
        public void onImageAvailable(ImageReader imageReader) {
            Log.i(TAG, "image available called");
            if (this.image != null) {
                this.image.close();
                image_count.decrementAndGet();
                dropped_images += 1;
            }
            if (image_count.get() < max_images-2) {
                this.image = imageReader.acquireLatestImage();
                image_count.incrementAndGet();
            } else {
                imageReader.acquireLatestImage().close();
                dropped_images += 1;
            }
            buildFrame();
        }

        public CameraCaptureSession.CaptureCallback getCaptureCallback() {
            return this;
        }

        public void stop() {
            frame_handler.post(new Runnable() {
                @Override
                public void run() {
                    if (result != null){
                        result = null;
                        dropped_results++;
                    }
                    if (image != null){
                        image.close();
                        image_count.decrementAndGet();
                        dropped_images++;
                    }
                    App.log().append("matched frames:  " + matches + "\n")
                            .append("dropped images:   " + dropped_images + "\n")
                            .append("dropped results:  " + dropped_results + "\n");
                }
            });
        }
    }
}

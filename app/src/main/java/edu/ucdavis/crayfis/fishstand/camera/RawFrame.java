package edu.ucdavis.crayfis.fishstand.camera;

import android.graphics.ImageFormat;
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
import android.util.Size;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import edu.ucdavis.crayfis.fishstand.App;

class RawFrame implements Frame {
    private static final String TAG = "RawFrame";
    // tracking the image count ensures we don't acquire more images then allocated image buffers.
    //   ... even though frame building is single threaded, processing images need not be.
    private static final AtomicInteger image_count = new AtomicInteger();
    private TotalCaptureResult result;
    private Image image;
    private Semaphore alloc_lock;
    private Allocation alloc;
    private boolean alloc_ready;
    private int width;
    private int height;


    RawFrame(@NonNull Image image, @NonNull TotalCaptureResult result, Allocation alloc, Semaphore alloc_lock){
        this.result = result;
        this.image = image;
        this.alloc = alloc;
        this.alloc_lock = alloc_lock;
        alloc_ready = false;
        width = image.getWidth();
        height = image.getHeight();
    }

    public Allocation getAllocation(){
        if (alloc_ready){
            return alloc;
        }
        if (alloc == null){
            return null;
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

        alloc_lock.acquireUninterruptibly();
        alloc.copyFromUnchecked(vals);
        alloc_ready = true;

        return alloc;
    }

    public void copyRegion(int xc, int yc, int dx, int dy, short target[], int offset){
        if (! alloc_ready)
            return;

        int nominal_width = 2*dx + 1;
        int nominal_height = 2*dy + 1;

        int xmin = xc - dx;
        int xmax = xc + dx;
        int ymin = yc - dy;
        int ymax = yc + dy;
        if (xmin < 0){ xmin = 0; }
        if (ymin < 0){ ymin = 0; }
        if (xmax > width){ xmax = width; }
        if (ymax > height){ ymax = height; }
        int w = xmax - xmin;
        int h = ymax - ymin;
        short buf[] = new short[w*h];
        alloc.copy2DRangeTo(xmin,ymin,w,h,buf);

        for (int idy = -dy; idy <= dy; idy++){
            for (int idx = -dx; idx <= dx; idx++) {
                int target_index = (dy+idy)*nominal_width + (dx+idx);
                short value = 0;
                int x = xc + idx;
                int y = yc + idy;
                if ((x>=xmin)&&(y>=ymin)&&(x<xmax)&&(y<ymax)){
                    int alloc_index = (y-ymin)*w + (x-xmin);
                    value = buf[alloc_index];
                }
                target[offset+target_index] = value;
            }
        }
    }

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

        ImageReader image_reader;

        // externally provided via contstructor:
        final int max_images;
        final Handler frame_handler;
        final Frame.OnFrameCallback frame_callback;

        // Storage for last acquired image
        Image image = null;
        // Collection of recent TotalCaptureResults  OPTION B:
        CaptureResultCollector result_collector = new CaptureResultCollector();

        // For now we have a single Allocation and single lock...
        private static final Semaphore alloc_lock = new Semaphore(1);
        Allocation alloc;
        Surface surface;
        // statistics on frame building
        int dropped_images = 0;
        int matches = 0;

        // discard the initial image:
        boolean initial_received;

        // stop has been called
        boolean stop_called;

        Producer(int max_allocations, Size size, Handler frame_handler, Frame.OnFrameCallback frame_callback) {
            initial_received = false;
            stop_called = false;
            max_images = 10;
            //this.max_allocations = max_allocations;
            image_reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.RAW_SENSOR, max_images);
            image_count.set(0);
            this.frame_handler = frame_handler;
            this.frame_callback = frame_callback;
            image_reader.setOnImageAvailableListener(this, frame_handler);

            RenderScript rs = App.getRenderScript();
            alloc = Allocation.createTyped(rs, new Type.Builder(rs, Element.U16(rs))
                            .setX(image_reader.getWidth())
                            .setY(image_reader.getHeight())
                            .create(),
                    Allocation.USAGE_SCRIPT);
            surface = image_reader.getSurface();
        }

        public List<Surface> getSurfaces(){
            return Arrays.asList(surface);
        }


        private void buildFrame() {
            if (stop_called){
                return;
            }
            if (image == null) {
                return;
            }
            long image_timestamp = image.getTimestamp();

            TotalCaptureResult result;
            try {
                result = result_collector.findMatch(image_timestamp);
            } catch (CaptureResultCollector.StaleTimeStampException e) {
                Log.e(TAG, "stale image timestamp encountered, discarding image.");
                image.close();
                image_count.decrementAndGet();
                dropped_images += 1;
                image = null;
                return;
            }

            if (result == null){
                //Log.i(TAG, "result deque is empty.");
                return;
            }

            long result_timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
            //Log.i(TAG, "match found called with timestamps " + result_timestamp + " and " + image_timestamp);

            matches++;
            Frame frame = new RawFrame(image, result, alloc, alloc_lock);
            image = null;
            frame_callback.onFrame(frame, matches);
        }


        // Callback for Capture Result
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            long result_timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
            //Log.i(TAG, "capture complete called with timestamp " + result_timestamp);
            result_collector.add(result);
            buildFrame();
        }

        // Callback for Image Available
        public void onImageAvailable(ImageReader imageReader) {
            if (!initial_received) {
                image_reader.acquireNextImage().close();
                initial_received = true;
                return;
            }

            //Log.i(TAG, "image available called, image timestamp ");
            if (this.image != null) {
                Log.i(TAG, "dropping stored image with timestamp " + image.getTimestamp());
                image.close();
                image_count.decrementAndGet();
                dropped_images += 1;
                image = null;
            }

            if (image_count.get() < max_images-2) {
                image = imageReader.acquireNextImage();
                if (image == null){
                    Log.i(TAG, "null image received in callback...  ignoring.");
                    return;
                }
                //Log.i(TAG, "keeping new image with timestamp " + image.getTimestamp());
                image_count.incrementAndGet();
            } else {
                Image image = imageReader.acquireNextImage();
                if (image == null){
                    Log.i(TAG, "null image received in callback...  ignoring.");
                    return;
                }
                Log.i(TAG, "dropping new image with timestamp " + image.getTimestamp());
                image.close();
                dropped_images += 1;
                return;
            }
            buildFrame();
        }

        public CameraCaptureSession.CaptureCallback getCaptureCallback() {
            return this;
        }

        public void stop() {
            stop_called = true;
            App.log().append("matched frames:  " + matches + "\n")
                    .append("dropped images:   " + dropped_images + "\n")
                    .append("dropped results:  " + result_collector.dropped() + "\n");
        }

        public void close(){
            if (surface != null) {
                surface.release();
                surface = null;
            }
            if(image_reader != null) {
                image_reader.close();
                image_reader = null;
            }
            if (alloc != null){
                alloc.destroy();
                alloc = null;
            }
        }

    }
}

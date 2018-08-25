package edu.ucdavis.crayfis.fishstand.camera;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.os.Handler;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.Type;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

import edu.ucdavis.crayfis.fishstand.App;

public class YuvFrame implements Frame {
    private static final String TAG = "YuvFrame";
    private TotalCaptureResult result;
    private Semaphore alloc_lock;
    private Allocation alloc;
    private boolean alloc_ready;

    public YuvFrame(@NonNull TotalCaptureResult result, Allocation alloc, Semaphore alloc_lock){
        this.result = result;
        this.alloc = alloc;
        this.alloc_lock = alloc_lock;
        alloc_ready = false;
    }

    public Allocation getAllocation(){
        if (alloc_ready){
            return alloc;
        }
        alloc_lock.acquireUninterruptibly();
        alloc.ioReceive();
        return alloc;
    }
    public byte[] getRawBytes(int xoff, int yoff, int w, int h){ return null; }

    public TotalCaptureResult getTotalCaptureResult(){ return result; }

    public void close() {
        if (alloc_ready) {
            alloc_lock.release();
            alloc_ready = false;
        }
    }

    static class Producer extends CameraCaptureSession.CaptureCallback implements Allocation.OnBufferAvailableListener, Frame.Producer {
        static final String TAG = "YuvFrameProducer";

        // externally provided via contstructor:
        final int max_allocations;
        final Handler frame_handler;
        final Frame.OnFrameCallback frame_callback;

        // Collection of recent TotalCaptureResults  OPTION B:
        CaptureResultCollector result_collector = new CaptureResultCollector();

        // For now we have a single lock...
        private static final Semaphore alloc_lock = new Semaphore(1);

        List<Allocation> allocs;
        List<Surface> surfaces;

        // statistics on frame building
        int dropped_images = 0;
        int matches = 0;

        // throw out initial capture:
        boolean initial_received = false;

        Producer(int max_allocations, Size size, Handler frame_handler, Frame.OnFrameCallback frame_callback) {
            this.max_allocations = max_allocations;
            this.frame_handler = frame_handler;
            this.frame_callback = frame_callback;

            RenderScript rs = App.getRenderScript();
            allocs = new ArrayList<>(max_allocations);
            surfaces = new ArrayList<>(max_allocations);
            for(int i=0; i<max_allocations; i++) {
                Allocation a = Allocation.createTyped(rs, new Type.Builder(rs, Element.U8(rs))
                                .setX(size.getWidth())
                                .setY(size.getHeight())
                                .setYuvFormat(ImageFormat.YUV_420_888)
                                .create(),
                        Allocation.USAGE_SCRIPT | Allocation.USAGE_IO_INPUT);
                allocs.add(a);
                surfaces.add(a.getSurface());
                a.setOnBufferAvailableListener(this);
            }

        }

        public List<Surface> getSurfaces(){
            return surfaces;
        }

        private void buildFrame() {
            TotalCaptureResult result;
            long alloc_timestamp = 0;
            try {
                result = result_collector.findMatch(alloc_timestamp);
            } catch (CaptureResultCollector.StaleTimeStampException e) {
                Log.e(TAG, "stale allocation timestamp encountered, discarding this ioreceive.");
                dropped_images += 1;
                return;
            }

            if (result == null){
                Log.e(TAG, "result deque is empty.");
                return;
            }

            long result_timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
            Log.i(TAG, "match found called with timestamps " + result_timestamp);

            matches++;
            //Frame frame = new YuvFrame(result, alloc, alloc_lock);
            //frame_callback.onFrame(frame, matches);
        }

        // Callback for Buffer Available:
        @Override
        public void onBufferAvailable(final Allocation allocation) {
            if(!initial_received) {
                allocation.ioReceive();
                initial_received = true;
                return;
            }
            // operate in the same thread as captureCallback
            frame_handler.post(new Runnable() {
                @Override
                public void run() {
                    // ...
                }
            });
        }




        // Callback for Capture Result
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            long result_timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
            Log.i(TAG, "capture complete called with timestamp " + result_timestamp);
            result_collector.add(result);
            buildFrame();
        }

        public CameraCaptureSession.CaptureCallback getCaptureCallback() {
            return this;
        }

        public void stop() {
            frame_handler.post(new Runnable() {
                @Override
                public void run() {
                    App.log().append("matched frames:  " + matches + "\n")
                            .append("dropped images:   " + dropped_images + "\n")
                            .append("dropped results:  " + result_collector.dropped() + "\n");
                    if (surfaces != null) {
                        for (Surface s : surfaces) s.release();
                        surfaces = null;
                    }
                    if (allocs != null) {
                        for (Allocation a : allocs) a.destroy();
                        allocs = null;
                    }
                }
            });
        }
    }
}

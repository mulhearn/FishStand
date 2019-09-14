package edu.ucdavis.crayfis.fishstand.camera;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.os.Build;
import android.os.Handler;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.Type;
import androidx.annotation.NonNull;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Semaphore;

import edu.ucdavis.crayfis.fishstand.App;

class YuvFrame implements Frame {
    private static final String TAG = "YuvFrame";
    private TotalCaptureResult result;
    private Semaphore alloc_lock;
    private Allocation alloc;
    private boolean alloc_ready;

    YuvFrame(@NonNull TotalCaptureResult result, Allocation alloc, Semaphore alloc_lock){
        this.result = result;
        this.alloc = alloc;
        this.alloc_lock = alloc_lock;
        alloc_ready = true;
    }

    public Allocation getAllocation(){
        return alloc;
    }

    public void copyRegion(int xc, int yc, int dx, int dy, short target[], int offset) {
    }


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

        private List<Allocation> allocs;
        private List<Surface> surfaces;
        private HashMap<Allocation, Semaphore> locks;

        // statistics on frame building
        int dropped_images = 0;
        int matches = 0;

        // throw out initial capture:
        boolean initial_received = false;
        boolean stop_called = false;

        Producer(int max_allocations, Size size, Handler frame_handler, Frame.OnFrameCallback frame_callback) {
            this.max_allocations = max_allocations;
            this.frame_handler = frame_handler;
            this.frame_callback = frame_callback;

            RenderScript rs = App.getRenderScript();
            allocs = new ArrayList<>(max_allocations);
            surfaces = new ArrayList<>(max_allocations);
            locks = new HashMap<>();
            for(int i=0; i<max_allocations; i++) {
                Allocation a = Allocation.createTyped(rs, new Type.Builder(rs, Element.U8(rs))
                                .setX(size.getWidth())
                                .setY(size.getHeight())
                                .setYuvFormat(ImageFormat.YUV_420_888)
                                .create(),
                        Allocation.USAGE_SCRIPT | Allocation.USAGE_IO_INPUT);
                allocs.add(a);
                surfaces.add(a.getSurface());
                locks.put(a, new Semaphore(1));
                a.setOnBufferAvailableListener(this);
            }

        }

        public List<Surface> getSurfaces(){
            return surfaces;
        }

        private synchronized void buildFrame(final Allocation alloc){
            if (stop_called)
                return;

            Semaphore lock = locks.get(alloc);

            lock.acquireUninterruptibly();

            alloc.ioReceive();
            long alloc_timestamp = 0;

            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                alloc_timestamp = alloc.getTimeStamp();
            }

            Log.i(TAG, "new allocation with timestamp " + alloc_timestamp);
            TotalCaptureResult result;
            try {
                result = result_collector.findMatch(alloc_timestamp);
            } catch (CaptureResultCollector.StaleTimeStampException e) {
                Log.e(TAG, "stale allocation timestamp encountered, discarding image.");
                dropped_images += 1;
                lock.release();
                return;
            }

            if (result != null){
                Log.i(TAG, "found frame at time " + alloc_timestamp);
                matches++;

                Frame frame = new YuvFrame(result, alloc, lock);
                frame_callback.onFrame(frame, matches);

                // for now just release the allocation...
                lock.release();
                return;
            }

            dropped_images +=1;
            Log.e(TAG, "No result available matches allocation... discarding image.");
            lock.release();
        }


        // Callback for Buffer Available:
        @Override
        public void onBufferAvailable(final Allocation alloc) {
            if(!initial_received) {
                alloc.ioReceive();
                initial_received = true;
                return;
            }

            // operate in the same thread as captureCallback
            frame_handler.post(new Runnable() {
                @Override
                public void run() {
                    buildFrame(alloc);
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
            if (surfaces != null) {
                for (Surface s : surfaces) s.release();
                surfaces = null;
            }
            if (allocs != null) {
                for (Allocation a : allocs) a.destroy();
                allocs = null;
            }
        }
    }
}

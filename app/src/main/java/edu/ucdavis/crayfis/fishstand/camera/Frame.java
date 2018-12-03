package edu.ucdavis.crayfis.fishstand.camera;

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.os.Handler;
import android.renderscript.Allocation;
import android.renderscript.RenderScript;
import android.support.annotation.CallSuper;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;

import edu.ucdavis.crayfis.fishstand.App;

public abstract class Frame {

    final Semaphore alloc_lock;
    final Allocation alloc;
    boolean alloc_ready;

    final TotalCaptureResult result;

    final int width;
    final int height;

    Frame(@NonNull TotalCaptureResult result, @NonNull Allocation alloc, Semaphore alloc_lock) {
        this.result = result;
        this.alloc = alloc;
        this.alloc_lock = alloc_lock;

        this.width = alloc.getType().getX();
        this.height = alloc.getType().getY();
    }

    abstract void readyAlloc();
    public abstract void save(OutputStream outputStream) throws IOException;
    public abstract String getFileExt();

    public TotalCaptureResult getTotalCaptureResult() { return result; }

    // get raw data in region with inclusive edges at xc +/- dx and yc +/- dy
    // non-existent pixels are set to zero
    public void copyRegion(int xc, int yc, int dx, int dy, short[] array, int offset) {
        int xmin = Math.max(xc - dx, 0);
        int ymin = Math.max(yc - dy, 0);
        int xmax = Math.min(xc + dx, width - 1);
        int ymax = Math.min(yc + dy, height - 1);
        Log.d("Frame", "Range:" + xmin + "-" + xmax + ", " + ymin + "-" + ymax);
        int nominal_width = 2 * dx + 1;

        short[] buf = new short[(xmax - xmin) * (ymax - ymin)];

        copyRange(xmin, ymin, xmax - xmin, ymax - ymin, buf);

        for (int idy = -dy; idy <= dy; idy++){
            for (int idx = -dx; idx <= dx; idx++) {
                int target_index = (dy+idy)*nominal_width + (dx+idx);
                short value = 0;
                int x = xc + idx;
                int y = yc + idy;
                if ((x>=xmin)&&(y>=ymin)&&(x<xmax)&&(y<ymax)){
                    int alloc_index = (y-ymin)*(xmax - xmin) + (x-xmin);
                    value = buf[alloc_index];
                }
                array[offset+target_index] = value;
            }
        }
    }

    public void copyRange(int xOffset, int yOffset, int w, int h, Object array) {
        getAllocation().copy2DRangeTo(xOffset, yOffset, w, h, array);
    }

    public final Allocation getAllocation() {
        if (!alloc_ready) {
            readyAlloc();
            alloc_ready = true;
        }
        return alloc;
    }

    @CallSuper
    public void close() {
        if (alloc_ready) {
            alloc_lock.release();
            alloc_ready = false;
        }
    }

    public interface OnFrameCallback {
        void onFrame(@NonNull Frame frame, int num_frames);
    }

    static abstract class Producer extends CameraCaptureSession.CaptureCallback {

        // externally provided via constructor:
        final int maxAllocations;
        final Handler frameHandler;
        final Frame.OnFrameCallback frameCallback;

        // Collection of recent TotalCaptureResults  OPTION B:
        final CaptureResultCollector result_collector = new CaptureResultCollector();

        final Queue<Allocation> allocs;
        final List<Surface> surfaces;
        final HashMap<Allocation, Semaphore> locks;

        // statistics on frame building
        int dropped_images = 0;
        int matches = 0;

        // discard the initial image:
        boolean initial_received = false;

        // stop has been called
        boolean stop_called = false;

        Producer(int maxAllocations, Size sz, Handler handler, OnFrameCallback callback) {
            this.maxAllocations = maxAllocations;
            this.frameHandler = handler;
            this.frameCallback = callback;

            RenderScript rs = App.getRenderScript();
            allocs = new ArrayBlockingQueue<>(maxAllocations);
            surfaces = new ArrayList<>(maxAllocations);
            locks = new HashMap<>();

            for(int i=0; i<maxAllocations; i++) {
                Allocation a = buildAlloc(sz, rs);
                allocs.add(a);
                locks.put(a, new Semaphore(1));
            }
        }

        abstract Allocation buildAlloc(Size sz, RenderScript rs);

        final void dispatchFrame(final Frame frame) {
            if(frame != null) {
                if(stop_called) {
                    frame.close();
                    return;
                }

                // operate in the same thread as the CaptureCallback
                frameHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        frameCallback.onFrame(frame, ++matches);
                    }
                });
            }
        }

        final List<Surface> getSurfaces() {
            return surfaces;
        }

        final void stop() {
            stop_called = true;
            App.log().append("matched frames:  " + matches + "\n")
                    .append("dropped images:   " + dropped_images + "\n")
                    .append("dropped results:  " + result_collector.dropped() + "\n");
        }

        @CallSuper
        public void close(){
            for (Surface s : surfaces) {
                s.release();
            }
            surfaces.clear();
            for (Allocation a : allocs) {
                a.destroy();
            }
            allocs.clear();

        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            //long result_timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
            //Log.i("Frame", "capture complete called with timestamp " + result_timestamp);
            result_collector.add(result);
        }
    }
}

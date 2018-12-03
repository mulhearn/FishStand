package edu.ucdavis.crayfis.fishstand.camera;

import android.graphics.ImageFormat;
import android.hardware.camera2.TotalCaptureResult;
import android.os.Build;
import android.os.Handler;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.Type;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.Size;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Semaphore;

import ar.com.hjg.pngj.ImageInfo;
import ar.com.hjg.pngj.ImageLineByte;
import ar.com.hjg.pngj.PngWriter;

class YuvFrame extends Frame {
    private static final String TAG = "YuvFrame";

    private YuvFrame(@NonNull TotalCaptureResult result, Allocation alloc, Semaphore alloc_lock){
        super(result, alloc, alloc_lock);
        alloc_ready = true;
    }

    @Override
    public void readyAlloc() {}

    @Override
    public void save(OutputStream out) throws IOException {
        ImageInfo info = new ImageInfo(width, height, 8, false, true, false);
        PngWriter writer = new PngWriter(out, info);
        byte[] rowBuf = new byte[width];

        for(int irow=0; irow < height; irow++) {
            copyRange(0, irow, width, 1, rowBuf);
            ImageLineByte line = new ImageLineByte(info, rowBuf);
            writer.writeRow(line);
        }
    }

    @Override
    public String getFileExt() {
        return ".png";
    }

    static class Producer extends Frame.Producer implements Allocation.OnBufferAvailableListener {
        static final String TAG = "YuvFrameProducer";

        private final Deque<Allocation> allocationQueue = new ArrayDeque<>();

        Producer(int max_allocations, Size size, Handler frame_handler, Frame.OnFrameCallback frame_callback) {
            super(max_allocations, size, frame_handler, frame_callback);
            for(Allocation a: allocs) {
                a.setOnBufferAvailableListener(this);
                surfaces.add(a.getSurface());
            }
        }

        @Override
        Allocation buildAlloc(Size sz, RenderScript rs) {
            return Allocation.createTyped(rs, new Type.Builder(rs, Element.U8(rs))
                            .setX(sz.getWidth())
                            .setY(sz.getHeight())
                            .setYuvFormat(ImageFormat.YUV_420_888)
                            .create(),
                    Allocation.USAGE_SCRIPT | Allocation.USAGE_IO_INPUT);
        }

        private synchronized void buildFrame() {
            while(!allocationQueue.isEmpty()) {

                Allocation alloc = allocationQueue.poll();
                Semaphore lock = locks.get(alloc);

                synchronized (locks) {
                    if (lock.availablePermits() > 0) {
                        // allocations with unprocessed buffers received should all be locked
                        //
                        // in case the buffer is currently being processed, the CaptureResult
                        // will return null, exiting the loop
                        lock.acquireUninterruptibly();
                        alloc.ioReceive();
                    }
                }

                long alloc_timestamp = 0;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    alloc_timestamp = alloc.getTimeStamp();
                }

                Log.i(TAG, "new allocation with timestamp " + alloc_timestamp);
                try {
                    TotalCaptureResult result = result_collector.findMatch(alloc_timestamp);
                    if(result == null) {
                        // re-insert allocation in queue
                        allocationQueue.offerFirst(alloc);
                        break;
                    }

                    dispatchFrame(new YuvFrame(result, alloc, lock));

                } catch (CaptureResultCollector.StaleTimeStampException e) {
                    Log.e(TAG, "stale allocation timestamp encountered, discarding image.");
                    dropped_images++;
                    // this allows the buffer to be overwritten
                    lock.release();
                }
            }
        }


        // Callback for Buffer Available:
        @Override
        public void onBufferAvailable(final Allocation alloc) {
            if(!initial_received) {
                alloc.ioReceive();
                initial_received = true;
                return;
            }

            allocationQueue.add(alloc);
            buildFrame();
        }
    }
}

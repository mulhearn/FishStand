package edu.ucdavis.crayfis.fishstand.camera;

import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.os.Build;
import android.renderscript.Allocation;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Lock;

class YUVFrame extends Frame {

    private byte[] copybuf;

    YUVFrame(TotalCaptureResult result, Allocation buffer) {
        super(result, buffer);
    }

    @Override
    public byte[] getRawBytes(int xoff, int yoff, int w, int h) {
        if(copybuf == null || copybuf.length != w*h) {
            copybuf = new byte[w*h];
        }
        alloc.copy2DRangeTo(xoff, yoff, w, h, copybuf);
        return copybuf;
    }

    @Override
    public void close() {
        Builder.allocationLocks.get(alloc).release();
    }

    @Override
    public Allocation asAllocation(Lock lock) {
        lock.lock();
        return alloc;
    }

    static class Builder implements Frame.Builder {

        private final Queue<Allocation> bAllocationQueue = new ArrayBlockingQueue<>(5);
        private final Queue<TotalCaptureResult> bResultQueue = new ArrayBlockingQueue<>(5);
        private static HashMap<Allocation, Semaphore> allocationLocks;

        private boolean bufferReady;

        Builder(List<Allocation> allocations) {
            allocationLocks = new HashMap<>();
            for(Allocation a: allocations) {
                allocationLocks.put(a, new Semaphore(1));
            }
        }

        @Nullable
        synchronized Frame addBuffer(Allocation a) {

            Semaphore semaphore = allocationLocks.get(a);
            semaphore.acquireUninterruptibly();

            while(bResultQueue.size() > 0) {
                if(!bufferReady) {
                    a.ioReceive();
                    bufferReady = true;
                }

                TotalCaptureResult r = bResultQueue.poll();
                Long timestamp = r.get(CaptureResult.SENSOR_TIMESTAMP);
                if(Build.VERSION.SDK_INT < Build.VERSION_CODES.N
                        || timestamp == a.getTimeStamp()) {
                    bufferReady = false;
                    return new YUVFrame(r, a);
                }
            }

            semaphore.release();

            while (!bAllocationQueue.offer(a)) {
                bAllocationQueue.poll().ioReceive();
            }

            return null;
        }

        @Nullable
        public synchronized Frame addResult(@NonNull TotalCaptureResult result) {
            Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
            if (timestamp == null) return null;

            while (bAllocationQueue.size() > 0) {
                Allocation a = bAllocationQueue.poll();

                Semaphore semaphore = allocationLocks.get(a);
                semaphore.acquireUninterruptibly();

                if (!bufferReady) {
                    a.ioReceive();
                } else {
                    bufferReady = false;
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N
                        || timestamp == a.getTimeStamp()) {
                    return new YUVFrame(result, a);
                }

                semaphore.release();
            }

            while (!bResultQueue.offer(result)) {
                bResultQueue.poll();
            }
            return null;
        }
    }
}

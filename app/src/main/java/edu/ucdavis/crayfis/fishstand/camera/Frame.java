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
import java.util.concurrent.locks.Lock;

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
     * @param lock Lock for Allocation to be invoked as late as possible in the copying of the buffer
     * @return RenderScript Allocation with image buffer
     */
    public abstract Allocation asAllocation(Lock lock);

    /**
     * Release locks/buffers
     */
    public abstract void close();

    public interface OnFrameCallback {
        void onFrame(@NonNull Frame frame, int num_frames);
    }

    interface Builder {
        @Nullable
        Frame addResult(@NonNull TotalCaptureResult result);
    }
}

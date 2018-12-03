package edu.ucdavis.crayfis.fishstand.camera;

import android.graphics.ImageFormat;
import android.hardware.camera2.DngCreator;
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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import edu.ucdavis.crayfis.fishstand.App;

class RawFrame extends Frame {
    private static final String TAG = "RawFrame";
    private Image image;

    private RawFrame(@NonNull Image image, @NonNull TotalCaptureResult result, Allocation alloc, Semaphore alloc_lock){
        super(result, alloc, alloc_lock);
        this.image = image;
        alloc_ready = false;
    }

    @Override
    public void readyAlloc() {

        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();

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
    }

    @Override
    public void copyRange(int xOffset, int yOffset, int w, int h, Object array) {
        final Class cls = array.getClass();
        if(!cls.isArray()) throw new IllegalArgumentException();
        final Class cmp = cls.getComponentType();
        if(!cmp.isPrimitive()) throw new IllegalArgumentException();

        ByteBuffer buf = image.getPlanes()[0].getBuffer();

        if(cmp == Short.TYPE) {
            short[] shortArray = (short[]) array;
            ShortBuffer shortBuf = buf.asShortBuffer();
            for(int irow = 0; irow < h; irow++) {
                shortBuf.position((yOffset + irow) * image.getWidth() + xOffset);
                shortBuf.get(shortArray, irow * w, w);
            }
        } else if(cmp == Byte.TYPE) {
            byte[] byteArray = (byte[]) array;
            for(int irow = 0; irow < h; irow++) {
                buf.position((yOffset + irow) * image.getWidth() + xOffset);
                buf.get(byteArray, irow * w, w);
            }

        }

    }

    @Override
    public void save(OutputStream out) throws IOException {
        DngCreator dng = new DngCreator(App.getCamera().getCharacteristics(), result);
        dng.writeImage(out, image);
    }

    @Override
    public String getFileExt() {
        return ".dng";
    }

    @Override
    public void close() {
        super.close();
        if (image != null) {
            image.close();
            image = null;
        }
    }

    static class Producer extends Frame.Producer implements ImageReader.OnImageAvailableListener {
        private static final String TAG = "RawFrameProducer";
        private static final int MAX_IMAGES = 10;
        
        private ImageReader image_reader;
        // Storage for last acquired image
        private final Deque<Image> imageQueue = new ArrayDeque<>(MAX_IMAGES);

        Producer(int max_allocations, Size size, Handler frame_handler, Frame.OnFrameCallback frame_callback) {
            super(max_allocations, size, frame_handler, frame_callback);

            image_reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.RAW_SENSOR, MAX_IMAGES);
            image_reader.setOnImageAvailableListener(this, frame_handler);

            surfaces.add(image_reader.getSurface());
        }

        @Override
        Allocation buildAlloc(Size sz, RenderScript rs) {
            return Allocation.createTyped(rs, new Type.Builder(rs, Element.U16(rs))
                            .setX(sz.getWidth())
                            .setY(sz.getHeight())
                            .create(),
                    Allocation.USAGE_SCRIPT);
        }


        private synchronized void buildFrame() {
            while(!imageQueue.isEmpty()) {
                Image i = imageQueue.poll();
                try {
                    TotalCaptureResult result = result_collector.findMatch(i.getTimestamp());
                    if(result == null) {
                        // re-insert image in queue
                        imageQueue.offerFirst(i);
                        break;
                    }
                    // cycle through allocations
                    Allocation alloc = allocs.poll();
                    allocs.add(alloc);

                    dispatchFrame(new RawFrame(i, result, alloc, locks.get(alloc)));
                } catch (CaptureResultCollector.StaleTimeStampException e) {
                    i.close();
                    dropped_images++;
                }
            }
        }

        // Callback for Image Available
        @Override
        public void onImageAvailable(ImageReader imageReader) {
            if (!initial_received) {
                image_reader.acquireNextImage().close();
                initial_received = true;
                return;
            }

            imageQueue.add(imageReader.acquireNextImage());
            buildFrame();
        }

        @Override
        public void close(){
            super.close();
            if(image_reader != null) {
                image_reader.close();
                image_reader = null;
            }
        }

    }
}

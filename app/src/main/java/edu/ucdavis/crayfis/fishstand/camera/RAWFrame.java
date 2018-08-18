package edu.ucdavis.crayfis.fishstand.camera;

import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.renderscript.Allocation;

import java.nio.ShortBuffer;

class RAWFrame extends Frame {

    private final Image image;

    RAWFrame(Image image, TotalCaptureResult result, Allocation buffer) {
        super(result, buffer);
        this.image = image;
    }

    @Override
    public Image getImage() {
        return image;
    }

    @Override
    public Allocation asAllocation() {
        Image.Plane iplane = image.getPlanes()[0];
        ShortBuffer buf = iplane.getBuffer().asShortBuffer();
        short[] vals;
        if(buf.hasArray()) {
            vals = buf.array();
        } else {
            vals = new short[buf.capacity()];
            buf.get(vals);
        }

        alloc.copyFromUnchecked(vals);
        return alloc;
    }

    @Override
    public void close() {
        image.close();
    }
}

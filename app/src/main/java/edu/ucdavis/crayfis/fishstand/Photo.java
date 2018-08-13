package edu.ucdavis.crayfis.fishstand;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;

import java.io.OutputStream;
import java.nio.ByteBuffer;

public class Photo implements Analysis {
    //public static final String TAG = "Photo";

    public void Init() {
    }

    public void ProcessImage(Image img) {
        Image.Plane iplane = img.getPlanes()[0];
        ByteBuffer buf = iplane.getBuffer();

        int w = (int) (0.1 * ((float) img.getWidth()));
        int h = (int) (0.1 * ((float) img.getHeight()));
        //make a square shaped image:
        if (w > h) h = w;
        else w = h;

        int off_w = (img.getWidth() - w) >> 1;
        int off_h = (img.getHeight() - h) >> 1;
        int pw = iplane.getPixelStride();
        int rw = iplane.getRowStride();

        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);

        for (int i = 0; i < h; i++) {
            for (int j = 0; j < w; j++) {
                int index = rw * (off_h + i) + pw * (off_w + j);
                char b = buf.getChar(index);

                if (b > 0xff) b = 0xff;
                byte x = (byte) b;
                //int x = b & 0xFF;
                //if (b > 0xFF) x = 0xFF;
                //x = ~x;
                bm.setPixel(j, i, Color.argb(0xff, x, x, x));
            }
        }

        // save to output file:
        String filename = "image_" + System.currentTimeMillis() + ".jpg";
        OutputStream output = Storage.newOutput(filename);
        if (output != null) {
            bm.compress(Bitmap.CompressFormat.JPEG, 50, output);
        } else {
            App.log().append("Failed to write image file.");
        }

    }

    public void ProcessRun() {
    }
}
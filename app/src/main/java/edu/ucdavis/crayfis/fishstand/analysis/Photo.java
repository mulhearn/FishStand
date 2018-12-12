package edu.ucdavis.crayfis.fishstand.analysis;

import android.support.annotation.Nullable;

import java.io.OutputStream;

import ar.com.hjg.pngj.ImageInfo;
import ar.com.hjg.pngj.ImageLineByte;
import ar.com.hjg.pngj.PngWriter;
import edu.ucdavis.crayfis.fishstand.App;
import edu.ucdavis.crayfis.fishstand.Config;
import edu.ucdavis.crayfis.fishstand.UploadService;
import edu.ucdavis.crayfis.fishstand.camera.Frame;
import edu.ucdavis.crayfis.fishstand.Storage;

public class Photo implements Analysis {
    private static final String TAG = "Photo";

    private final int gzip;
    private UploadService.UploadBinder binder;

    private final int PHOTO_DIM;
    private final int X_OFF;
    private final int Y_OFF;

    private final ImageInfo info;
    private final int bpp;


    public Photo(Config cfg, @Nullable UploadService.UploadBinder binder) {

        gzip = cfg.getInteger("gzip", 0);
        this.binder = binder;

        PHOTO_DIM = cfg.getInteger("dimension", 256);
        X_OFF = Math.min(cfg.getInteger("x_offset", 0), App.getCamera().getResX() - PHOTO_DIM);
        Y_OFF = Math.min(cfg.getInteger("y_offset", 0), App.getCamera().getResY() - PHOTO_DIM);

        bpp = cfg.getBoolean("yuv", false) ? 8 : 16;
        info = new ImageInfo(PHOTO_DIM, PHOTO_DIM, bpp, false, true, false);
    }

    public void ProcessFrame(Frame frame) {
        String filename = "run_" + App.getPref().getInt("run_num", 0)
                + "_" + System.currentTimeMillis() + ".png";
        OutputStream output = Storage.newOutput(filename, gzip, binder);

        if(output == null) {
            App.log().append("Failed to write image file.");
            return;
        }

        PngWriter writer = new PngWriter(output, info);

        // x-dimension is doubled for 16-bit png
        int bytespp = bpp / 8;
        byte[] buf = new byte[bytespp * PHOTO_DIM];

        for (int irow = 0; irow < PHOTO_DIM; irow++) {
            frame.copyRange(bytespp * X_OFF, Y_OFF+irow, bytespp * PHOTO_DIM, 1, buf);
            ImageLineByte line = new PhotoLineByte(info, buf);
            writer.writeRow(line);
        }
        frame.close();
        writer.close();
    }

    public void ProcessRun() {
    }

    /**
     * Helper class to interpret ByteBuffers in correct order
     */
    private class PhotoLineByte extends ImageLineByte {

        private PhotoLineByte(ImageInfo info, byte[] sci) {
            super(info, sci);

            if(info.bitDepth == 16) {

                byte[] most_significant_bytes = super.getScanlineByte();
                byte[] least_significant_bytes = super.getScanlineByte2();

                for (int i = 0, s = 0; i < imgInfo.samplesPerRow; i++) {
                    least_significant_bytes[i] = sci[s++]; // get the first byte
                    most_significant_bytes[i] = sci[s++]; // get the first byte
                }

            }
        }
    }
}
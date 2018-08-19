package edu.ucdavis.crayfis.fishstand.analysis;

import java.io.OutputStream;

import ar.com.hjg.pngj.ImageInfo;
import ar.com.hjg.pngj.ImageLineByte;
import ar.com.hjg.pngj.PngWriter;
import edu.ucdavis.crayfis.fishstand.App;
import edu.ucdavis.crayfis.fishstand.Config;
import edu.ucdavis.crayfis.fishstand.camera.Frame;
import edu.ucdavis.crayfis.fishstand.Storage;

public class Photo implements Analysis {
    private static final String TAG = "Photo";

    private static final int PHOTO_DIM = 256;
    private static final int X_OFF = 0;
    private static final int Y_OFF = 0;

    private final Config CONFIG;
    private final ImageInfo info;


    public Photo(Config cfg) {
        CONFIG = cfg;
        int bpp = CONFIG.getBoolean("yuv", false) ? 8 : 16;
        info = new ImageInfo(PHOTO_DIM, PHOTO_DIM, bpp, false, true, false);
    }

    public void ProcessFrame(Frame frame) {
        String filename = "run_" + App.getPref().getInt("run_num", 0)
                + "_" + System.currentTimeMillis() + ".png";
        OutputStream output = Storage.newOutput(filename);

        if(output == null) {
            App.log().append("Failed to write image file.");
            return;
        }

        PngWriter writer = new PngWriter(output, info);

        synchronized (this) {
            for (int irow = 0; irow < PHOTO_DIM; irow++) {
                ImageLineByte line = new PhotoLineByte(info,
                        frame.getRawBytes(X_OFF, Y_OFF+irow, PHOTO_DIM, 1));
                writer.writeRow(line);
            }
            writer.close();
        }

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
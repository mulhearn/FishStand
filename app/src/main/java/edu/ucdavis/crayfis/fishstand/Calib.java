package edu.ucdavis.crayfis.fishstand;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.InputStream;

public class Calib {
    private static final String TAG = "Calib";

    public static final int DENOM = 1024;

    // have calibrations been successfully read:
    private boolean calibrated;
    public boolean isCalibrated(){ return calibrated; }

    // image width and height:
    private int width;
    private int height;


    // hot pixel calibration:
    private int num_hot;
    private int[] hot;

    // lens shading weight calibration:
    private int nx;
    private int ny;
    private int ds;
    private int lx;
    private int ly;
    private int num_wgt;
    private float wgt[];

    // weights at full resolution with hot pixels set to zero, as shorts for RS:
    private short[] combined_weights;

    public short[] getCombinedWeights(){
        if (calibrated) {
            return combined_weights;
        } else {
            short[] tmp = new short[width*height];
            for (int i=0; i<tmp.length; i++){
                tmp[i] = 1;
            }
            return tmp;
        }
    }




    public Calib(int width, int height) {
        calibrated = false;
        this.width = width;
        this.height = height;
        readCalibrations();
    }

    private void readCalibrations(){
        float max_wgt = 0.0f;
        float min_wgt = 1.0f;
        App.log().append("reading hot pixel list...\n");
        try {
            InputStream tmp = new FileInputStream(Storage.getFile("hot_pixels.cal"));
            DataInputStream input = new DataInputStream(tmp);
            num_hot = input.readInt();
            hot = new int[num_hot];
            for (int i = 0; i < num_hot; i++) {
                hot[i] = input.readInt();
            }
        } catch (Exception e) {
            App.log().append("Problem reading hot pixel list.\n");
            return;
        }
        App.log().append("read " + num_hot + " hot pixels.\n");
        if (num_hot > 1) {
            App.log().append("pixel " + hot[0] + "\n");
            App.log().append("pixel " + hot[1] + "\n");
            App.log().append("...\n");
            App.log().append("pixel " + hot[num_hot - 2] + "\n");
            App.log().append("pixel " + hot[num_hot - 1] + "\n");
        }

        App.log().append("reading lens shading calibration...\n");
        try {
            InputStream tmp = new FileInputStream(Storage.getFile("pixel_weight.cal"));
            DataInputStream input = new DataInputStream(tmp);
            nx = input.readInt();
            ny = input.readInt();
            ds = input.readInt();
            lx = input.readInt();
            ly = input.readInt();
            num_wgt = lx * ly;

            wgt = new float[num_wgt];
            for (int i=0; i<num_wgt; i++) {
                float w = input.readFloat();
                wgt[i] = w;
                if (w < min_wgt){
                    min_wgt = w;
                }
                if (w > max_wgt){
                    max_wgt = w;
                }
            }
        } catch (Exception e) {
            App.log().append("Problem reading lens shading weights.\n");
            return;
        }

        if ((nx != width) || (ny != height)){
            App.log().append("Calibration is inconsistend with image size.\n");
            return;
        }

        App.log().append("read " + num_wgt + " lens shading weights.\n");
        App.log().append("nx:           " + nx + "\n");
        App.log().append("ny:           " + ny + "\n");
        App.log().append("down_sample:  " + ds + "\n");
        App.log().append("lx:           " + lx + "\n");
        App.log().append("ly:           " + ly + "\n");
        App.log().append("min wgt:      " + min_wgt + "\n");
        App.log().append("max wgt:      " + max_wgt + "\n");
        App.log().append("Calculating full resolutions weights.\n");

        int tot_pixels = nx*ny;
        combined_weights = new short[tot_pixels];

        for (int index=0; index<tot_pixels; index++){
            int ix = (index % width) / ds;
            int iy = (index / width) / ds;
            float w = wgt[iy*lx + ix];
            combined_weights[index] = Float.valueOf(DENOM*w).shortValue();
        }
        for (int i=0; i<num_hot; i++){
            combined_weights[hot[i]] = 0;
        }

        App.log().append("All calibrations available.\n");
        calibrated = true;
    }
}

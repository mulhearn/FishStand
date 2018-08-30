package edu.ucdavis.crayfis.fishstand;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.InputStream;

public class Calib {
    private static final String TAG = "Calib";

    // hot pixel calibration:
    private int num_hot;
    private int[] hot;

    // lens shading weight calibration:
    private int down_sample;
    private int nx;
    private int ny;
    private int num_weight;
    private float weight[];

    private boolean calibrated;


    public Calib() {
        calibrated = false;
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
            down_sample = input.readInt();
            nx = input.readInt();
            ny = input.readInt();
            num_weight = nx * ny;

            weight = new float[num_weight];
            for (int i=0; i<num_weight; i++) {
                weight[i] = input.readFloat();
            }
        } catch (Exception e) {
            return;
        }

        App.log().append("read " + num_weight + " lens shading weights.\n");
        App.log().append("down_sample:  " + down_sample + "\n");
        App.log().append("nx:           " + nx + "\n");
        App.log().append("ny:           " + ny + "\n");

        App.log().append("read " + num_weight + " lens shading weights.\n");
        if (num_weight > 1){
            App.log().append("weight 0 " + weight[0] + "\n");
            App.log().append("weight 1 " + weight[1] + "\n");
        }
        App.log().append("All calibrations available.");
        calibrated = true;

    }
}

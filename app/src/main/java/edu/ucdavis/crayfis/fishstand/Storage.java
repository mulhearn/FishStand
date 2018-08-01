package edu.ucdavis.crayfis.fishstand;

import android.os.Environment;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Created by mulhearn on 8/1/18.
 */

public class Storage {
    public static final String WORK_DIR = "FishStand";

    public static OutputStream newOutput(String filename){
        File path = new File(Environment.getExternalStoragePublicDirectory(""),
                WORK_DIR);
        path.mkdirs();
        File outfile = new File(path, filename);
        try {
            return new BufferedOutputStream(new FileOutputStream(outfile));
        } catch (Exception e){
            Log.e("Storage", e.getMessage());
            return null;
        }
    }
}

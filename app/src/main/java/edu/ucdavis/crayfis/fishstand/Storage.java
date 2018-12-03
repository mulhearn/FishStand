package edu.ucdavis.crayfis.fishstand;

import android.os.Environment;
import android.support.annotation.Nullable;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.OutputStream;

public class Storage {
    private static final String WORK_DIR = "FishStand";
    private static final String UPLOAD_DIR = "uploads";

    private static File getPath(boolean upload) {
        File path = new File(Environment.getExternalStoragePublicDirectory(""), WORK_DIR);
        path.mkdirs();
        if(upload) {
            path = new File(path, UPLOAD_DIR);
            path.mkdirs();
        }
        return path;
    }

    public static File getFile(String filename) {
        return new File(getPath(false), filename);
    }

    public static File[] listFiles(@Nullable FilenameFilter filter, boolean upload) {
        if(filter == null) return getPath(upload).listFiles();
        return getPath(upload).listFiles(filter);
    }

    public static OutputStream newOutput(String filename, boolean upload) {
        File path = getPath(upload);

        File outfile = new File(path, filename);
        try {
            return new BufferedOutputStream(new FileOutputStream(outfile));
        } catch (IOException e){
            Log.e("Storage", e.getMessage());
            return null;
        }
    }
}

package edu.ucdavis.crayfis.fishstand;

import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.zip.GZIPOutputStream;

public class Storage {
    private static final String WORK_DIR = "FishStand";
    private static final String UPLOAD_DIR = "uploads";

    public static File getPath(boolean upload) {
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

    public static OutputStream newOutput(String filename, @Nullable UploadService.UploadBinder binder) {
        return newOutput(filename, 0, binder);
    }

    public static OutputStream newOutput(String filename, int compression,
                                         @Nullable UploadService.UploadBinder binder) {

        return newOutput(filename, null, null, compression, binder);
    }

    public static OutputStream newOutput(String filename, String tag, String analysis,
                                         @Nullable UploadService.UploadBinder binder) {
        return newOutput(filename, tag, analysis, 0, binder);
    }

    public static OutputStream newOutput(@NonNull String filename, String tag, String analysis,
                                         int compression, @Nullable UploadService.UploadBinder binder) {

        if(compression > 0 && !filename.endsWith(".gz"))
            filename += ".gz";

        String pathString = getPath(binder != null).getAbsolutePath();

        if(tag != null) pathString += "/" + tag;
        if(analysis != null) pathString += "/" + analysis;

        File path = new File(pathString);
        path.mkdirs();

        File outfile = new File(path, filename);

        try {
            UploadOutputStream uos = new UploadOutputStream(outfile, binder);
            if(compression > 0)
                return new VarCompressionOutputStream(uos, compression);
            return new BufferedOutputStream(uos);

        } catch (IOException e){
            Log.e("Storage", e.getMessage());
            return null;
        }
    }

    private static class VarCompressionOutputStream extends GZIPOutputStream {
        private VarCompressionOutputStream(OutputStream out) throws IOException {
            super(out);
        }
        private VarCompressionOutputStream(OutputStream out, int lvl) throws IOException {
            super(out);
            this.def.setLevel(lvl);
        }
    }

    private static class UploadOutputStream extends FileOutputStream {
        @NonNull private File file;
        @Nullable private UploadService.UploadBinder uploadBinder;
        private boolean uploaded = false;

        private UploadOutputStream(@NonNull File f, @Nullable UploadService.UploadBinder binder)
                throws FileNotFoundException {

            super(f);
            file = f;
            uploadBinder = binder;
        }

        @Override
        public synchronized void close() throws IOException {
            super.close();
            if(uploadBinder != null && uploadBinder.isBinderAlive() && !uploaded) {
                uploaded = true;
                uploadBinder.uploadFile(file);
            }
        }
    }
}

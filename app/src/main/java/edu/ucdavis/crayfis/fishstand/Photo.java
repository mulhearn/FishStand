package edu.ucdavis.crayfis.fishstand;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.google.android.gms.drive.DriveContents;
import com.google.android.gms.drive.DriveFile;
import com.google.android.gms.drive.MetadataChangeSet;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.concurrent.TimeUnit;

public class Photo implements Analysis {
    int num = 1;
    String tag = "";

    public static Analysis create() {
        Photo photo = new Photo();
        return (Analysis) photo;
    }

    private Photo() {
    }

    public void Init(String[] names, String[] values) {
    }

    public void Next(CaptureRequest.Builder request) {
        request.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 200*App.getCamera().min_exp);
    }

    public void ProcessImage(Image img, int img_index) {
        Image.Plane iplane = img.getPlanes()[0];
        ByteBuffer buf = iplane.getBuffer();

        int w = (int) (0.5 * ((float) img.getWidth()));
        int h = (int) (0.5 * ((float) img.getHeight()));
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

        // save local copy:
        try {
            File path = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "FishStand");
            path.mkdirs();
            String filename = "image_" + System.currentTimeMillis() + ".jpg";
            File outfile = new File(path, filename);
            BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(outfile));
            bm.compress(Bitmap.CompressFormat.JPEG, 50, bos);

        } catch (Exception e) {
            Log.e("photo", "Failed to save image to local storage.");
        }

        // upload to Google Drive:
        try {
            String suffix = "image_" + img_index + ".jpg";
            DriveFile dfile = App.getDriveObsolete().createOutputFile(suffix,"application/jpg");
            Task<DriveContents> open_file =
                    App.getDriveObsolete().getResourceClient().openFile(dfile, DriveFile.MODE_WRITE_ONLY);

            DriveContents contents = Tasks.await(open_file, 30000, TimeUnit.MILLISECONDS);
            ParcelFileDescriptor pfd = contents.getParcelFileDescriptor();


            BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(pfd.getFileDescriptor()));
            bm.compress(Bitmap.CompressFormat.JPEG, 50, bos);
            bos.flush();

            MetadataChangeSet changes = new MetadataChangeSet.Builder()
                .setStarred(false)
                .setLastViewedByMeDate(new Date())
                .build();
            Task<Void> commit =
                    App.getDriveObsolete().getResourceClient().commitContents(contents, changes);
            Tasks.await(commit, 30000, TimeUnit.MILLISECONDS);

        } catch(Exception e) {
            Log.e("photo", "Failed to save image to Google Drive");
            Log.e("photo", "message:  " + e.getMessage());
            return;
        }
    }

    public void ProcessRun() {
    }
}
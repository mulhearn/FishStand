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
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.concurrent.TimeUnit;

public class PixelStats implements Analysis {
    int num_pixels;
    //int step_pixels = 117;
    int step_pixels = 1;
    int num_regions = 20;
    int region_size;
    int images = 0;

    long[] sum = null;
    long[] ssq = null;

    public static Analysis create() {
        PixelStats a = new PixelStats();
        return (Analysis) a;
    }

    private PixelStats() {
    }

    public void Init(String[] names, String[] values) {
	    int nx = App.getCamera().raw_size.getWidth();
        int ny = App.getCamera().raw_size.getHeight();
	    num_pixels = nx * ny / step_pixels;

        region_size = num_pixels / num_regions;
        if (num_pixels % num_regions != 0){
            region_size = region_size + 1;
        }

        String str = "";
        str += "num_pixels:   " + num_pixels + "\n";
        str += "num_regions:  " + num_regions + "\n";
        str += "step_pixels:  " + step_pixels + "\n";
        str += "region_size:  " + region_size + "\n";
        str += "allocating memory, please be patient...\n";
        App.log().append(str);
        sum = new long[num_pixels];
        ssq = new long[num_pixels];
        App.log().append("finished allocating memory.\n");
    }

    public void Next(CaptureRequest.Builder request) {
    }

    public void ProcessImage(Image img, int img_index) {
        synchronized(this){
            images++;
        }

        Image.Plane iplane = img.getPlanes()[0];
        ByteBuffer buf = iplane.getBuffer();
        int pw = iplane.getPixelStride();

        RegionLock lock = new RegionLock(num_regions);

        Boolean todo[] = lock.newToDoList();
        do {
            int region = lock.lockRegion(todo);
            while(region < 0){
                // no unfinished and available region, so wait a spell:
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                region = lock.lockRegion(todo);
            }

            int start = region*region_size;
            int end   = (region+1)*region_size;
            if (end > num_pixels)
                end = num_pixels;
            for (int ipix = start; ipix < end; ipix++){
                int index = ipix * step_pixels * pw;
                char b = buf.getChar(index);
                sum[ipix] += b;
                ssq[ipix] += b*b;
            }
            lock.releaseRegion(region);
        } while(lock.stillWorking(todo));
    }

    public void ProcessRun() {

        // upload to Google Drive:
        try {
            String suffix = "pixelstats.dat";
            DriveFile dfile = App.getDriveObsolete().createOutputFile(suffix,"application/dat");
            Task<DriveContents> open_file =
                    App.getDriveObsolete().getResourceClient().openFile(dfile, DriveFile.MODE_WRITE_ONLY);

            DriveContents contents = Tasks.await(open_file, 30000, TimeUnit.MILLISECONDS);
            ParcelFileDescriptor pfd = contents.getParcelFileDescriptor();

            DataOutputStream writer = new DataOutputStream(new FileOutputStream(pfd.getFileDescriptor()));
            final int HEADER_SIZE = 5;
            final int VERSION = 1;
            writer.writeInt(HEADER_SIZE);
            writer.writeInt(VERSION);
            // additional header items (should match above size!)
            writer.writeInt(images);
            writer.writeInt(App.getCamera().raw_size.getWidth());
            writer.writeInt(App.getCamera().raw_size.getHeight());
            writer.writeInt(step_pixels);
            writer.writeInt(num_pixels);
            // payload
            for (int i=0;i<num_pixels;i++){
                writer.writeLong(sum[i]);
                writer.writeLong(ssq[i]);
            }

            MetadataChangeSet changes = new MetadataChangeSet.Builder()
                .setStarred(false)
                .setLastViewedByMeDate(new Date())
                .build();
            Task<Void> commit =
                    App.getDriveObsolete().getResourceClient().commitContents(contents, changes);
            //Tasks.await(commit, 30000, TimeUnit.MILLISECONDS);
            Tasks.await(commit);

        } catch(Exception e) {
            Log.e("photo", "Failed to save results to Google Drive");
            Log.e("photo", "message:  " + e.getMessage());
            return;
        }
    }
}
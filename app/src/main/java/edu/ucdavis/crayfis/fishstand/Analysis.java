package edu.ucdavis.crayfis.fishstand;

import android.hardware.camera2.CaptureRequest;
import android.media.Image;

public interface Analysis {
    // DAQ interface:
    public void Init();
    public void Next(CaptureRequest.Builder request);
    public void ProcessImage(Image img, int img_index);
    public void ProcessRun();
}


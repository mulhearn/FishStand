package edu.ucdavis.crayfis.fishstand;

import android.hardware.camera2.CaptureRequest;
import android.media.Image;

public interface Analysis {
    // DAQ interface:
    void Init();
    void ProcessImage(Image img);
    void ProcessRun();
}


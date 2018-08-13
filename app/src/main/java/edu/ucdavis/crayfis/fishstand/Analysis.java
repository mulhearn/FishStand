package edu.ucdavis.crayfis.fishstand;

import android.media.Image;

public interface Analysis {
    // DAQ interface:
    void ProcessImage(Image img);
    void ProcessRun();
}


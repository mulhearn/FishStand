package edu.ucdavis.crayfis.fishstand.analysis;

import edu.ucdavis.crayfis.fishstand.camera.Frame;

public interface Analysis {
    // DAQ interface:
    void ProcessFrame(Frame frame);
    void ProcessRun();
}


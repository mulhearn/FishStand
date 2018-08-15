package edu.ucdavis.crayfis.fishstand;

public interface Analysis {
    // DAQ interface:
    void ProcessFrame(Camera.Frame frame);
    void ProcessRun();
}


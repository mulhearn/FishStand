package edu.ucdavis.crayfis.fishstand;

import java.io.InputStream;
import java.io.OutputStream;


interface Storage {

    interface CallBack {
        void reportStorageReady();  // called once, to report successful initialization
        void reportStorageFailure(String msg);  // called upon any storage failure
    }

    InputStream getConfig();
    void closeConfig();

    void newLog(int run);
    void appendLog(String str);

    OutputStream newOutput(String filename, String mime_type);
    void closeOutput();

}

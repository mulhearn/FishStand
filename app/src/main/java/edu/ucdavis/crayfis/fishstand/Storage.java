package edu.ucdavis.crayfis.fishstand;

import java.io.InputStream;
import java.io.OutputStream;


interface Storage {

    InputStream getConfig();
    void closeConfig();

    void newLog(int run);
    void appendLog(String str);

    OutputStream newOutput(String filename, String mime_type);
    void closeOutput();

}

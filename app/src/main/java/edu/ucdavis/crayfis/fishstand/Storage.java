package edu.ucdavis.crayfis.fishstand;

import java.io.InputStream;
import java.io.OutputStream;


interface Storage {



    class DriveException extends Exception {
        public DriveException(String msg){
            super(msg);
        }
    }

    void Init() throws DriveException;

    InputStream getConfig() throws DriveException;

    OutputStream newLog() throws DriveException;
    void closeLog() throws DriveException;

    OutputStream newOutput(String suffix, String mime_type) throws DriveException;
    void closeOutput() throws DriveException;

}

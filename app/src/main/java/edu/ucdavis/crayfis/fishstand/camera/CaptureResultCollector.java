package edu.ucdavis.crayfis.fishstand.camera;

import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.Deque;

class CaptureResultCollector {
    private int dropped = 0;
    private static final String TAG = "CaptureResultCollector";

    private final Deque<TotalCaptureResult> deque = new ArrayDeque<TotalCaptureResult>();

    void add(TotalCaptureResult result) {
        //Log.i(TAG, "adding capture result to deque of size " + deque.size());
        deque.offer(result);
    }

    int dropped(){
        return dropped;
    }

    // if timestamp is older than oldest result, this exception is thrown:
    static class StaleTimeStampException extends Exception {}

    // for now just report any available Capture Result:
    TotalCaptureResult findMatch(long timestamp) throws StaleTimeStampException{
        TotalCaptureResult result = deque.poll();

        // special case for correct timestamp unavailable:
        if (timestamp == 0){
            return result;
        }

        if (result == null){
            return null;
        }
        long result_timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
        if (result_timestamp == timestamp){
            return result;
        }
        if (result_timestamp < timestamp){
            dropped++;
            return findMatch(timestamp);
        }
        deque.offerFirst(result);
        throw new StaleTimeStampException();
    }

}

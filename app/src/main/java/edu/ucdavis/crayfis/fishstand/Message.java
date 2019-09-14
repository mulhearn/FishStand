package edu.ucdavis.crayfis.fishstand;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

// a simple way to send update messages between GUI and asynchronous worker threads

// produced new XXX data?
// -> Message.updateXXX();
// handle new XXX data?
//     BroadcastReceiver updater;
//   onCreateView():
//     updater = Message.onXXXUpdate(new Runnable(){public void run(){...}});
//   onDestroyView():
//      Message.unregister(updater)

public class Message {
    final private Context context;
    Message(Context context){ this.context = context; }

    // message types
    // these can be made as fine as possible, for now using fairly coarse categories:
    private static final String UPDATE_LOG       = "update-log";     // update a log file
    private static final String UPDATE_STATE     = "update-state";   // update a state of DAQ Service

    void updateLog(){ send(UPDATE_LOG); }
    BroadcastReceiver onLogUpdate(Runnable r){ return onMessage(r, UPDATE_LOG); }

    void updateState(){ send(UPDATE_STATE); }
    BroadcastReceiver onStateUpdate(Runnable r){ return onMessage(r, UPDATE_STATE); }

    void unregister(BroadcastReceiver r) {
        LocalBroadcastManager.getInstance(context.getApplicationContext()).unregisterReceiver(r);
    }

    // generic versions:

    private BroadcastReceiver onMessage(final Runnable r, final String action){
        IntentFilter filter = new IntentFilter(action);

        BroadcastReceiver receiver = new BroadcastReceiver(){
            public void onReceive(Context context, Intent intent){
                if (intent.getAction() == action) {
                    r.run();
                }
            }
        };
        LocalBroadcastManager.getInstance(context.getApplicationContext()).registerReceiver(receiver,filter);
        return receiver;
    }

    private void send(final String action) {
        LocalBroadcastManager.getInstance(context.getApplicationContext()).sendBroadcast(new Intent(action));
    }

}


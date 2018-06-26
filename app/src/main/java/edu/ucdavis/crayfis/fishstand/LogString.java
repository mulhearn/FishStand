package edu.ucdavis.crayfis.fishstand;

public class LogString {
    private String logtxt = "";

    private Runnable update = new Runnable() {public void run() {} };

    public void setUpdate(Runnable update){
        this.update = update;
    }

    public String getTxt(){ return logtxt;}

    public synchronized void append(String s){
        logtxt = logtxt + s;
        update.run();
    }
    public synchronized void clear(){
        logtxt = "";
        update.run();
    }
}

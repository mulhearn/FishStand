package edu.ucdavis.crayfis.fishstand;

//
// A regional lock mechanism, for, e.g., dividing up an image array
//
// For typical usage, see SelfTest method.
//

public class RegionLock {
    int nreg;
    Boolean lock[];

    public static void SelfTest() {
        RegionLock lock = new RegionLock(10);

        Boolean todo[] = lock.newToDoList();
        do {
            int region = lock.lockRegion(todo);
            while(region < 0){
                // no unfinished and available region, so wait a spell:
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                region = lock.lockRegion(todo);
            }

            // handle region <region> with exclusive access
            //...

            lock.releaseRegion(region);
        } while(lock.stillWorking(todo));
    }

    RegionLock(int nreg){
        this.nreg = nreg;
        lock = new Boolean[nreg];
        for (int i=0; i<nreg; i++){
            lock[i] = false;
        }
    }

    public int numRegions(){return nreg;}

    public Boolean stillWorking(Boolean todo[]){
        for (int i=0; i<nreg; i++){
            if (todo[i]){
                return true;
            }
        }
        return false;
    }

    public Boolean[] newToDoList(){
        Boolean todo[] = new Boolean[nreg];
        for (int i=0; i<nreg; i++){
            todo[i] = true;
        }
        return todo;
    }

    synchronized int lockRegion(Boolean todo[]){
        for (int i=0; i<nreg; i++) {
            if (todo[i] && !lock[i]) {
                lock[i] = true;
                todo[i] = false;
                return i;
            }
        }
        return -1; // better luck next time!
    }

    synchronized void releaseRegion(int region){
        lock[region]=false;
    }
}

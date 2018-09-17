#pragma version(1)
#pragma rs java_package_name(edu.ucdavis.crayfis.fishstand)
#pragma rs_fp_relaxed

// output histograms:
int denom_calib;       // denominator when filling calibrated pixel histogram
// histogram of uncalibrated pixel values -> 64 bit allocation
uint64_t* hist_uncal;
uint64_t* hist_unhot;
uint64_t* hist_calib;
// output results:
uint16_t * pixel_output;

#define MAX_HIST 1024
int max_hist;
// 32 bit local histograms, for use with rsAtomicInc
uint32_t local_uncal[MAX_HIST]; // uncalibrated, but no hot pixels
uint32_t local_unhot[MAX_HIST]; // uncalibrated hot pixels
uint32_t local_calib[MAX_HIST]; // calibrated pixels

#define MAX_THRESH 10
int num_thresh;
int threshold[MAX_THRESH];
int prescale[MAX_THRESH];
uint32_t count[MAX_THRESH];

#define MAX_PIXEL 100
int max_pixel;        // the maximum number of pixels to save (must be less than MAX_PIXEL)
uint32_t num_pixel;
uint16_t pixel_x[MAX_PIXEL];
uint16_t pixel_y[MAX_PIXEL];
uint16_t highest[MAX_PIXEL];

// raw threshold:
int raw_thresh;       // initial threshold on raw counts

void init(){
    num_thresh = 0;
    for (int i=0; i<=MAX_THRESH;i++){
        count[i] = 0;
    }
}

void add_threshold(int t, int ps){
    if (num_thresh >= MAX_THRESH){
        return;
    }
    threshold[num_thresh] = t*denom_calib;
    prescale[num_thresh]  = ps;
    num_thresh += 1;
}

void set_parameters(int max_hist_, int max_pixel_, int raw_thresh_, int denom_calib_){
    max_hist = max_hist_;
    max_pixel = max_pixel_;
    raw_thresh = raw_thresh_;
    denom_calib = denom_calib_;
}

void start(){
    num_pixel = 0;
    for (int i=0; i<=max_hist;i++){
        local_uncal[i] = 0;
        local_unhot[i] = 0;
        local_calib[i] = 0;
    }
}

void finish(){
    for (int i=0; i<=max_hist;i++){
        hist_uncal[i] += local_uncal[i];
        hist_unhot[i] += local_unhot[i];
        hist_calib[i] += local_calib[i];
    }
    pixel_output[0] = num_pixel;
    if (num_pixel > max_pixel){
        num_pixel = max_pixel;
    }
    for (int i=0; i<num_pixel; i++){
        pixel_output[3*i + 1] = pixel_x[i];
        pixel_output[3*i + 2] = pixel_y[i];
        pixel_output[3*i + 3] = highest[i];
    }
}

void RS_KERNEL histogram_ushort(ushort hwval, ushort weight, uint32_t x, uint32_t y) {
    if (weight > 0){
        volatile uint32_t* addr = &local_uncal[hwval];
        rsAtomicInc(addr);
    } else {
        volatile uint32_t* addr = &local_unhot[hwval];
        rsAtomicInc(addr);
        return;
    }

    uint32_t calib = ((uint32_t) hwval) * weight;
    volatile uint32_t* addr = &local_calib[calib/denom_calib];
    rsAtomicInc(addr);
}

void RS_KERNEL histogram_uchar(ushort hwval, ushort weight, uint32_t x, uint32_t y) {
    // cut and paste above, when ready...
}


void RS_KERNEL process_ushort(ushort hwval, ushort weight, uint32_t x, uint32_t y) {

    if (hwval >= raw_thresh){
        uint32_t calib = ((uint32_t) hwval) * weight;
        int i=-1;
        for (i=num_thresh-1; i>=0; i--){
            if (calib >= threshold[i]){
                break;
            }
        }
        if (i >= 0){
            volatile uint32_t* addr_count = &count[i];
            uint32_t c = rsAtomicInc(addr_count);
            if ((c % prescale[i]) == 0){
                volatile uint32_t* addr_pixel = &num_pixel;
                int ipixel = rsAtomicInc(addr_pixel);
                if (ipixel < max_pixel){
                    pixel_x[ipixel] = x;
                    pixel_y[ipixel] = y;
                    highest[ipixel] = 1 + i;
                }
            }
        }
    }

}

void RS_KERNEL process_uchar(uchar hwval, ushort weight, uint32_t x, uint32_t y) {
    // cut and paste above, when ready...
}
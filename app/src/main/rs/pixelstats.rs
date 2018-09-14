#pragma version(1)
#pragma rs java_package_name(edu.ucdavis.crayfis.fishstand)
#pragma rs_fp_relaxed

uint32_t * sum;  // running sum
uint64_t * ssq;  // running sum of squares
uint16_t * mxv;  // maximum value
uint16_t * sec;  // second maximum value

static bool full_resolution;
static int width, flat_min, flat_max;

void init(){
    full_resolution = true;
}

void set_image_width(int width_){
    width = width_;
}


void set_partition(int flat_min_, int flat_max_){
    full_resolution = false;
    flat_min = flat_min_;
    flat_max = flat_max_;
}

static int flat_index(uint32_t x, uint32_t y, uint32_t * flat){
    *flat = y*width + x;

    if (full_resolution){
        return 0;
    }
    if ((*flat < flat_min) || (*flat >= flat_max)){
        return 1;
    }
    *flat = *flat - flat_min;
    return 0;
}


static void add(uint in, uint32_t x, uint32_t y){
    uint32_t flat;

    if (flat_index(x,y,&flat))
        return;

    sum[flat] += in;
    ssq[flat] += in*in;

    if (in > mxv[flat]){
        sec[flat] = mxv[flat];
        mxv[flat] = in;
    } else if (in > sec[flat]){
        sec[flat] = in;
    }
}

void RS_KERNEL add_RAW(ushort raw, uint32_t x, uint32_t y) {
    uint in = (uint) raw;
    add(in, x, y);
}

void RS_KERNEL add_YUV(uchar ychannel, uint32_t x, uint32_t y) {
    uint in = (uint) ychannel;
    add(in, x, y);
}
#pragma version(1)
#pragma rs java_package_name(edu.ucdavis.crayfis.fishstand)
#pragma rs_fp_relaxed

uint32_t* ahist;

void RS_KERNEL histogram_ushort(ushort val, uint32_t x, uint32_t y) {
    volatile uint32_t* addr = &ahist[val];
    rsAtomicInc(addr);
}

void RS_KERNEL histogram_uchar(uchar val, uint32_t x, uint32_t y) {
    volatile uint32_t* addr = &ahist[val];
    rsAtomicInc(addr);
}

void clear() {
    for(int i=0; i<1024; i++) {
        *(ahist + i) = 0;
    }
}


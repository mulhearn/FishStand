#pragma version(1)
#pragma rs java_package_name(edu.ucdavis.crayfis.fishstand)
#pragma rs_fp_relaxed

uint64_t* gHist;

void RS_KERNEL histogram(ushort in) {
    volatile uint32_t * addr = &gHist[in];
    rsAtomicInc(addr);
}
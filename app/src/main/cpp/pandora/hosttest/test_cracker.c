/* Task 4 + review fix: the Pandora code-grabber against ground-truth oracles. */
#include "../cracker.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <assert.h>

static long rf(const char* p, uint8_t** o) {
    FILE* f = fopen(p, "rb"); if(!f) return -1;
    fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    uint8_t* b = malloc(n); if(fread(b,1,n,f)!=(size_t)n){fclose(f);free(b);return -1;}
    fclose(f); *o = b; return n;
}
static void knock_str(const uint8_t* k, int n, char* out) {
    out[0]=0; for(int i=0;i<n;i++){ strcat(out, pa_knock_name(k[i])); if(i+1<n) strcat(out," "); }
}

static void test_detect(void) {
    uint8_t *d=NULL,*m=NULL,*x=NULL;
    long ld=rf("fixtures/d605.bin",&d), lm=rf("fixtures/max.bin",&m), lx=rf("fixtures/dxl5000.bin",&x);
    assert(ld>0&&lm>0&&lx>0);
    int vd=pa_detect_variant(d,ld), vm=pa_detect_variant(m,lm), vx=pa_detect_variant(x,lx);
    printf("  detect: d605=%d max=%d dxl=%d\n", vd, vm, vx);
    assert(vd==PA_VAR_ARM_D605); assert(vm==PA_VAR_ARM_MAX); assert(vx==PA_VAR_PIC_DXL);
    free(d);free(m);free(x);
}
static void test_knock_d605(void) {
    uint8_t* d=NULL; long n=rf("fixtures/d605.bin",&d); assert(n>0);
    uint8_t k[16]; int c=pa_crack_knock(d,n,k,16); char s[64]; knock_str(k,c,s);
    printf("  knock d605: \"%s\" (%d)\n", s, c);
    assert(c==6); assert(strcmp(s,"UP OK OK UP UP DOWN")==0);
    free(d);
}
static void test_knock_max(void) {
    uint8_t* m=NULL; long n=rf("fixtures/max.bin",&m); assert(n>0);
    uint8_t k[16]; int c=pa_crack_knock(m,n,k,16); char s[64]; knock_str(k,c,s);
    printf("  knock max: \"%s\" (%d)\n", s, c);
    assert(c==6); assert(strcmp(s,"UP OK DOWN OK UP DOWN")==0);
    free(m);
}
static void test_pin_dxl(void) {
    uint8_t* x=NULL; long n=rf("fixtures/dxl5000.bin",&x); assert(n>0);
    int pin=pic_crack_pin(x,n,"dxl5000.bin");
    printf("  pin dxl (fast): %d\n", pin);
    assert(pin==2552);
    free(x);
}
static void test_pin_brute_sweep(void) {
    uint8_t* x=NULL; long n=rf("fixtures/dxl5000.bin",&x); assert(n>0);
    int pin=pic_crack_pin_brute(x,n,"dxl5000.bin");   /* forces 0000-9999, RAM restored per attempt */
    printf("  pin dxl (brute sweep): %d\n", pin);
    assert(pin==2552);
    free(x);
}
static void test_pin_none_terminates(void) {
    uint8_t* v=NULL; long n=rf("fixtures/dxl5000_v2.hex",&v); assert(n>0);
    int pin=pic_crack_pin(v,n,"dxl5000_v2.hex");
    printf("  pin v2(no-pin): %d\n", pin);
    assert(pin==-1);
    free(v);
}

int main(void) {
    setvbuf(stdout, NULL, _IONBF, 0);
    test_detect();
    test_knock_d605();
    test_knock_max();
    test_pin_dxl();
    test_pin_brute_sweep();
    test_pin_none_terminates();
    printf("test_cracker: 6/6 PASS\n");
    return 0;
}

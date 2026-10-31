// FoxFW on-phone WPA PMKID cracker — OpenCL GPU backend.
//
// SAFETY MODEL: this .so is NOT linked against libOpenCL at build time. OpenCL is resolved at
// runtime with dlopen("libOpenCL.so"); if it is absent (or anything fails) nativeAvailable() returns
// a negative value and the Kotlin side falls back to the CPU oracle. The GPU result is never trusted
// until GpuCrackBackend self-validates it against the CPU WpaCrack vector on device.
//
// Kernel: for each candidate passphrase, PMK = PBKDF2-HMAC-SHA1(pw, ssid, 4096, 32);
// PMKID = HMAC-SHA1(PMK, "PMK Name"||AP||STA)[0:16]; compare to target; report smallest matching gid.

#include <jni.h>
#include <dlfcn.h>
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <string>
#include <vector>
#include <mutex>
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "FoxGpu", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "FoxGpu", __VA_ARGS__)

// ---- minimal OpenCL ABI (avoid a header dependency the NDK may not ship) ----
typedef int32_t  cl_int;
typedef uint32_t cl_uint;
typedef uint64_t cl_ulong;
typedef cl_ulong cl_device_type;
typedef cl_ulong cl_mem_flags;
typedef cl_uint  cl_bool;
typedef cl_uint  cl_program_build_info;
typedef void* cl_platform_id;
typedef void* cl_device_id;
typedef void* cl_context;
typedef void* cl_command_queue;
typedef void* cl_program;
typedef void* cl_kernel;
typedef void* cl_mem;

#define CL_SUCCESS                0
#define CL_DEVICE_TYPE_GPU        (1u << 2)
#define CL_MEM_READ_ONLY          (1u << 2)
#define CL_MEM_WRITE_ONLY         (1u << 1)
#define CL_MEM_READ_WRITE         (1u << 0)
#define CL_MEM_COPY_HOST_PTR      (1u << 5)
#define CL_TRUE                   1
#define CL_PROGRAM_BUILD_LOG      0x1183

typedef cl_int (*fn_GetPlatformIDs)(cl_uint, cl_platform_id*, cl_uint*);
typedef cl_int (*fn_GetDeviceIDs)(cl_platform_id, cl_device_type, cl_uint, cl_device_id*, cl_uint*);
typedef cl_context (*fn_CreateContext)(const intptr_t*, cl_uint, const cl_device_id*, void*, void*, cl_int*);
typedef cl_command_queue (*fn_CreateCommandQueue)(cl_context, cl_device_id, cl_ulong, cl_int*);
typedef cl_program (*fn_CreateProgramWithSource)(cl_context, cl_uint, const char**, const size_t*, cl_int*);
typedef cl_int (*fn_BuildProgram)(cl_program, cl_uint, const cl_device_id*, const char*, void*, void*);
typedef cl_int (*fn_GetProgramBuildInfo)(cl_program, cl_device_id, cl_program_build_info, size_t, void*, size_t*);
typedef cl_kernel (*fn_CreateKernel)(cl_program, const char*, cl_int*);
typedef cl_mem (*fn_CreateBuffer)(cl_context, cl_mem_flags, size_t, void*, cl_int*);
typedef cl_int (*fn_SetKernelArg)(cl_kernel, cl_uint, size_t, const void*);
typedef cl_int (*fn_EnqueueNDRangeKernel)(cl_command_queue, cl_kernel, cl_uint, const size_t*, const size_t*, const size_t*, cl_uint, const void*, void*);
typedef cl_int (*fn_EnqueueReadBuffer)(cl_command_queue, cl_mem, cl_bool, size_t, size_t, void*, cl_uint, const void*, void*);
typedef cl_int (*fn_Finish)(cl_command_queue);
typedef cl_int (*fn_ReleaseMemObject)(cl_mem);
typedef cl_int (*fn_ReleaseContext)(cl_context);
typedef cl_int (*fn_ReleaseCommandQueue)(cl_command_queue);
typedef cl_int (*fn_ReleaseProgram)(cl_program);
typedef cl_int (*fn_ReleaseKernel)(cl_kernel);

struct Cl {
    void* lib = nullptr;
    fn_GetPlatformIDs           GetPlatformIDs = nullptr;
    fn_GetDeviceIDs             GetDeviceIDs = nullptr;
    fn_CreateContext            CreateContext = nullptr;
    fn_CreateCommandQueue       CreateCommandQueue = nullptr;
    fn_CreateProgramWithSource  CreateProgramWithSource = nullptr;
    fn_BuildProgram             BuildProgram = nullptr;
    fn_GetProgramBuildInfo      GetProgramBuildInfo = nullptr;
    fn_CreateKernel             CreateKernel = nullptr;
    fn_CreateBuffer             CreateBuffer = nullptr;
    fn_SetKernelArg             SetKernelArg = nullptr;
    fn_EnqueueNDRangeKernel     EnqueueNDRangeKernel = nullptr;
    fn_EnqueueReadBuffer        EnqueueReadBuffer = nullptr;
    fn_Finish                   Finish = nullptr;
    fn_ReleaseMemObject         ReleaseMemObject = nullptr;
    fn_ReleaseContext           ReleaseContext = nullptr;
    fn_ReleaseCommandQueue      ReleaseCommandQueue = nullptr;
    fn_ReleaseProgram           ReleaseProgram = nullptr;
    fn_ReleaseKernel            ReleaseKernel = nullptr;

    cl_device_id  device = nullptr;
    cl_context    ctx = nullptr;
    cl_command_queue queue = nullptr;
    cl_program    program = nullptr;
    cl_kernel     kernel = nullptr;
    bool ready = false;
};

static Cl g;

static const char* KERNEL_SRC = R"CLSRC(
#define ROL(x,n) rotate((uint)(x),(uint)(n))

static void sha1_compress(uint* s, const uchar* b){
    uint w[80];
    for(int i=0;i<16;i++)
        w[i]=((uint)b[i*4]<<24)|((uint)b[i*4+1]<<16)|((uint)b[i*4+2]<<8)|((uint)b[i*4+3]);
    for(int i=16;i<80;i++)
        w[i]=ROL(w[i-3]^w[i-8]^w[i-14]^w[i-16],1);
    uint a=s[0],bb=s[1],c=s[2],d=s[3],e=s[4];
    for(int i=0;i<80;i++){
        uint f,k;
        if(i<20){ f=(bb&c)|((~bb)&d); k=0x5A827999u; }
        else if(i<40){ f=bb^c^d; k=0x6ED9EBA1u; }
        else if(i<60){ f=(bb&c)|(bb&d)|(c&d); k=0x8F1BBCDCu; }
        else { f=bb^c^d; k=0xCA62C1D6u; }
        uint t=ROL(a,5)+f+e+k+w[i];
        e=d; d=c; c=ROL(bb,30); bb=a; a=t;
    }
    s[0]+=a; s[1]+=bb; s[2]+=c; s[3]+=d; s[4]+=e;
}

// precompute SHA1 state after the 64-byte ipad/opad key block
static void hmac_init(const uchar* key, int keylen, uint* ipadS, uint* opadS){
    uchar ib[64], ob[64];
    for(int i=0;i<64;i++){ uchar k=(i<keylen)?key[i]:(uchar)0; ib[i]=k^0x36; ob[i]=k^0x5c; }
    ipadS[0]=0x67452301u;ipadS[1]=0xEFCDAB89u;ipadS[2]=0x98BADCFEu;ipadS[3]=0x10325476u;ipadS[4]=0xC3D2E1F0u;
    opadS[0]=0x67452301u;opadS[1]=0xEFCDAB89u;opadS[2]=0x98BADCFEu;opadS[3]=0x10325476u;opadS[4]=0xC3D2E1F0u;
    sha1_compress(ipadS, ib);
    sha1_compress(opadS, ob);
}

// one padded message block (msglen<=55); total message = 64-byte key block + msg
static void pad_block(uchar* blk, const uchar* msg, int msglen, uint totalbits){
    for(int i=0;i<64;i++) blk[i]=0;
    for(int i=0;i<msglen;i++) blk[i]=msg[i];
    blk[msglen]=0x80;
    blk[60]=(uchar)(totalbits>>24);
    blk[61]=(uchar)(totalbits>>16);
    blk[62]=(uchar)(totalbits>>8);
    blk[63]=(uchar)(totalbits);
}

// HMAC-SHA1 of a short msg, given precomputed key states; returns 5 digest words
static void hmac_short(const uint* ipadS, const uint* opadS, const uchar* msg, int msglen, uint* out5){
    uint s[5]; uchar blk[64]; uchar id[20];
    s[0]=ipadS[0];s[1]=ipadS[1];s[2]=ipadS[2];s[3]=ipadS[3];s[4]=ipadS[4];
    pad_block(blk,msg,msglen,(uint)((64+msglen)*8));
    sha1_compress(s,blk);
    for(int i=0;i<5;i++){ id[i*4]=(uchar)(s[i]>>24); id[i*4+1]=(uchar)(s[i]>>16); id[i*4+2]=(uchar)(s[i]>>8); id[i*4+3]=(uchar)s[i]; }
    out5[0]=opadS[0];out5[1]=opadS[1];out5[2]=opadS[2];out5[3]=opadS[3];out5[4]=opadS[4];
    pad_block(blk,id,20,(uint)((64+20)*8));
    sha1_compress(out5,blk);
}

static void pbkdf2_block(const uint* ipadS, const uint* opadS, const uchar* salt, int saltlen, uint idx, uint* T5){
    uchar msg[40];
    for(int i=0;i<saltlen;i++) msg[i]=salt[i];
    msg[saltlen]  =(uchar)(idx>>24);
    msg[saltlen+1]=(uchar)(idx>>16);
    msg[saltlen+2]=(uchar)(idx>>8);
    msg[saltlen+3]=(uchar)(idx);
    uint U[5];
    hmac_short(ipadS,opadS,msg,saltlen+4,U);
    T5[0]=U[0];T5[1]=U[1];T5[2]=U[2];T5[3]=U[3];T5[4]=U[4];
    uchar ub[20];
    for(int it=1; it<4096; it++){
        for(int i=0;i<5;i++){ ub[i*4]=(uchar)(U[i]>>24); ub[i*4+1]=(uchar)(U[i]>>16); ub[i*4+2]=(uchar)(U[i]>>8); ub[i*4+3]=(uchar)U[i]; }
        hmac_short(ipadS,opadS,ub,20,U);
        T5[0]^=U[0];T5[1]^=U[1];T5[2]^=U[2];T5[3]^=U[3];T5[4]^=U[4];
    }
}

__kernel void wpa_pmkid(
    __global const uchar* pw, __global const int* pwlen,
    const int npw, const int pwstride,
    __global const uchar* ssid, const int saltlen,
    __global const uchar* meta,            // [pmkid16][ap6][sta6]
    __global volatile int* found)
{
    int gid = get_global_id(0);
    if(gid>=npw) return;
    int L = pwlen[gid];
    if(L<8 || L>63) return;

    uchar key[64];
    for(int i=0;i<L;i++) key[i]=pw[gid*pwstride+i];

    uint ipadS[5],opadS[5];
    hmac_init(key,L,ipadS,opadS);

    uchar saltbuf[32];
    for(int i=0;i<saltlen;i++) saltbuf[i]=ssid[i];

    uint T1[5],T2[5];
    pbkdf2_block(ipadS,opadS,saltbuf,saltlen,1u,T1);
    pbkdf2_block(ipadS,opadS,saltbuf,saltlen,2u,T2);

    uchar pmk[32];
    for(int i=0;i<5;i++){ pmk[i*4]=(uchar)(T1[i]>>24); pmk[i*4+1]=(uchar)(T1[i]>>16); pmk[i*4+2]=(uchar)(T1[i]>>8); pmk[i*4+3]=(uchar)T1[i]; }
    for(int i=0;i<3;i++){ pmk[20+i*4]=(uchar)(T2[i]>>24); pmk[20+i*4+1]=(uchar)(T2[i]>>16); pmk[20+i*4+2]=(uchar)(T2[i]>>8); pmk[20+i*4+3]=(uchar)T2[i]; }

    uint ip2[5],op2[5];
    hmac_init(pmk,32,ip2,op2);
    uchar m[20];
    m[0]='P';m[1]='M';m[2]='K';m[3]=' ';m[4]='N';m[5]='a';m[6]='m';m[7]='e';
    for(int i=0;i<6;i++) m[8+i]=meta[16+i];
    for(int i=0;i<6;i++) m[14+i]=meta[22+i];
    uint out5[5];
    hmac_short(ip2,op2,m,20,out5);
    uchar id[20];
    for(int i=0;i<5;i++){ id[i*4]=(uchar)(out5[i]>>24); id[i*4+1]=(uchar)(out5[i]>>16); id[i*4+2]=(uchar)(out5[i]>>8); id[i*4+3]=(uchar)out5[i]; }

    bool match=true;
    for(int i=0;i<16;i++) if(id[i]!=meta[i]){ match=false; break; }
    if(match) atomic_min(found, gid);
}
)CLSRC";

static bool load_symbols() {
    const char* names[] = { "libOpenCL.so", "libGLES_mali.so", "libPVROCL.so",
                            "/system/vendor/lib64/libOpenCL.so", "/vendor/lib64/libOpenCL.so" };
    for (const char* n : names) {
        g.lib = dlopen(n, RTLD_NOW | RTLD_LOCAL);
        if (g.lib) { LOGI("dlopen %s ok", n); break; }
    }
    if (!g.lib) { LOGE("no libOpenCL.so"); return false; }
    #define SYM(field, sym) g.field = (fn_##field)dlsym(g.lib, sym); if(!g.field){ LOGE("missing %s", sym); return false; }
    SYM(GetPlatformIDs, "clGetPlatformIDs")
    SYM(GetDeviceIDs, "clGetDeviceIDs")
    SYM(CreateContext, "clCreateContext")
    SYM(CreateCommandQueue, "clCreateCommandQueue")
    SYM(CreateProgramWithSource, "clCreateProgramWithSource")
    SYM(BuildProgram, "clBuildProgram")
    SYM(GetProgramBuildInfo, "clGetProgramBuildInfo")
    SYM(CreateKernel, "clCreateKernel")
    SYM(CreateBuffer, "clCreateBuffer")
    SYM(SetKernelArg, "clSetKernelArg")
    SYM(EnqueueNDRangeKernel, "clEnqueueNDRangeKernel")
    SYM(EnqueueReadBuffer, "clEnqueueReadBuffer")
    SYM(Finish, "clFinish")
    SYM(ReleaseMemObject, "clReleaseMemObject")
    #undef SYM
    // optional (don't fail init if absent) — used only to free cl objects on retry/teardown
    g.ReleaseContext = (fn_ReleaseContext)dlsym(g.lib, "clReleaseContext");
    g.ReleaseCommandQueue = (fn_ReleaseCommandQueue)dlsym(g.lib, "clReleaseCommandQueue");
    g.ReleaseProgram = (fn_ReleaseProgram)dlsym(g.lib, "clReleaseProgram");
    g.ReleaseKernel = (fn_ReleaseKernel)dlsym(g.lib, "clReleaseKernel");
    return true;
}

// Serialises init + all GPU work: the cl context/queue/program/kernel are a single shared instance,
// so concurrent JNI calls must not race on kernel args / buffer release (use-after-free on the GPU).
static std::mutex g_mutex;

static void release_cl() {
    if (g.kernel && g.ReleaseKernel) g.ReleaseKernel(g.kernel);
    if (g.program && g.ReleaseProgram) g.ReleaseProgram(g.program);
    if (g.queue && g.ReleaseCommandQueue) g.ReleaseCommandQueue(g.queue);
    if (g.ctx && g.ReleaseContext) g.ReleaseContext(g.ctx);
    g.kernel = nullptr; g.program = nullptr; g.queue = nullptr; g.ctx = nullptr;
}

static bool init_device() {
    if (g.ready) return true;
    release_cl(); // free any partial objects left by a prior failed attempt (no accumulation on retry)
    if (!g.lib && !load_symbols()) return false;

    cl_uint nplat = 0;
    if (g.GetPlatformIDs(0, nullptr, &nplat) != CL_SUCCESS || nplat == 0) { LOGE("no platforms"); return false; }
    std::vector<cl_platform_id> plats(nplat);
    g.GetPlatformIDs(nplat, plats.data(), nullptr);

    for (auto p : plats) {
        cl_uint ndev = 0;
        if (g.GetDeviceIDs(p, CL_DEVICE_TYPE_GPU, 1, &g.device, &ndev) == CL_SUCCESS && ndev > 0) break;
        g.device = nullptr;
    }
    if (!g.device) { LOGE("no GPU device"); return false; }

    cl_int err = 0;
    g.ctx = g.CreateContext(nullptr, 1, &g.device, nullptr, nullptr, &err);
    if (!g.ctx || err != CL_SUCCESS) { LOGE("CreateContext %d", err); release_cl(); return false; }
    g.queue = g.CreateCommandQueue(g.ctx, g.device, 0, &err);
    if (!g.queue || err != CL_SUCCESS) { LOGE("CreateCommandQueue %d", err); release_cl(); return false; }

    size_t len = strlen(KERNEL_SRC);
    g.program = g.CreateProgramWithSource(g.ctx, 1, &KERNEL_SRC, &len, &err);
    if (!g.program || err != CL_SUCCESS) { LOGE("CreateProgram %d", err); release_cl(); return false; }
    err = g.BuildProgram(g.program, 1, &g.device, "-cl-std=CL1.2", nullptr, nullptr);
    if (err != CL_SUCCESS) {
        char log[4096]; size_t ls = 0;
        g.GetProgramBuildInfo(g.program, g.device, CL_PROGRAM_BUILD_LOG, sizeof(log) - 1, log, &ls);
        log[(ls < sizeof(log)) ? ls : sizeof(log) - 1] = 0;
        LOGE("BuildProgram %d: %s", err, log);
        release_cl(); return false;
    }
    g.kernel = g.CreateKernel(g.program, "wpa_pmkid", &err);
    if (!g.kernel || err != CL_SUCCESS) { LOGE("CreateKernel %d", err); release_cl(); return false; }

    g.ready = true;
    LOGI("OpenCL device ready");
    return true;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_foxfw_companion_GpuCrackBackend_nativeAvailable(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_mutex);
    return init_device() ? 0 : -1;
}

// Crack one batch; returns the (smallest) matching index within the batch, or -1.
extern "C" JNIEXPORT jint JNICALL
Java_com_foxfw_companion_GpuCrackBackend_nativeCrackBatch(
        JNIEnv* env, jobject,
        jbyteArray jpw, jintArray jpwlen, jint stride,
        jbyteArray jssid, jbyteArray jmeta) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (!init_device()) return -1;

    jsize npw = env->GetArrayLength(jpwlen);
    jsize pwBytes = env->GetArrayLength(jpw);
    jsize saltlen = env->GetArrayLength(jssid);
    if (npw <= 0 || saltlen <= 0 || saltlen > 32) return -1;
    if (env->GetArrayLength(jmeta) < 28) return -1;       // kernel reads meta[0..27] (pmkid16+ap6+sta6)
    if ((int64_t)npw * stride > pwBytes) return -1;       // kernel indexes pw[gid*stride + i]

    jbyte* pw = env->GetByteArrayElements(jpw, nullptr);
    jint* pwlen = env->GetIntArrayElements(jpwlen, nullptr);
    jbyte* ssid = env->GetByteArrayElements(jssid, nullptr);
    jbyte* meta = env->GetByteArrayElements(jmeta, nullptr);

    cl_int err = 0;
    int found = 0x7fffffff;
    cl_mem bPw = nullptr, bLen = nullptr, bSsid = nullptr, bMeta = nullptr, bFound = nullptr;
    int result = -1;

    bPw = g.CreateBuffer(g.ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, (size_t)pwBytes, pw, &err);
    bLen = g.CreateBuffer(g.ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, (size_t)npw * sizeof(int), pwlen, &err);
    bSsid = g.CreateBuffer(g.ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, (size_t)(saltlen > 0 ? saltlen : 1), ssid, &err);
    bMeta = g.CreateBuffer(g.ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, 28, meta, &err);
    bFound = g.CreateBuffer(g.ctx, CL_MEM_READ_WRITE | CL_MEM_COPY_HOST_PTR, sizeof(int), &found, &err);
    if (!bPw || !bLen || !bSsid || !bMeta || !bFound) { LOGE("CreateBuffer %d", err); goto cleanup; }

    {
        int ssl = (int)saltlen, np = (int)npw, st = (int)stride;
        g.SetKernelArg(g.kernel, 0, sizeof(cl_mem), &bPw);
        g.SetKernelArg(g.kernel, 1, sizeof(cl_mem), &bLen);
        g.SetKernelArg(g.kernel, 2, sizeof(int), &np);
        g.SetKernelArg(g.kernel, 3, sizeof(int), &st);
        g.SetKernelArg(g.kernel, 4, sizeof(cl_mem), &bSsid);
        g.SetKernelArg(g.kernel, 5, sizeof(int), &ssl);
        g.SetKernelArg(g.kernel, 6, sizeof(cl_mem), &bMeta);
        g.SetKernelArg(g.kernel, 7, sizeof(cl_mem), &bFound);

        size_t global = (size_t)npw;
        err = g.EnqueueNDRangeKernel(g.queue, g.kernel, 1, nullptr, &global, nullptr, 0, nullptr, nullptr);
        if (err != CL_SUCCESS) { LOGE("Enqueue %d", err); goto cleanup; }
        g.Finish(g.queue);
        err = g.EnqueueReadBuffer(g.queue, bFound, CL_TRUE, 0, sizeof(int), &found, 0, nullptr, nullptr);
        if (err != CL_SUCCESS) { LOGE("Read %d", err); goto cleanup; }
        result = (found >= 0 && found < npw) ? found : -1;
    }

cleanup:
    if (bPw) g.ReleaseMemObject(bPw);
    if (bLen) g.ReleaseMemObject(bLen);
    if (bSsid) g.ReleaseMemObject(bSsid);
    if (bMeta) g.ReleaseMemObject(bMeta);
    if (bFound) g.ReleaseMemObject(bFound);
    env->ReleaseByteArrayElements(jpw, pw, JNI_ABORT);
    env->ReleaseIntArrayElements(jpwlen, pwlen, JNI_ABORT);
    env->ReleaseByteArrayElements(jssid, ssid, JNI_ABORT);
    env->ReleaseByteArrayElements(jmeta, meta, JNI_ABORT);
    return result;
}

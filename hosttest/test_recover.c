#include <stdio.h>
#include <stdint.h>
#include <time.h>
extern int fiat_recover(uint32_t uid,int n,const int*btn,const int*ctrl,const uint32_t*hop,int threads,uint8_t out[6]);
extern unsigned long long fiat_recover_scanned(void);
int main(){
  uint32_t uid=0x75E1D628u;
  int btn[4]={2,4,1,2}; int ctrl[4]={345,347,346,343};
  uint32_t hop[4]={0xE1371615u,0xE0F4918Eu,0xE3F649B1u,0xE1E3A735u};
  uint8_t out[6];
  time_t t0=time(0);
  int ok=fiat_recover(uid,4,btn,ctrl,hop,0,out);
  printf("ok=%d clone=%02X%02X_%02X%02X%02X%02X scanned=%llu wall=%llds\n",
         ok,out[0],out[1],out[2],out[3],out[4],out[5],fiat_recover_scanned(),(long long)(time(0)-t0));
  return ok?0:1;
}

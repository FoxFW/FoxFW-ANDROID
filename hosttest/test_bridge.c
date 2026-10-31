#include <stdio.h>
#include <stdint.h>
#include "../app/src/main/cpp/fiat_bridge.h"
int main(){
  uint32_t uid=0x12345678u;
  uint8_t key6[6]={0xB7,0x92,0x80,0xAE,0xCC,0x37};
  int epoch=5;
  int btn[4]={2,4,1,3}, ctrl[4]={345,347,346,343};
  uint32_t hop[4];
  for(int j=0;j<4;j++) hop[j]=fb_output(fb_state_after_init(uid,btn[j],ctrl[j],key6,epoch));
  uint64_t fiat0=fb_state_after_init(uid,btn[0],ctrl[0],key6,epoch);
  uint8_t out[6];
  int ok=fb_check_state(uid, fb_brev48(fiat0), 4, btn, ctrl, hop, out);
  printf("C self-test check=%d clone=%02X%02X_%02X%02X%02X%02X\n",ok,out[0],out[1],out[2],out[3],out[4],out[5]);
  printf("CROSSCHECK uid=%08X btn=%d ctrl=%d epoch=%d key=%02X%02X%02X%02X%02X%02X hop0=%08X\n",
         uid,btn[0],ctrl[0],epoch,key6[0],key6[1],key6[2],key6[3],key6[4],key6[5],hop[0]);
  return ok?0:1;
}

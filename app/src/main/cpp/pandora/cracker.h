/* =====================================================================
 * cracker.h - the Pandora "code-grabber": recover a keyfob's unlock code
 * from its firmware dump. Ported from applications/system/PandoraPINBrute:
 *   - ARM (D-605 / MAX): static pattern-match of the Thumb-2 knock scanner
 *     at the profile scan_head -> knock sequence (UP/OK/DOWN/...).
 *   - PIC (DXL-5000): read the hardcoded compare literals in the PIN
 *     validator (fast path), else brute 0000-9999 via the REAL validation.
 * Pure C; the PIC path reuses pic_emu / pic18_core.
 * ===================================================================== */
#ifndef CRACKER_H
#define CRACKER_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Firmware variant. */
enum { PA_VAR_UNKNOWN = 0, PA_VAR_ARM_D605 = 1, PA_VAR_ARM_MAX = 2, PA_VAR_PIC_DXL = 3 };

/* ARM knock button codes (match PandoraPINBrute AK_* + PandoraARM PA_B_*). */
enum { AK_UP = 0, AK_DOWN = 1, AK_OK = 2, AK_BACK = 3, AK_B5 = 4, AK_B6 = 5 };

/* Classify a raw firmware image (ARM EFM32 initial-SP heuristic vs PIC). */
int pa_detect_variant(const uint8_t* fw, uint32_t len);

/* ARM: recover the knock sequence into out[] (AK_* codes), returns count (0 =
 * not found). Operates on a flat .bin image (addr == file offset). */
int pa_crack_knock(const uint8_t* fw, uint32_t len, uint8_t* out, int maxout);

/* Token name ("UP".."B6") for an AK_* code. */
const char* pa_knock_name(int ak);

/* PIC: recover the numeric PIN (0..9999), or -1 if not found / not a PIN fw.
 * `name` is the file name (profile hint); may be NULL. */
int pic_crack_pin(const uint8_t* fw, uint32_t len, const char* name);

/* Test-only: force the brute sweep (skip the literal fast path). */
int pic_crack_pin_brute(const uint8_t* fw, uint32_t len, const char* name);

#ifdef __cplusplus
}
#endif
#endif /* CRACKER_H */

cmake_minimum_required(VERSION 3.22.1)
project(foxsolver C CXX)

# Fiat/Hitag2: dictionary (fiat_dict) + real hitag2hell bitslice (h2bitslice) full search (fiat_full).
# The old fiat_recover.c guess-and-determine search was buggy and is no longer built.
add_library(foxsolver SHARED native_solver.c fiat_dict.c fiat_full.c h2bitslice.c hitag2_seed.c)
target_compile_options(foxsolver PRIVATE -O3 -fno-strict-aliasing)

# ChameleonUltra MIFARE-Classic crypto (vendored onyx crapto1 + darkside + mfkey32)
add_library(chamcrypto SHARED
    cham/cham_jni.cpp
    cham/signal/darkside.cpp
    cham/signal/mfkey32_lowmem.cpp
    cham/signal/crapto1/crapto1.c
    cham/signal/crapto1/crypto1.c
    cham/signal/crapto1/bucketsort.c)
target_include_directories(chamcrypto PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/cham)
target_compile_options(chamcrypto PRIVATE -O2 -fno-strict-aliasing)
set_target_properties(chamcrypto PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)

# Pandora keyfob emulator (D-605 + MAX): forge command frames + decode captures on-device
add_library(pandora SHARED
    pandora/pandora_jni.c
    pandora/pa_emu.c
    pandora/pa_rf.c
    pandora/thumb_core.c
    pandora/pic18_core.c
    pandora/ihex.c
    pandora/pic_emu.c
    pandora/cracker.c
    pandora/crack_jni.c)
target_include_directories(pandora PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/pandora)
target_compile_options(pandora PRIVATE -O2 -fno-strict-aliasing)

# On-phone WPA PMKID cracker, GPU backend. NOT linked against libOpenCL: it is dlopen()'d at runtime
# (see gpu_crack.cpp), so this .so always loads and degrades to the CPU oracle when no GPU is present.
add_library(foxgpu SHARED foxgpu/gpu_crack.cpp)
target_link_libraries(foxgpu log dl)
target_compile_options(foxgpu PRIVATE -O2)
set_target_properties(foxgpu PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)

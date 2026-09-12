cmake_minimum_required(VERSION 3.22.1)

# Optional: rebuild the LD_PRELOAD payload from the vendored sources with
# the NDK toolchain that AGP provides. The su_daemon PIE executable and ksud
# are shipped as assets (see exploit/Makefile + stage-assets.sh) because a
# CMake executable target is not packaged into the APK.
#
#   exploit/  -> vendored from https://github.com/1ndevelopment/ghostlock-s26
#   assets: preload.so + su_daemon staged by stage-assets.sh (needs NDK r26+)

project("ghostlock-jni")

add_library(
        preload
        SHARED
        ../../../../exploit/src/main.c
        ../../../../exploit/src/util.c
        ../../../../exploit/src/bootclaim.c
        ../../../../exploit/src/slide.c
        ../../../../exploit/src/fops.c
        ../../../../exploit/src/attr.c
        ../../../../exploit/src/root.c
        ../../../../exploit/src/params.c
        ../../../../exploit/src/params_table.c
        ../../../../exploit/src/preload.c)

target_include_directories(preload PRIVATE ../../../../exploit/src)
target_compile_options(preload PRIVATE -O2 -Wall -Wextra
        -Wno-unused-parameter -Wno-sign-compare -Wno-unused-function)
target_link_libraries(preload log)
set_target_properties(preload PROPERTIES OUTPUT_NAME preload)

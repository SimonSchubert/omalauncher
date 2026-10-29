cmake_minimum_required(VERSION 3.22)
project(wlbridge C)
add_library(wlbridge SHARED wlbridge.c)
# Pixel copies and compositing run per frame; debug builds would otherwise use -O0.
target_compile_options(wlbridge PRIVATE -O3 -Wall)
target_link_libraries(wlbridge android log)
# android.airpods' L2CAP socket, see aap.c.
add_library(aap SHARED aap.c)
target_compile_options(aap PRIVATE -Wall)
target_link_libraries(aap log)

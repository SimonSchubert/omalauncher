cmake_minimum_required(VERSION 3.22)
project(wlbridge C)
add_library(wlbridge SHARED wlbridge.c)
# Pixel copies and compositing run per frame; debug builds would otherwise use -O0.
target_compile_options(wlbridge PRIVATE -O3 -Wall)
target_link_libraries(wlbridge android log)
# A 3D scene's triangles, see scene3d.c. No fused multiply-adds: its numbers are the same as the Kotlin's it stands in for.
add_library(scene3d SHARED scene3d.c)
target_compile_options(scene3d PRIVATE -O3 -Wall -ffp-contract=off)
target_link_libraries(scene3d m)
# android.airpods' L2CAP socket, see aap.c.
add_library(aap SHARED aap.c)
target_compile_options(aap PRIVATE -Wall)
target_link_libraries(aap log)

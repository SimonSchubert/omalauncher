cmake_minimum_required(VERSION 3.22)
project(wlbridge C)
add_library(wlbridge SHARED wlbridge.c)
# Pixel copies and compositing run per frame; debug builds would otherwise use -O0.
target_compile_options(wlbridge PRIVATE -O3 -Wall)
target_link_libraries(wlbridge android log)
# A 3D scene's triangles and the building of its meshes, see scene3d.c and mesh3d.c. No fused multiply-adds: its numbers are the same as the Kotlin's it stands in for.
add_library(scene3d SHARED scene3d.c mesh3d.c)
target_compile_options(scene3d PRIVATE -O3 -Wall -ffp-contract=off)
target_link_libraries(scene3d m)
# Frost's blur and icon masks, see pixels.c. Its numbers are the Kotlin's too.
add_library(pixels SHARED pixels.c)
target_compile_options(pixels PRIVATE -O3 -Wall -ffp-contract=off)
# ProcShim's /proc/stat, see procstat.c.
add_library(procstat SHARED procstat.c)
target_compile_options(procstat PRIVATE -O2 -Wall)
# android.airpods' L2CAP socket, see aap.c.
add_library(aap SHARED aap.c)
target_compile_options(aap PRIVATE -Wall)
target_link_libraries(aap log)

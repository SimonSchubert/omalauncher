cmake_minimum_required(VERSION 3.22)
project(wlbridge C)
add_library(wlbridge SHARED wlbridge.c)
# Pixel copies and compositing run per frame; debug builds would otherwise use -O0.
target_compile_options(wlbridge PRIVATE -O3 -Wall)
target_link_libraries(wlbridge android log)

# Static bionic also runs inside the glibc guest, without exposing Android's linker.
# The lib*.so filename lets Android extract the executable into nativeLibraryDir.
add_executable(ando ando.c ando_broker.c)
set_target_properties(ando PROPERTIES OUTPUT_NAME libando.so
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
target_compile_options(ando PRIVATE -O2 -Wall -Wextra -Werror)
target_link_options(ando PRIVATE -static -Wl,-z,max-page-size=16384)

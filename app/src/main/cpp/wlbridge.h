// Shared by wlbridge.c (sockets, shm, compositing) and ahb.c (AHardwareBuffers).
#pragma once
#include <android/native_window.h>
#include <stdint.h>

#define WL_TAG "wlbridge"

// One Linux window's Android surface. A frame is begin (lock the dirty rect,
// clear it), a blit per visible surface, bottom to top, then end (post).
typedef struct {
    ANativeWindow *window;
    ANativeWindow_Buffer buf;
    ARect dirty;
    int locked;
    // Keeps alpha, premultiplied, for what's behind the view to show through.
    int translucent;
} Window;

// Draws sw x sh RGBA pixels (premultiplied, rows [stride] pixels apart) at
// (dx, dy) of the locked window, scaled to dw x dh, clipped to the locked rect.
// [translucent]: some pixel may have alpha below 255. [force_opaque]: draw
// them as opaque anyway, alpha forced to 255 (an X format).
void wl_blit(Window *win, const uint32_t *px, int sw, int sh, int stride, int translucent, int dx, int dy, int dw, int dh,
             int force_opaque);

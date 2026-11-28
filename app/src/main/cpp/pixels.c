// Work over a picture's pixels, as Kotlin's IntArrays of ARGB: the blur behind frosted widgets (FrostCache in
// widgets/render/Frost.kt) and an icon's brightness as its mask (ui/IconMask.kt). Ports of that Kotlin, which stays
// for where the library isn't there; the two give the same pixels, which PixelsNativeTest holds them to.
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

#define FN(name) Java_dev_omalauncher_ui_PixelsNative_##name

static int clamp(int i, int len) { return i < 0 ? 0 : i >= len ? len - 1 : i; }

// FrostCache.box: one pass of a box blur along lines of len pixels, step apart within a line and stride between lines.
// div[x] is x / (2 * r + 1): a division per channel and pixel was most of the time.
static void box(const uint32_t *src, uint32_t *dst, int len, int lines, int r, int step, int stride, const uint8_t *div) {
    for (int line = 0; line < lines; line++) {
        const uint32_t *s = src + (size_t)line * stride;
        uint32_t *d = dst + (size_t)line * stride;
        int a = 0, red = 0, g = 0, b = 0;
        for (int i = -r; i <= r; i++) {
            uint32_t c = s[(size_t)clamp(i, len) * step];
            a += c >> 24;
            red += (c >> 16) & 0xff;
            g += (c >> 8) & 0xff;
            b += c & 0xff;
        }
        for (int i = 0; i < len; i++) {
            d[(size_t)i * step] = (uint32_t)div[a] << 24 | (uint32_t)div[red] << 16 | (uint32_t)div[g] << 8 | div[b];
            uint32_t out = s[(size_t)clamp(i - r, len) * step], in = s[(size_t)clamp(i + r + 1, len) * step];
            a += (int)(in >> 24) - (int)(out >> 24);
            red += (int)((in >> 16) & 0xff) - (int)((out >> 16) & 0xff);
            g += (int)((in >> 8) & 0xff) - (int)((out >> 8) & 0xff);
            b += (int)(in & 0xff) - (int)(out & 0xff);
        }
    }
}

// FrostCache.blur: three box blurs each way, in place. False when it couldn't.
JNIEXPORT jboolean JNICALL FN(blur)(JNIEnv *env, jclass cls, jintArray jpx, jint w, jint h, jint radius) {
    // Frost's radius is 1 to 24.
    if (w <= 0 || h <= 0 || radius < 0 || radius > 1000 || (int64_t)w * h > (*env)->GetArrayLength(env, jpx)) return JNI_FALSE;
    size_t size = (size_t)w * h;
    int n = 2 * radius + 1;
    uint32_t *px = malloc(sizeof(uint32_t) * size), *tmp = malloc(sizeof(uint32_t) * size);
    // A sum is at most n times 255.
    uint8_t *div = malloc((size_t)255 * n + 1);
    if (!px || !tmp || !div) {
        free(px);
        free(tmp);
        free(div);
        return JNI_FALSE;
    }
    for (int x = 0; x <= 255 * n; x++) div[x] = (uint8_t)(x / n);
    (*env)->GetIntArrayRegion(env, jpx, 0, (jsize)size, (jint *)px);
    for (int k = 0; k < 3; k++) {
        box(px, tmp, w, h, radius, 1, w, div);
        box(tmp, px, h, w, radius, w, 1, div);
    }
    (*env)->SetIntArrayRegion(env, jpx, 0, (jsize)size, (const jint *)px);
    free(px);
    free(tmp);
    free(div);
    return JNI_TRUE;
}

// IconMask's brightnessToAlpha: 1 with pixels turned into the mask, 0 for a black logo (pixels left as they were), -1
// when it couldn't.
JNIEXPORT jint JNICALL FN(brightnessToAlpha)(JNIEnv *env, jclass cls, jintArray jpx, jint blackLogo) {
    jsize n = (*env)->GetArrayLength(env, jpx);
    uint32_t *px = malloc(sizeof(uint32_t) * (n ? n : 1));
    if (!px) return -1;
    (*env)->GetIntArrayRegion(env, jpx, 0, n, (jint *)px);
    // Each channel's share of the brightness, the same doubles as 0.3 * r and so on: added in the Kotlin's order, they
    // give its sums.
    double rs[256], gs[256], bs[256];
    for (int c = 0; c < 256; c++) {
        rs[c] = 0.3 * c;
        gs[c] = 0.59 * c;
        bs[c] = 0.11 * c;
    }
    int max = 1;
    // Each pixel's value in place, at most 255: no second array.
    for (jsize i = 0; i < n; i++) {
        uint32_t p = px[i];
        int lum = (int)(rs[(p >> 16) & 0xff] + gs[(p >> 8) & 0xff] + bs[p & 0xff]);
        int v = (int)(p >> 24) * lum / 255;
        px[i] = (uint32_t)v;
        if (v > max) max = v;
    }
    int changed = max >= blackLogo;
    if (changed) {
        uint32_t alpha[256];
        for (int v = 0; v < 256; v++) alpha[v] = (uint32_t)(v * 255 / max) << 24 | 0xffffff;
        for (jsize i = 0; i < n; i++) px[i] = alpha[px[i]];
        (*env)->SetIntArrayRegion(env, jpx, 0, n, (const jint *)px);
    }
    free(px);
    return changed;
}

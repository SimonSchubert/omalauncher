// The per-triangle work of a 3D scene (owl's Scene3d): a model's points into camera coordinates, its faces culled,
// lit, fogged, cut at the near plane and projected, and the frame's triangles sorted far to near. A port of the
// Kotlin in owl/.../widgets/lang/Space3d.kt, which stays the reference and is what runs off the phone; the two give
// the same numbers (the build turns fused multiply-adds off for that), which SceneNativeTest holds them to.
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#define FN(name) Java_dev_omalauncher_widgets_render_SceneNative_##name

// Scene3d's MAX_SCENE_TRIANGLES.
#define MAX_TRIANGLES 60000

// The floats add() takes, see NativeKernel in Kotlin.
enum {
    V_C = 0, // 9: the mesh's points to camera coordinates
    V_O = 9, // 3: and the offset added
    V_N = 12, // 9: the same for normals
    V_NEAR = 21,
    V_FAR,
    V_TX,
    V_TY,
    V_FOCAL,
    V_W,
    V_H,
    V_LX,
    V_LY,
    V_LZ,
    V_AMBIENT,
    V_FOG_FROM,
    V_FOG_R,
    V_FOG_G,
    V_FOG_B,
    V_TEX_W,
    V_TEX_H,
    V_COUNT,
};

enum { TWO_SIDED = 1, FOG = 2, OWN_ORDER = 4 };

typedef struct {
    // Staged triangles: screen x, y of each corner, their colors and uvs, depth and texture.
    int n, cap;
    float *sxy;
    int32_t *cols;
    float *uv;
    float *depth;
    int32_t *tex;
    // A model's points in camera coordinates.
    int pcap;
    float *px, *py, *pz;
    // The sort's keys and order, and their other halves.
    int scap;
    uint32_t *key[2];
    int32_t *idx[2];
    // Runs of triangles with one texture, three ints each: first, count, texture.
    int batches, bcap;
    int32_t *batch;
} Scene;

static int grow(Scene *s) {
    int cap = s->cap ? s->cap * 2 : 256;
    float *sxy = realloc(s->sxy, sizeof(float) * 6 * cap);
    if (sxy) s->sxy = sxy;
    int32_t *cols = realloc(s->cols, sizeof(int32_t) * 3 * cap);
    if (cols) s->cols = cols;
    float *uv = realloc(s->uv, sizeof(float) * 6 * cap);
    if (uv) s->uv = uv;
    float *depth = realloc(s->depth, sizeof(float) * cap);
    if (depth) s->depth = depth;
    int32_t *tex = realloc(s->tex, sizeof(int32_t) * cap);
    if (tex) s->tex = tex;
    if (!sxy || !cols || !uv || !depth || !tex) return 0;
    s->cap = cap;
    return 1;
}

// Kotlin's (v + 0.5f).toInt().coerceIn(0, 255): not a number is 0.
static inline int32_t channel(float v) {
    v += 0.5f;
    if (!(v > 0.0f)) return 0;
    if (v >= 255.0f) return 255;
    return (int32_t)v;
}

// Kotlin's maxOf(0f, x), which keeps not-a-number.
static inline float max0(float x) { return x > 0.0f ? x : (x != x ? x : 0.0f); }

// Projects the corner at src (x, y, d, r, g, b, a, u, v) as corner k of the triangle being staged.
static inline void project(Scene *s, const float *v, const float *src, int k) {
    int n = s->n;
    float f = v[V_FOCAL] / src[2];
    s->sxy[6 * n + 2 * k] = v[V_W] / 2 + src[0] * f;
    s->sxy[6 * n + 2 * k + 1] = v[V_H] / 2 - src[1] * f;
    s->cols[3 * n + k] = (channel(src[6]) << 24) | (channel(src[3]) << 16) | (channel(src[4]) << 8) | channel(src[5]);
    s->uv[6 * n + 2 * k] = src[7] * v[V_TEX_W];
    s->uv[6 * n + 2 * k + 1] = src[8] * v[V_TEX_H];
}

// 1 staged, 0 when the scene is full or memory ran out.
static inline int stage(Scene *s, const float *v, const float *a, const float *b, const float *c, int32_t texIndex) {
    if (s->n >= MAX_TRIANGLES) return 0;
    if (s->n == s->cap && !grow(s)) return 0;
    project(s, v, a, 0);
    project(s, v, b, 1);
    project(s, v, c, 2);
    float d = a[2] + b[2] + c[2];
    s->depth[s->n] = d / 3;
    s->tex[s->n] = texIndex;
    s->n++;
    return 1;
}

// Java's Float.compare, turned round: far to near.
static int farFirst(const void *pa, const void *pb) {
    float a = *(const float *)pa, b = *(const float *)pb;
    if (a > b) return -1;
    if (a < b) return 1;
    int32_t x, y;
    if (a != a) x = 0x7fc00000; else memcpy(&x, &a, 4);
    if (b != b) y = 0x7fc00000; else memcpy(&y, &b, 4);
    return x > y ? -1 : x < y;
}

static int add(Scene *s, const float *v, int32_t color, int flags, int32_t texIndex, int triangles, int points, const float *pts,
               const int32_t *tris, const float *fn, const float *cn, const float *uvs, const int32_t *ci, const int32_t *palette,
               int paletteSize, const int32_t *order) {
    if (points > s->pcap) {
        free(s->px);
        free(s->py);
        free(s->pz);
        s->px = malloc(sizeof(float) * points);
        s->py = malloc(sizeof(float) * points);
        s->pz = malloc(sizeof(float) * points);
        s->pcap = s->px && s->py && s->pz ? points : 0;
        if (!s->pcap) return 0;
    }
    float *px = s->px, *py = s->py, *pz = s->pz;
    const float *c = v + V_C, *o = v + V_O, *nm = v + V_N;
    for (int i = 0; i < points; i++) {
        float x = pts[3 * i], y = pts[3 * i + 1], z = pts[3 * i + 2];
        px[i] = c[0] * x + c[1] * y + c[2] * z + o[0];
        py[i] = c[3] * x + c[4] * y + c[5] * z + o[1];
        pz[i] = c[6] * x + c[7] * y + c[8] * z + o[2];
    }
    const float near = v[V_NEAR], far = v[V_FAR], tx = v[V_TX], ty = v[V_TY];
    const float lx = v[V_LX], ly = v[V_LY], lz = v[V_LZ], amb = v[V_AMBIENT];
    const float fogFrom = v[V_FOG_FROM], fogR = v[V_FOG_R], fogG = v[V_FOG_G], fogB = v[V_FOG_B];
    const int twoSided = flags & TWO_SIDED, fog = flags & FOG;
    const int textured = uvs && texIndex >= 0;
    const int first = s->n;
    float corner[27], poly[36];
    memset(corner, 0, sizeof corner);
    for (int q = 0; q < triangles; q++) {
        int t = order ? order[q] : q;
        int32_t ia = tris[3 * t], ib = tris[3 * t + 1], ic = tris[3 * t + 2];
        float da = pz[ia], db = pz[ib], dc = pz[ic];
        if (da < near && db < near && dc < near) continue;
        if (da > far && db > far && dc > far) continue;
        // All on the outside of one side of the view.
        if (px[ia] > tx * da && px[ib] > tx * db && px[ic] > tx * dc) continue;
        if (-px[ia] > tx * da && -px[ib] > tx * db && -px[ic] > tx * dc) continue;
        if (py[ia] > ty * da && py[ib] > ty * db && py[ic] > ty * dc) continue;
        if (-py[ia] > ty * da && -py[ib] > ty * db && -py[ic] > ty * dc) continue;
        const float *f = fn + 3 * t;
        float fx = nm[0] * f[0] + nm[1] * f[1] + nm[2] * f[2];
        float fy = nm[3] * f[0] + nm[4] * f[1] + nm[5] * f[2];
        float fz = nm[6] * f[0] + nm[7] * f[1] + nm[8] * f[2];
        // Turned away from the eye, which is at the camera's origin: its back shows, or nothing.
        int back = fx * px[ia] + fy * py[ia] + fz * pz[ia] >= 0.0f;
        if (back && !twoSided) continue;
        float flip = back ? -1.0f : 1.0f;
        int32_t pi = ci ? ci[t] : -1;
        uint32_t base = (uint32_t)(pi >= 0 && palette && pi < paletteSize ? palette[pi] : color);
        float alpha = (float)((base >> 24) & 0xff);
        float br = (float)((base >> 16) & 0xff);
        float bg = (float)((base >> 8) & 0xff);
        float bb = (float)(base & 0xff);
        float light = 0.0f;
        if (!cn) {
            float l = sqrtf(fx * fx + fy * fy + fz * fz);
            if (!(l > 0.0f)) l = 1.0f;
            fx *= flip / l;
            fy *= flip / l;
            fz *= flip / l;
            light = amb + (1 - amb) * max0(fx * lx + fy * ly + fz * lz);
        }
        for (int k = 0; k < 3; k++) {
            int32_t p = tris[3 * t + k];
            float *out = corner + 9 * k;
            out[0] = px[p];
            out[1] = py[p];
            out[2] = pz[p];
            float lit = light;
            if (cn) {
                const float *m = cn + 9 * t + 3 * k;
                float nx = nm[0] * m[0] + nm[1] * m[1] + nm[2] * m[2];
                float ny = nm[3] * m[0] + nm[4] * m[1] + nm[5] * m[2];
                float nz = nm[6] * m[0] + nm[7] * m[1] + nm[8] * m[2];
                float l = sqrtf(nx * nx + ny * ny + nz * nz);
                if (!(l > 0.0f)) l = 1.0f;
                lit = amb + (1 - amb) * max0((nx * lx + ny * ly + nz * lz) * flip / l);
            }
            float rr = br * lit, gg = bg * lit, bl = bb * lit;
            if (fog) {
                float g = (pz[p] - fogFrom) / (far - fogFrom);
                g = g < 0.0f ? 0.0f : g > 1.0f ? 1.0f : g;
                rr += (fogR - rr) * g;
                gg += (fogG - gg) * g;
                bl += (fogB - bl) * g;
            }
            out[3] = rr;
            out[4] = gg;
            out[5] = bl;
            out[6] = alpha;
            if (textured) {
                out[7] = uvs[6 * t + 2 * k];
                out[8] = uvs[6 * t + 2 * k + 1];
            }
        }
        if (da >= near && db >= near && dc >= near) {
            if (!stage(s, v, corner, corner + 9, corner + 18, texIndex)) return 0;
        } else {
            // Cut at the near plane: what's left is a triangle or a quad.
            int m = 0;
            for (int k = 0; k < 3; k++) {
                const float *i = corner + 9 * k, *j = corner + 9 * ((k + 1) % 3);
                int ina = i[2] >= near, inb = j[2] >= near;
                if (ina) {
                    memcpy(poly + 9 * m, i, sizeof(float) * 9);
                    m++;
                }
                if (ina != inb) {
                    float w = (near - i[2]) / (j[2] - i[2]);
                    for (int e = 0; e < 9; e++) poly[9 * m + e] = i[e] + (j[e] - i[e]) * w;
                    m++;
                }
            }
            if (m >= 3 && !stage(s, v, poly, poly + 9, poly + 18, texIndex)) return 0;
            if (m == 4 && !stage(s, v, poly, poly + 18, poly + 27, texIndex)) return 0;
        }
    }
    // The tree's order stands within the model: its faces take the depths they would have had, nearest last, so
    // across models the scene still sorts by depth.
    if ((flags & OWN_ORDER) && s->n - first > 1) qsort(s->depth + first, s->n - first, sizeof(float), farFirst);
    return 1;
}

// The staged triangles far to near into idx[0], ties in the order they came in: a radix sort of the depths' bits,
// which for the positive numbers depths are sort as the numbers do (and as Kotlin's sort of them as longs does).
static int sort(Scene *s) {
    int n = s->n;
    if (n > s->scap) {
        for (int i = 0; i < 2; i++) {
            free(s->key[i]);
            free(s->idx[i]);
            s->key[i] = malloc(sizeof(uint32_t) * s->cap);
            s->idx[i] = malloc(sizeof(int32_t) * s->cap);
        }
        s->scap = s->key[0] && s->key[1] && s->idx[0] && s->idx[1] ? s->cap : 0;
        if (!s->scap) return 0;
    }
    uint32_t *key = s->key[0], *key2 = s->key[1];
    int32_t *idx = s->idx[0], *idx2 = s->idx[1];
    // Far first is the keys from high to low: sorted from low to high here on their complement, which a stable
    // sort leaves in coming-in order where depths are the same.
    for (int i = 0; i < n; i++) {
        uint32_t bits;
        memcpy(&bits, &s->depth[i], 4);
        key[i] = ~(bits ^ 0x80000000u);
        idx[i] = i;
    }
    if (n < 48) {
        for (int i = 1; i < n; i++) {
            uint32_t k = key[i];
            int32_t x = idx[i];
            int j = i - 1;
            for (; j >= 0 && key[j] > k; j--) {
                key[j + 1] = key[j];
                idx[j + 1] = idx[j];
            }
            key[j + 1] = k;
            idx[j + 1] = x;
        }
        return 1;
    }
    static const int SHIFT[3] = {0, 11, 22};
    static const int BITS[3] = {11, 11, 10};
    for (int pass = 0; pass < 3; pass++) {
        uint32_t count[2048];
        int buckets = 1 << BITS[pass];
        uint32_t mask = (uint32_t)buckets - 1;
        memset(count, 0, sizeof(uint32_t) * buckets);
        for (int i = 0; i < n; i++) count[(key[i] >> SHIFT[pass]) & mask]++;
        uint32_t at = 0;
        for (int b = 0; b < buckets; b++) {
            uint32_t c = count[b];
            count[b] = at;
            at += c;
        }
        for (int i = 0; i < n; i++) {
            uint32_t to = count[(key[i] >> SHIFT[pass]) & mask]++;
            key2[to] = key[i];
            idx2[to] = idx[i];
        }
        uint32_t *k = key;
        key = key2;
        key2 = k;
        int32_t *x = idx;
        idx = idx2;
        idx2 = x;
    }
    // Three passes: the sorted halves are the other ones now.
    s->key[0] = key;
    s->key[1] = key2;
    s->idx[0] = idx;
    s->idx[1] = idx2;
    return 1;
}

JNIEXPORT jlong JNICALL FN(create)(JNIEnv *env, jclass cls) { return (jlong)(intptr_t)calloc(1, sizeof(Scene)); }

JNIEXPORT void JNICALL FN(destroy)(JNIEnv *env, jclass cls, jlong ptr) {
    Scene *s = (Scene *)(intptr_t)ptr;
    if (!s) return;
    free(s->sxy);
    free(s->cols);
    free(s->uv);
    free(s->depth);
    free(s->tex);
    free(s->px);
    free(s->py);
    free(s->pz);
    for (int i = 0; i < 2; i++) {
        free(s->key[i]);
        free(s->idx[i]);
    }
    free(s->batch);
    free(s);
}

JNIEXPORT void JNICALL FN(reset)(JNIEnv *env, jclass cls, jlong ptr) {
    Scene *s = (Scene *)(intptr_t)ptr;
    s->n = 0;
    s->batches = 0;
}

// Adds a model's triangles; the staged count so far, -1 when the scene is full, -2 for arrays that don't fit the mesh.
JNIEXPORT jint JNICALL FN(add)(JNIEnv *env, jclass cls, jlong ptr, jfloatArray jv, jint color, jint flags, jint texIndex, jint triangles,
                               jfloatArray jpoints, jintArray jtris, jfloatArray jfn, jfloatArray jcn, jfloatArray juvs, jintArray jci,
                               jintArray jpalette, jintArray jorder) {
    Scene *s = (Scene *)(intptr_t)ptr;
    jsize points = (*env)->GetArrayLength(env, jpoints) / 3;
    jsize paletteSize = jpalette ? (*env)->GetArrayLength(env, jpalette) : 0;
    if (triangles < 0 || (*env)->GetArrayLength(env, jv) < V_COUNT || (*env)->GetArrayLength(env, jtris) < 3 * triangles ||
        (*env)->GetArrayLength(env, jfn) < 3 * triangles || (jcn && (*env)->GetArrayLength(env, jcn) < 9 * triangles) ||
        (juvs && (*env)->GetArrayLength(env, juvs) < 6 * triangles) || (jci && (*env)->GetArrayLength(env, jci) < triangles) ||
        (jorder && (*env)->GetArrayLength(env, jorder) < triangles))
        return -2;
    // Nothing between here and the releases calls back into the VM.
    float *v = (*env)->GetPrimitiveArrayCritical(env, jv, NULL);
    float *pts = (*env)->GetPrimitiveArrayCritical(env, jpoints, NULL);
    int32_t *tris = (*env)->GetPrimitiveArrayCritical(env, jtris, NULL);
    float *fn = (*env)->GetPrimitiveArrayCritical(env, jfn, NULL);
    float *cn = jcn ? (*env)->GetPrimitiveArrayCritical(env, jcn, NULL) : NULL;
    float *uvs = juvs ? (*env)->GetPrimitiveArrayCritical(env, juvs, NULL) : NULL;
    int32_t *ci = jci ? (*env)->GetPrimitiveArrayCritical(env, jci, NULL) : NULL;
    int32_t *palette = jpalette ? (*env)->GetPrimitiveArrayCritical(env, jpalette, NULL) : NULL;
    int32_t *order = jorder ? (*env)->GetPrimitiveArrayCritical(env, jorder, NULL) : NULL;
    int ok = v && pts && tris && fn && (!jcn || cn) && (!juvs || uvs) && (!jci || ci) && (!jpalette || palette) && (!jorder || order) &&
             add(s, v, color, flags, texIndex, triangles, points, pts, tris, fn, cn, uvs, ci, palette, paletteSize, order);
    if (order) (*env)->ReleasePrimitiveArrayCritical(env, jorder, order, JNI_ABORT);
    if (palette) (*env)->ReleasePrimitiveArrayCritical(env, jpalette, palette, JNI_ABORT);
    if (ci) (*env)->ReleasePrimitiveArrayCritical(env, jci, ci, JNI_ABORT);
    if (uvs) (*env)->ReleasePrimitiveArrayCritical(env, juvs, uvs, JNI_ABORT);
    if (cn) (*env)->ReleasePrimitiveArrayCritical(env, jcn, cn, JNI_ABORT);
    if (fn) (*env)->ReleasePrimitiveArrayCritical(env, jfn, fn, JNI_ABORT);
    if (tris) (*env)->ReleasePrimitiveArrayCritical(env, jtris, tris, JNI_ABORT);
    if (pts) (*env)->ReleasePrimitiveArrayCritical(env, jpoints, pts, JNI_ABORT);
    if (v) (*env)->ReleasePrimitiveArrayCritical(env, jv, v, JNI_ABORT);
    return ok ? s->n : -1;
}

// Sorts the staged triangles far to near into verts, texs and colors, which have room for them (six floats and three
// colors each), and their runs by texture into batches, three ints each: first, count, texture. The number of runs:
// when batches is too short for them it's left alone, to be asked for again with a longer one. -1 out of memory.
JNIEXPORT jint JNICALL FN(finish)(JNIEnv *env, jclass cls, jlong ptr, jfloatArray jverts, jfloatArray jtexs, jintArray jcolors,
                                  jintArray jbatches) {
    Scene *s = (Scene *)(intptr_t)ptr;
    int n = s->n;
    if ((*env)->GetArrayLength(env, jverts) < 6 * n || (*env)->GetArrayLength(env, jtexs) < 6 * n ||
        (*env)->GetArrayLength(env, jcolors) < 3 * n)
        return -1;
    if (n > 0 && s->batches == 0) {
        if (!sort(s)) return -1;
        float *verts = (*env)->GetPrimitiveArrayCritical(env, jverts, NULL);
        float *texs = (*env)->GetPrimitiveArrayCritical(env, jtexs, NULL);
        int32_t *colors = (*env)->GetPrimitiveArrayCritical(env, jcolors, NULL);
        int ok = verts && texs && colors;
        const int32_t *idx = s->idx[0];
        int32_t last = INT32_MIN;
        for (int k = 0; ok && k < n; k++) {
            int i = idx[k];
            memcpy(verts + 6 * k, s->sxy + 6 * i, sizeof(float) * 6);
            memcpy(texs + 6 * k, s->uv + 6 * i, sizeof(float) * 6);
            memcpy(colors + 3 * k, s->cols + 3 * i, sizeof(int32_t) * 3);
            if (s->tex[i] != last) {
                if (s->batches == s->bcap) {
                    int cap = s->bcap ? s->bcap * 2 : 16;
                    int32_t *batch = realloc(s->batch, sizeof(int32_t) * 3 * cap);
                    if (!batch) {
                        ok = 0;
                        break;
                    }
                    s->batch = batch;
                    s->bcap = cap;
                }
                last = s->tex[i];
                s->batch[3 * s->batches] = k;
                s->batch[3 * s->batches + 1] = 0;
                s->batch[3 * s->batches + 2] = last;
                s->batches++;
            }
            s->batch[3 * s->batches - 2]++;
        }
        if (colors) (*env)->ReleasePrimitiveArrayCritical(env, jcolors, colors, 0);
        if (texs) (*env)->ReleasePrimitiveArrayCritical(env, jtexs, texs, 0);
        if (verts) (*env)->ReleasePrimitiveArrayCritical(env, jverts, verts, 0);
        if (!ok) {
            s->batches = 0;
            return -1;
        }
    }
    if ((*env)->GetArrayLength(env, jbatches) >= 3 * s->batches)
        (*env)->SetIntArrayRegion(env, jbatches, 0, 3 * s->batches, s->batch);
    return s->batches;
}

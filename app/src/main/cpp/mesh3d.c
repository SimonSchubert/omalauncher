// The work of building a 3D mesh (owl's Mesh, Bsp and parseObj): its smooth corner normals, the tree that orders its
// faces, and reading an OBJ file. A port of the Kotlin in owl/.../widgets/lang/Space3d.kt and Bsp.kt, which stays the
// reference and is what runs off the phone; the two give the same numbers (no fused multiply-adds, see
// CMakeLists.txt), which MeshNativeTest holds them to. Each answers null or false to leave the work to the Kotlin.
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#define FN(name) Java_dev_omalauncher_widgets_render_MeshNative_##name

// A growing array of ints or floats.
typedef struct {
    int n, cap;
    void *a;
} Vec;

static int reserve(Vec *v, int more, size_t size) {
    if (v->n + more <= v->cap) return 1;
    int cap = v->cap ? v->cap : 64;
    while (cap < v->n + more) cap *= 2;
    void *a = realloc(v->a, size * cap);
    if (!a) return 0;
    v->a = a;
    v->cap = cap;
    return 1;
}

static int pushInt(Vec *v, int32_t x) {
    if (!reserve(v, 1, sizeof(int32_t))) return 0;
    ((int32_t *)v->a)[v->n++] = x;
    return 1;
}

static int pushFloat(Vec *v, float x) {
    if (!reserve(v, 1, sizeof(float))) return 0;
    ((float *)v->a)[v->n++] = x;
    return 1;
}

#define INTS(v) ((int32_t *)(v).a)
#define FLOATS(v) ((float *)(v).a)

static void *copyFloats(JNIEnv *env, jfloatArray a, jsize *n) {
    *n = (*env)->GetArrayLength(env, a);
    float *p = malloc(sizeof(float) * (*n ? *n : 1));
    if (p) (*env)->GetFloatArrayRegion(env, a, 0, *n, p);
    return p;
}

static void *copyInts(JNIEnv *env, jintArray a, jsize *n) {
    *n = (*env)->GetArrayLength(env, a);
    int32_t *p = malloc(sizeof(int32_t) * (*n ? *n : 1));
    if (p) (*env)->GetIntArrayRegion(env, a, 0, *n, p);
    return p;
}

static jfloatArray newFloats(JNIEnv *env, const float *a, int n) {
    jfloatArray r = (*env)->NewFloatArray(env, n);
    if (r && n) (*env)->SetFloatArrayRegion(env, r, 0, n, a);
    return r;
}

static jintArray newInts(JNIEnv *env, const int32_t *a, int n) {
    jintArray r = (*env)->NewIntArray(env, n);
    if (r && n) (*env)->SetIntArrayRegion(env, r, 0, n, a);
    return r;
}

// ---- Mesh.cornerNormals ----

static uint32_t bits(float f) {
    uint32_t u;
    memcpy(&u, &f, 4);
    return u;
}

// Each point's position, numbered: points at the same x, y, z (as bits, as Kotlin's Position compares them) share
// one. Into pos; the number of positions, or -1 out of memory.
static int positions(const float *pts, int count, int32_t *pos) {
    int size = 16;
    while (size < 2 * count) size *= 2;
    int32_t *slot = malloc(sizeof(int32_t) * size);
    if (!slot) return -1;
    memset(slot, 0xff, sizeof(int32_t) * size);
    int n = 0;
    for (int p = 0; p < count; p++) {
        uint32_t x = bits(pts[3 * p]), y = bits(pts[3 * p + 1]), z = bits(pts[3 * p + 2]);
        uint32_t h = x * 0x9E3779B1u ^ (y * 0x85EBCA77u) ^ (z * 0xC2B2AE3Du);
        h ^= h >> 15;
        int i = h & (size - 1);
        for (;;) {
            int32_t q = slot[i];
            if (q < 0) {
                slot[i] = p;
                pos[p] = n++;
                break;
            }
            if (bits(pts[3 * q]) == x && bits(pts[3 * q + 1]) == y && bits(pts[3 * q + 2]) == z) {
                pos[p] = pos[q];
                break;
            }
            i = (i + 1) & (size - 1);
        }
    }
    free(slot);
    return n;
}

static int cornerNormals(const float *pts, int count, const int32_t *tris, int triangles, const float *fn, const float *areas,
                         float *out) {
    int ok = 0;
    int corners = 3 * triangles;
    int32_t *pos = malloc(sizeof(int32_t) * (count ? count : 1));
    int32_t *start = NULL, *list = NULL;
    if (!pos) goto done;
    int n = positions(pts, count, pos);
    if (n < 0) goto done;
    // The triangles at each position, in the order Kotlin's lists get them: by triangle, then corner.
    start = calloc(n + 1, sizeof(int32_t));
    list = malloc(sizeof(int32_t) * (corners ? corners : 1));
    if (!start || !list) goto done;
    for (int c = 0; c < corners; c++) start[pos[tris[c]] + 1]++;
    for (int g = 0; g < n; g++) start[g + 1] += start[g];
    for (int c = 0; c < corners; c++) list[start[pos[tris[c]]]++] = c / 3;
    for (int g = n; g > 0; g--) start[g] = start[g - 1];
    start[0] = 0;
    for (int t = 0; t < triangles; t++) {
        const float *f = fn + 3 * t;
        for (int k = 0; k < 3; k++) {
            int g = pos[tris[3 * t + k]];
            float nx = 0.0f, ny = 0.0f, nz = 0.0f;
            for (int i = start[g]; i < start[g + 1]; i++) {
                int o = list[i];
                const float *e = fn + 3 * o;
                float d = e[0] * f[0] + e[1] * f[1] + e[2] * f[2];
                if (d < 0.5f) continue;
                float w = areas[o];
                nx += e[0] * w;
                ny += e[1] * w;
                nz += e[2] * w;
            }
            float l = sqrtf(nx * nx + ny * ny + nz * nz);
            float *j = out + 9 * t + 3 * k;
            if (l > 0.0f) {
                j[0] = nx / l;
                j[1] = ny / l;
                j[2] = nz / l;
            } else {
                j[0] = f[0];
                j[1] = f[1];
                j[2] = f[2];
            }
        }
    }
    ok = 1;
done:
    free(pos);
    free(start);
    free(list);
    return ok;
}

JNIEXPORT jboolean JNICALL FN(cornerNormals)(JNIEnv *env, jclass cls, jfloatArray jpoints, jintArray jtris, jfloatArray jfn,
                                             jfloatArray jareas, jfloatArray jout) {
    jsize np, nt, nf, na;
    float *pts = copyFloats(env, jpoints, &np);
    int32_t *tris = copyInts(env, jtris, &nt);
    float *fn = copyFloats(env, jfn, &nf);
    float *areas = copyFloats(env, jareas, &na);
    int triangles = nt / 3, count = np / 3;
    int ok = pts && tris && fn && areas && nf >= 3 * triangles && na >= triangles && (*env)->GetArrayLength(env, jout) >= 9 * triangles;
    for (int i = 0; ok && i < 3 * triangles; i++)
        if (tris[i] < 0 || tris[i] >= count) ok = 0;
    float *out = ok ? malloc(sizeof(float) * (9 * triangles ? 9 * triangles : 1)) : NULL;
    ok = out && cornerNormals(pts, count, tris, triangles, fn, areas, out);
    if (ok) (*env)->SetFloatArrayRegion(env, jout, 0, 9 * triangles, out);
    free(out);
    free(pts);
    free(tris);
    free(fn);
    free(areas);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ---- Bsp.build ----

// Faces while the tree is built: nine floats of corners, six of uvs when the mesh has them, and the mesh face each
// came from.
typedef struct {
    Vec p, uv, src;
    int hasUv;
} Pool;

static int addFace(Pool *pool, const float *p, const float *uv) {
    if (!reserve(&pool->p, 9, sizeof(float)) || (pool->hasUv && !reserve(&pool->uv, 6, sizeof(float))) ||
        !reserve(&pool->src, 1, sizeof(int32_t)))
        return -1;
    memcpy(FLOATS(pool->p) + pool->p.n, p, 9 * sizeof(float));
    pool->p.n += 9;
    if (pool->hasUv) {
        memcpy(FLOATS(pool->uv) + pool->uv.n, uv, 6 * sizeof(float));
        pool->uv.n += 6;
    }
    return pool->src.n++;
}

static int isConvex(const float *pts, int count, const int32_t *tris, int triangles, const float *fn, float eps) {
    for (int t = 0; t < triangles; t++) {
        float nx = fn[3 * t], ny = fn[3 * t + 1], nz = fn[3 * t + 2];
        if (nx == 0.0f && ny == 0.0f && nz == 0.0f) continue;
        int a = 3 * tris[3 * t];
        float d = nx * pts[a] + ny * pts[a + 1] + nz * pts[a + 2];
        for (int i = 0; i < count; i++)
            if (nx * pts[3 * i] + ny * pts[3 * i + 1] + nz * pts[3 * i + 2] - d > eps) return 0;
    }
    return 1;
}

static void plane(const float *p, float *out) {
    float ux = p[3] - p[0], uy = p[4] - p[1], uz = p[5] - p[2];
    float vx = p[6] - p[0], vy = p[7] - p[1], vz = p[8] - p[2];
    float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
    float l = sqrtf(nx * nx + ny * ny + nz * nz);
    nx /= l;
    ny /= l;
    nz /= l;
    out[0] = nx;
    out[1] = ny;
    out[2] = nz;
    out[3] = nx * p[0] + ny * p[1] + nz * p[2];
}

static int pickSplitter(const Pool *pool, const int32_t *list, int len, float eps) {
    const float *P = FLOATS(pool->p);
    int best = list[0];
    int bestScore = INT32_MAX;
    int tries = len < 12 ? len : 12;
    int step = len / 200 > 1 ? len / 200 : 1;
    float pl[4];
    for (int c = 0; c < tries; c++) {
        int f = list[(int)((int64_t)c * len / tries)];
        plane(P + 9 * f, pl);
        int cuts = 0, fr = 0, bk = 0;
        for (int i = 0; i < len; i += step) {
            const float *g = P + 9 * list[i];
            int pos = 0, neg = 0;
            for (int k = 0; k < 3; k++) {
                float dist = pl[0] * g[3 * k] + pl[1] * g[3 * k + 1] + pl[2] * g[3 * k + 2] - pl[3];
                if (dist > eps) pos = 1;
                if (dist < -eps) neg = 1;
            }
            if (pos && neg) cuts++;
            else if (pos) fr++;
            else if (neg) bk++;
        }
        int score = 8 * cuts + abs(fr - bk);
        if (score < bestScore) {
            bestScore = score;
            best = f;
        }
    }
    return best;
}

// Bsp.fan: the polygon of n corners (x, y, z, u, v each) as triangles of face f, slivers left out, into list.
static int fan(Pool *pool, float poly[][5], int n, int f, Vec *list) {
    float p[9], uv[6];
    for (int k = 1; k < n - 1; k++) {
        float *c[3] = {poly[0], poly[k], poly[k + 1]};
        for (int q = 0; q < 3; q++) {
            p[3 * q] = c[q][0];
            p[3 * q + 1] = c[q][1];
            p[3 * q + 2] = c[q][2];
            uv[2 * q] = c[q][3];
            uv[2 * q + 1] = c[q][4];
        }
        float ux = p[3] - p[0], uy = p[4] - p[1], uz = p[5] - p[2];
        float vx = p[6] - p[0], vy = p[7] - p[1], vz = p[8] - p[2];
        float cx = uy * vz - uz * vy, cy = uz * vx - ux * vz, cz = ux * vy - uy * vx;
        if (cx * cx + cy * cy + cz * cz > 1e-12f) {
            int g = addFace(pool, p, uv);
            if (g < 0) return 0;
            INTS(pool->src)[g] = INTS(pool->src)[f];
            if (!pushInt(list, g)) return 0;
        }
    }
    return 1;
}

// Bsp.split: face f, its corners dist from the plane, cut into the part in front (to fs) and behind (to bs).
static int split(Pool *pool, int f, const float *dist, float eps, Vec *fs, Vec *bs) {
    // Each corner can go to both sides, and a cut after it too.
    float fp[6][5], bp[6][5], a[5], b[5];
    int nf = 0, nb = 0;
    for (int k = 0; k < 3; k++) {
        int j = (k + 1) % 3;
        // Read again for each: the pool may have moved.
        const float *p = FLOATS(pool->p) + 9 * f;
        const float *uv = pool->hasUv ? FLOATS(pool->uv) + 6 * f : NULL;
        a[0] = p[3 * k];
        a[1] = p[3 * k + 1];
        a[2] = p[3 * k + 2];
        a[3] = uv ? uv[2 * k] : 0.0f;
        a[4] = uv ? uv[2 * k + 1] : 0.0f;
        float da = dist[k], db = dist[j];
        if (da >= -eps) memcpy(fp[nf++], a, sizeof a);
        if (da <= eps) memcpy(bp[nb++], a, sizeof a);
        if ((da > eps && db < -eps) || (da < -eps && db > eps)) {
            float s = da / (da - db);
            b[0] = p[3 * j];
            b[1] = p[3 * j + 1];
            b[2] = p[3 * j + 2];
            b[3] = uv ? uv[2 * j] : 0.0f;
            b[4] = uv ? uv[2 * j + 1] : 0.0f;
            float m[5];
            for (int i = 0; i < 5; i++) m[i] = a[i] + (b[i] - a[i]) * s;
            memcpy(fp[nf++], m, sizeof m);
            memcpy(bp[nb++], m, sizeof m);
        }
    }
    return fan(pool, fp, nf, f, fs) && fan(pool, bp, nb, f, bs);
}

typedef struct {
    Vec list;
    int parent, isFront;
} Work;

// 1 with the tree in planes, nodes and out (the faces, in the order the nodes take them), 0 when there's none, -1 out
// of memory.
static int build(Pool *pool, const float *pts, int count, const int32_t *tris, int triangles, const float *uvs, const float *fn,
                 float radius, int maxTriangles, Vec *planes, Vec *nodes, Vec *out) {
    float eps = 1e-4f * (radius > 1.0f ? radius : 1.0f);
    if (isConvex(pts, count, tris, triangles, fn, eps)) return 0;
    Vec faces = {0};
    float p[9];
    int result = -1;
    Work *work = NULL;
    int top = 0, wcap = 0;
    for (int t = 0; t < triangles; t++) {
        if (fn[3 * t] == 0.0f && fn[3 * t + 1] == 0.0f && fn[3 * t + 2] == 0.0f) continue;
        for (int k = 0; k < 3; k++) memcpy(p + 3 * k, pts + 3 * tris[3 * t + k], 3 * sizeof(float));
        int g = addFace(pool, p, uvs ? uvs + 6 * t : NULL);
        if (g < 0 || !pushInt(&faces, g)) goto fail;
        INTS(pool->src)[g] = t;
    }
    if (faces.n < 2) {
        free(faces.a);
        return 0;
    }
    int many = 4 * faces.n > faces.n + 2000 ? 4 * faces.n : faces.n + 2000;
    int limit = maxTriangles < many ? maxTriangles : many;
    int total = faces.n;
    wcap = 64;
    work = malloc(sizeof(Work) * wcap);
    if (!work) goto fail;
    work[top++] = (Work){faces, -1, 0};
    faces = (Vec){0};
    Vec on = {0};
    while (top > 0) {
        Work w = work[--top];
        int32_t *list = INTS(w.list);
        int len = w.list.n;
        int node = nodes->n / 4;
        if (w.parent >= 0) INTS(*nodes)[4 * w.parent + (w.isFront ? 0 : 1)] = node;
        int splitter = pickSplitter(pool, list, len, eps);
        float s[4];
        plane(FLOATS(pool->p) + 9 * splitter, s);
        float nx = s[0], ny = s[1], nz = s[2], d = s[3];
        if (!reserve(planes, 4, sizeof(float)) || !reserve(nodes, 4, sizeof(int32_t))) {
            free(w.list.a);
            goto fail;
        }
        memcpy(FLOATS(*planes) + planes->n, s, sizeof s);
        planes->n += 4;
        int32_t *nd = INTS(*nodes) + nodes->n;
        nd[0] = -1;
        nd[1] = -1;
        nodes->n += 4;
        on.n = 0;
        Vec fs = {0}, bs = {0};
        int ok = 1;
        for (int i = 0; ok && i < len; i++) {
            int f = list[i];
            const float *fp = FLOATS(pool->p) + 9 * f;
            float dist[3] = {
                nx * fp[0] + ny * fp[1] + nz * fp[2] - d,
                nx * fp[3] + ny * fp[4] + nz * fp[5] - d,
                nx * fp[6] + ny * fp[7] + nz * fp[8] - d,
            };
            float d0 = dist[0], d1 = dist[1], d2 = dist[2];
            if (f == splitter || (fabsf(d0) <= eps && fabsf(d1) <= eps && fabsf(d2) <= eps)) ok = pushInt(&on, f);
            else if (d0 >= -eps && d1 >= -eps && d2 >= -eps) ok = pushInt(&fs, f);
            else if (d0 <= eps && d1 <= eps && d2 <= eps) ok = pushInt(&bs, f);
            else {
                int before = fs.n + bs.n;
                ok = split(pool, f, dist, eps, &fs, &bs);
                total += fs.n + bs.n - before - 1;
                if (ok && total > limit) {
                    free(w.list.a);
                    free(fs.a);
                    free(bs.a);
                    free(on.a);
                    result = 0;
                    goto fail;
                }
            }
        }
        free(w.list.a);
        nd = INTS(*nodes) + 4 * node;
        nd[2] = out->n;
        nd[3] = on.n;
        if (ok && !reserve(out, on.n, sizeof(int32_t))) ok = 0;
        if (ok) {
            memcpy(INTS(*out) + out->n, on.a, sizeof(int32_t) * on.n);
            out->n += on.n;
        }
        if (ok && top + 2 > wcap) {
            Work *more = realloc(work, sizeof(Work) * wcap * 2);
            if (more) {
                work = more;
                wcap *= 2;
            } else {
                ok = 0;
            }
        }
        if (!ok) {
            free(fs.a);
            free(bs.a);
            free(on.a);
            goto fail;
        }
        if (bs.n > 0) work[top++] = (Work){bs, node, 0};
        else free(bs.a);
        if (fs.n > 0) work[top++] = (Work){fs, node, 1};
        else free(fs.a);
    }
    free(on.a);
    free(work);
    return 1;
fail:
    free(faces.a);
    for (int i = 0; i < top; i++) free(work[i].list.a);
    free(work);
    return result;
}

// Bsp.build: {planes, nodes, points, uvs or null, src}, an empty array for no tree, null to leave it to the Kotlin.
JNIEXPORT jobjectArray JNICALL FN(bsp)(JNIEnv *env, jclass cls, jfloatArray jpoints, jintArray jtris, jfloatArray juvs,
                                       jfloatArray jfn, jfloat radius, jint maxTriangles) {
    jsize np, nt, nu = 0, nf;
    float *pts = copyFloats(env, jpoints, &np);
    int32_t *tris = copyInts(env, jtris, &nt);
    float *uvs = juvs ? copyFloats(env, juvs, &nu) : NULL;
    float *fn = copyFloats(env, jfn, &nf);
    int triangles = nt / 3, count = np / 3;
    jobjectArray result = NULL;
    Pool pool = {.hasUv = juvs != NULL};
    Vec planes = {0}, nodes = {0}, out = {0};
    int ok = pts && tris && fn && (!juvs || uvs) && nf >= 3 * triangles && (!juvs || nu >= 6 * triangles);
    for (int i = 0; ok && i < 3 * triangles; i++)
        if (tris[i] < 0 || tris[i] >= count) ok = 0;
    if (!ok) goto done;
    jclass object = (*env)->FindClass(env, "java/lang/Object");
    if (!object) goto done;
    int built = build(&pool, pts, count, tris, triangles, uvs, fn, radius, maxTriangles, &planes, &nodes, &out);
    if (built < 0) goto done;
    if (built == 0) {
        result = (*env)->NewObjectArray(env, 0, object, NULL);
        goto done;
    }
    int m = out.n;
    float *cut = malloc(sizeof(float) * 9 * (m ? m : 1));
    float *cutUv = pool.hasUv ? malloc(sizeof(float) * 6 * (m ? m : 1)) : NULL;
    int32_t *src = malloc(sizeof(int32_t) * (m ? m : 1));
    if (cut && src && (!pool.hasUv || cutUv)) {
        for (int i = 0; i < m; i++) {
            int f = INTS(out)[i];
            memcpy(cut + 9 * i, FLOATS(pool.p) + 9 * f, 9 * sizeof(float));
            if (cutUv) memcpy(cutUv + 6 * i, FLOATS(pool.uv) + 6 * f, 6 * sizeof(float));
            src[i] = INTS(pool.src)[f];
        }
        jobjectArray r = (*env)->NewObjectArray(env, 5, object, NULL);
        jobject parts[5] = {
            r ? newFloats(env, FLOATS(planes), planes.n) : NULL,
            r ? newInts(env, INTS(nodes), nodes.n) : NULL,
            r ? newFloats(env, cut, 9 * m) : NULL,
            r && cutUv ? newFloats(env, cutUv, 6 * m) : NULL,
            r ? newInts(env, src, m) : NULL,
        };
        if (r && parts[0] && parts[1] && parts[2] && (!cutUv || parts[3]) && parts[4]) {
            for (int i = 0; i < 5; i++) (*env)->SetObjectArrayElement(env, r, i, parts[i]);
            result = r;
        }
    }
    free(cut);
    free(cutUv);
    free(src);
done:
    free(pts);
    free(tris);
    free(uvs);
    free(fn);
    free(pool.p.a);
    free(pool.uv.a);
    free(pool.src.a);
    free(planes.a);
    free(nodes.a);
    free(out.a);
    return result;
}

// ---- Meshes.parseObj ----

// What Kotlin's Char.isWhitespace trims, of ASCII.
static int trimmed(jchar c) { return (c >= 0x09 && c <= 0x0d) || (c >= 0x1c && c <= 0x20); }

// What the Regex \s splits on.
static int space(jchar c) { return c == ' ' || (c >= 0x09 && c <= 0x0d); }

// Kotlin's toIntOrNull of ASCII: 1 with the number, 0 for null.
static int toInt(const jchar *s, int n, int32_t *out) {
    if (n == 0) return 0;
    int i = 0, negative = 0;
    if (s[0] < '0') {
        if (n == 1) return 0;
        if (s[0] == '-') negative = 1;
        else if (s[0] != '+') return 0;
        i = 1;
    }
    int64_t v = 0;
    for (; i < n; i++) {
        if (s[i] < '0' || s[i] > '9') return 0;
        v = v * 10 + (s[i] - '0');
        if (v > (int64_t)INT32_MAX + 1) return 0;
    }
    if (negative) v = -v;
    if (v > INT32_MAX || v < INT32_MIN) return 0;
    *out = (int32_t)v;
    return 1;
}

// A number as Kotlin's toFloatOrNull reads it, for plain decimals only: 1 with it, 0 for anything else (Java reads
// 1f, 0x1p3, NaN and Infinity too, and rejects what this can't be sure of: the Kotlin then reads the whole file).
static int toFloat(const jchar *s, int n, float *out) {
    char buf[128];
    if (n == 0 || n >= (int)sizeof buf) return 0;
    int i = 0, digits = 0;
    if (s[i] == '+' || s[i] == '-') i++;
    while (i < n && s[i] >= '0' && s[i] <= '9') i++, digits++;
    if (i < n && s[i] == '.') {
        i++;
        while (i < n && s[i] >= '0' && s[i] <= '9') i++, digits++;
    }
    if (!digits) return 0;
    if (i < n && (s[i] == 'e' || s[i] == 'E')) {
        i++;
        if (i < n && (s[i] == '+' || s[i] == '-')) i++;
        int e = 0;
        while (i < n && s[i] >= '0' && s[i] <= '9') i++, e++;
        if (!e) return 0;
    }
    if (i != n) return 0;
    for (int k = 0; k < n; k++) buf[k] = (char)s[k];
    buf[n] = 0;
    // Correctly rounded, as Java's parseFloat: Android's and glibc's strtof are.
    *out = strtof(buf, NULL);
    return 1;
}

static int same(const jchar *s, int n, const char *word) {
    int k = 0;
    for (; k < n && word[k]; k++)
        if (s[k] != (jchar)word[k]) return 0;
    return k == n && !word[k];
}

typedef struct {
    Vec points, vt, faceStart, corners, cornerTex, faceTex, faceMaterial, materials; // materials: start, length in the text
    int smooth;
} Obj;

#define MAX_TOKENS 64

// 1 read, 0 out of memory, -1 for a file the Kotlin should read.
static int scanObj(const jchar *c, int len, Obj *o) {
    int pointCount = 0, vtCount = 0, material = -1;
    int start[MAX_TOKENS], size[MAX_TOKENS];
    int i = 0;
    while (i < len) {
        int e = i;
        while (e < len && c[e] != '\n' && c[e] != '\r') e++;
        int a = i, b = e;
        i = e + (e + 1 < len && c[e] == '\r' && c[e + 1] == '\n' ? 2 : 1);
        while (a < b && trimmed(c[a])) a++;
        while (b > a && trimmed(c[b - 1])) b--;
        if (a == b || c[a] == '#') continue;
        int tokens = 0;
        for (int k = a; k < b; k++)
            if (c[k] >= 0x80 || (c[k] >= 0x1c && c[k] <= 0x1f)) return -1;
        for (int k = a; k < b;) {
            while (k < b && space(c[k])) k++;
            if (k == b) break;
            int s = k;
            while (k < b && !space(c[k])) k++;
            if (tokens == MAX_TOKENS) return -1;
            start[tokens] = s;
            size[tokens++] = k - s;
        }
        const jchar *t0 = c + start[0];
        if (same(t0, size[0], "v")) {
            float v[3] = {0.0f, 0.0f, 0.0f};
            for (int k = 1; k <= 3 && k < tokens; k++)
                if (!toFloat(c + start[k], size[k], &v[k - 1])) return -1;
            for (int k = 0; k < 3; k++)
                if (!pushFloat(&o->points, v[k])) return 0;
            pointCount++;
        } else if (same(t0, size[0], "vt")) {
            float v[2] = {0.0f, 0.0f};
            for (int k = 1; k <= 2 && k < tokens; k++)
                if (!toFloat(c + start[k], size[k], &v[k - 1])) return -1;
            if (!pushFloat(&o->vt, v[0]) || !pushFloat(&o->vt, v[1])) return 0;
            vtCount++;
        } else if (same(t0, size[0], "usemtl")) {
            material = o->materials.n / 2;
            if (!pushInt(&o->materials, tokens > 1 ? start[1] : 0) || !pushInt(&o->materials, tokens > 1 ? size[1] : 0)) return 0;
        } else if (same(t0, size[0], "s")) {
            if (tokens > 1 && !same(c + start[1], size[1], "off") && !same(c + start[1], size[1], "0")) o->smooth = 1;
        } else if (same(t0, size[0], "f")) {
            if (tokens < 4) continue;
            if (!o->faceStart.n && !pushInt(&o->faceStart, 0)) return 0;
            int tex = 1;
            for (int k = 1; k < tokens; k++) {
                const jchar *s = c + start[k];
                int n = size[k], slash = 0;
                while (slash < n && s[slash] != '/') slash++;
                int32_t p, t = 0;
                // Not a point number: the Kotlin says so.
                if (!toInt(s, slash, &p)) return -1;
                p = (int32_t)(p < 0 ? (uint32_t)pointCount + (uint32_t)p : (uint32_t)p - 1u);
                if (slash < n) {
                    int u = slash + 1, w = u;
                    while (w < n && s[w] != '/') w++;
                    if (!toInt(s + u, w - u, &t)) tex = 0;
                } else {
                    tex = 0;
                }
                if (tex) t = (int32_t)(t < 0 ? (uint32_t)vtCount + (uint32_t)t : (uint32_t)t - 1u);
                if (!pushInt(&o->corners, p) || !pushInt(&o->cornerTex, tex ? t : 0)) return 0;
            }
            if (!pushInt(&o->faceStart, o->corners.n) || !pushInt(&o->faceTex, tex) || !pushInt(&o->faceMaterial, material)) return 0;
        }
    }
    if (!o->faceStart.n && !pushInt(&o->faceStart, 0)) return 0;
    return 1;
}

// Meshes.parseObj's reading: {points, vt, faceStart, corners, cornerTex, faceTex, faceMaterial, materials, {smooth}}, or
// null to leave it to the Kotlin.
JNIEXPORT jobjectArray JNICALL FN(scanObj)(JNIEnv *env, jclass cls, jstring jtext) {
    jsize len = (*env)->GetStringLength(env, jtext);
    jchar *text = malloc(sizeof(jchar) * (len ? len : 1));
    if (!text) return NULL;
    (*env)->GetStringRegion(env, jtext, 0, len, text);
    Obj o = {0};
    jobjectArray result = NULL;
    if (scanObj(text, len, &o) != 1) goto done;
    jclass object = (*env)->FindClass(env, "java/lang/Object");
    jclass string = (*env)->FindClass(env, "java/lang/String");
    if (!object || !string) goto done;
    int faces = o.faceTex.n;
    jbooleanArray faceTex = (*env)->NewBooleanArray(env, faces);
    jbooleanArray smooth = (*env)->NewBooleanArray(env, 1);
    int names = o.materials.n / 2;
    jobjectArray materials = (*env)->NewObjectArray(env, names, string, NULL);
    if (!faceTex || !smooth || !materials) goto done;
    // In one call: the ints' own memory, narrowed from its start, takes the booleans.
    jboolean *flags = (jboolean *)o.faceTex.a;
    for (int f = 0; f < faces; f++) flags[f] = INTS(o.faceTex)[f] ? JNI_TRUE : JNI_FALSE;
    if (faces) (*env)->SetBooleanArrayRegion(env, faceTex, 0, faces, flags);
    jboolean s = o.smooth ? JNI_TRUE : JNI_FALSE;
    (*env)->SetBooleanArrayRegion(env, smooth, 0, 1, &s);
    for (int m = 0; m < names; m++) {
        jstring name = (*env)->NewString(env, text + INTS(o.materials)[2 * m], INTS(o.materials)[2 * m + 1]);
        if (!name) goto done;
        (*env)->SetObjectArrayElement(env, materials, m, name);
        (*env)->DeleteLocalRef(env, name);
    }
    jobject parts[9] = {
        newFloats(env, FLOATS(o.points), o.points.n),
        newFloats(env, FLOATS(o.vt), o.vt.n),
        newInts(env, INTS(o.faceStart), o.faceStart.n),
        newInts(env, INTS(o.corners), o.corners.n),
        newInts(env, INTS(o.cornerTex), o.cornerTex.n),
        faceTex,
        newInts(env, INTS(o.faceMaterial), o.faceMaterial.n),
        materials,
        smooth,
    };
    for (int k = 0; k < 9; k++)
        if (!parts[k]) goto done;
    jobjectArray r = (*env)->NewObjectArray(env, 9, object, NULL);
    if (!r) goto done;
    for (int k = 0; k < 9; k++) (*env)->SetObjectArrayElement(env, r, k, parts[k]);
    result = r;
done:
    free(text);
    free(o.points.a);
    free(o.vt.a);
    free(o.faceStart.a);
    free(o.corners.a);
    free(o.cornerTex.a);
    free(o.faceTex.a);
    free(o.faceMaterial.a);
    free(o.materials.a);
    return result;
}

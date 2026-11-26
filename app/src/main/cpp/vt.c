// The built-in terminal's screen (terminal/Vt.kt): libghostty-vt (scripts/fetch-ghostty-vt.sh) fed what the
// program writes, and asked how to encode the keys sent to it. No pty here: what the terminal answers the program
// (cursor position reports, device attributes) is kept for the session to send, so the tests run without one.
// A lock per terminal: the session's reading thread writes while the UI thread reads the screen and sends keys.
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <ghostty/vt.h>

#define FN(name) Java_dev_omalauncher_terminal_VtNative_##name

typedef struct {
    pthread_mutex_t lock;
    GhosttyTerminal term;
    GhosttyRenderState render;
    GhosttyRenderStateRowIterator rows;
    GhosttyRenderStateRowCells cells;
    GhosttyKeyEncoder keys;
    GhosttyKeyEvent key;
    GhosttyFormatter text;
    // The selected text: unwrapped, as Ghostty copies it.
    GhosttyFormatter selection;
    GhosttyMouseEncoder mouse;
    GhosttyMouseEvent mouse_event;
    // Replies to the program, until taken (replies()).
    uint8_t *reply;
    size_t reply_len, reply_cap;
    // Synchronized output (mode 2026): the program asked for the screen not to be drawn yet.
    int held;
    // What happened since events() last asked: bells, a new title, a clipboard write (OSC 52), a notification
    // (OSC 9/777). Strings are malloc'd, NULL when there is none.
    int bells;
    int title_changed;
    char *clipboard;
    char *notify_title, *notify_body;
} Vt;

static Vt *vt(jlong h) { return (Vt *)(intptr_t)h; }

static void on_reply(GhosttyTerminal t, void *ud, const uint8_t *data, size_t len) {
    (void)t;
    Vt *v = ud;
    if (v->reply_len + len > v->reply_cap) {
        size_t cap = (v->reply_len + len) * 2;
        uint8_t *grown = realloc(v->reply, cap);
        if (!grown) return;
        v->reply = grown;
        v->reply_cap = cap;
    }
    memcpy(v->reply + v->reply_len, data, len);
    v->reply_len += len;
}

static void on_hold(GhosttyTerminal t, void *ud, bool held) {
    (void)t;
    ((Vt *)ud)->held = held;
}

static void on_bell(GhosttyTerminal t, void *ud) {
    (void)t;
    ((Vt *)ud)->bells++;
}

static void on_title(GhosttyTerminal t, void *ud) {
    (void)t;
    ((Vt *)ud)->title_changed = 1;
}

static char *copy(GhosttyString s) {
    char *out = malloc(s.len + 1);
    if (!out) return NULL;
    if (s.len) memcpy(out, s.ptr, s.len);
    out[s.len] = 0;
    return out;
}

static int is_text(GhosttyString mime) {
    return mime.len >= 5 && memcmp(mime.ptr, "text/", 5) == 0;
}

// A program copies (OSC 52): taken, as foot does, into the clipboard events() hands on. Reads aren't offered.
static void on_clipboard(GhosttyTerminal t, void *ud, const GhosttyClipboardWrite *w) {
    (void)t;
    Vt *v = ud;
    GhosttyClipboardWriteReply reply = GHOSTTY_INIT_SIZED(GhosttyClipboardWriteReply);
    reply.result = GHOSTTY_CLIPBOARD_WRITE_RESULT_UNSUPPORTED;
    if (w->location == GHOSTTY_CLIPBOARD_LOCATION_STANDARD) {
        for (size_t i = 0; i < w->contents_len; i++) {
            if (!is_text(w->contents[i].mime)) continue;
            free(v->clipboard);
            v->clipboard = copy(w->contents[i].data);
            reply.result = GHOSTTY_CLIPBOARD_WRITE_RESULT_SUCCESS;
            break;
        }
    }
    w->reply(w, &reply);
}

static void on_notify(GhosttyTerminal t, void *ud, const GhosttyTerminalDesktopNotification *n) {
    (void)t;
    Vt *v = ud;
    free(v->notify_title);
    free(v->notify_body);
    v->notify_title = copy(n->title);
    v->notify_body = copy(n->body);
}

static void destroy(Vt *v) {
    if (v->mouse_event) ghostty_mouse_event_free(v->mouse_event);
    if (v->mouse) ghostty_mouse_encoder_free(v->mouse);
    if (v->selection) ghostty_formatter_free(v->selection);
    if (v->text) ghostty_formatter_free(v->text);
    if (v->key) ghostty_key_event_free(v->key);
    if (v->keys) ghostty_key_encoder_free(v->keys);
    if (v->cells) ghostty_render_state_row_cells_free(v->cells);
    if (v->rows) ghostty_render_state_row_iterator_free(v->rows);
    if (v->render) ghostty_render_state_free(v->render);
    if (v->term) ghostty_terminal_free(v->term);
    pthread_mutex_destroy(&v->lock);
    free(v->reply);
    free(v->clipboard);
    free(v->notify_title);
    free(v->notify_body);
    free(v);
}

// A terminal of cols x rows cells of cellWidth x cellHeight pixels, or 0.
JNIEXPORT jlong JNICALL FN(create)(JNIEnv *env, jclass cls, jint cols, jint rows, jint cellWidth, jint cellHeight) {
    (void)env;
    (void)cls;
    if (cols < 1 || rows < 1 || cols > 65535 || rows > 65535) return 0;
    Vt *v = calloc(1, sizeof *v);
    if (!v) return 0;
    pthread_mutex_init(&v->lock, NULL);
    bool plain_trim = true;
    GhosttyFormatterFormat plain = GHOSTTY_FORMATTER_FORMAT_PLAIN;
    if (ghostty_terminal_new(NULL, &v->term, (uint16_t)cols, (uint16_t)rows) != GHOSTTY_SUCCESS ||
        ghostty_render_state_new(NULL, &v->render) != GHOSTTY_SUCCESS ||
        ghostty_render_state_row_iterator_new(NULL, &v->rows) != GHOSTTY_SUCCESS ||
        ghostty_render_state_row_cells_new(NULL, &v->cells) != GHOSTTY_SUCCESS ||
        ghostty_key_encoder_new(NULL, &v->keys) != GHOSTTY_SUCCESS || ghostty_key_event_new(NULL, &v->key) != GHOSTTY_SUCCESS ||
        ghostty_formatter_terminal_new(NULL, &v->text, v->term) != GHOSTTY_SUCCESS ||
        ghostty_formatter_terminal_new(NULL, &v->selection, v->term) != GHOSTTY_SUCCESS ||
        ghostty_mouse_encoder_new(NULL, &v->mouse) != GHOSTTY_SUCCESS || ghostty_mouse_event_new(NULL, &v->mouse_event) != GHOSTTY_SUCCESS) {
        destroy(v);
        return 0;
    }
    ghostty_formatter_set(v->text, GHOSTTY_FORMATTER_OPT_EMIT, &plain);
    ghostty_formatter_set(v->text, GHOSTTY_FORMATTER_OPT_TRIM, &plain_trim);
    ghostty_formatter_set(v->selection, GHOSTTY_FORMATTER_OPT_EMIT, &plain);
    ghostty_formatter_set(v->selection, GHOSTTY_FORMATTER_OPT_TRIM, &plain_trim);
    ghostty_formatter_set(v->selection, GHOSTTY_FORMATTER_OPT_UNWRAP, &plain_trim);
    bool track = true;
    ghostty_mouse_encoder_set(v->mouse, GHOSTTY_MOUSE_ENCODER_OPT_TRACK_LAST_CELL, &track);
    ghostty_terminal_resize(v->term, (uint16_t)cols, (uint16_t)rows, (uint32_t)cellWidth, (uint32_t)cellHeight);
    ghostty_terminal_set(v->term, GHOSTTY_TERMINAL_OPT_USERDATA, v);
    ghostty_terminal_set(v->term, GHOSTTY_TERMINAL_OPT_WRITE_PTY, (const void *)on_reply);
    ghostty_terminal_set(v->term, GHOSTTY_TERMINAL_OPT_RENDER_HOLD, (const void *)on_hold);
    // Alt sends Esc before the key, as in foot and xterm's metaSendsEscape: Alt+b and Alt+f move by words in bash.
    GhosttyTerminalModeConfig alt_esc = {.mode = ghostty_mode_new(1036, false), .value = true};
    ghostty_terminal_set(v->term, GHOSTTY_TERMINAL_OPT_MODE_DEFAULT, &alt_esc);
    ghostty_terminal_set(v->term, GHOSTTY_TERMINAL_OPT_BELL, (const void *)on_bell);
    ghostty_terminal_set(v->term, GHOSTTY_TERMINAL_OPT_TITLE_CHANGED, (const void *)on_title);
    ghostty_terminal_set(v->term, GHOSTTY_TERMINAL_OPT_CLIPBOARD_WRITE, (const void *)on_clipboard);
    ghostty_terminal_set(v->term, GHOSTTY_TERMINAL_OPT_DESKTOP_NOTIFICATION, (const void *)on_notify);
    return (jlong)(intptr_t)v;
}

JNIEXPORT void JNICALL FN(free)(JNIEnv *env, jclass cls, jlong h) {
    (void)env;
    (void)cls;
    if (h) destroy(vt(h));
}

// Feeds len bytes the program wrote.
JNIEXPORT void JNICALL FN(write)(JNIEnv *env, jclass cls, jlong h, jbyteArray jbuf, jint len) {
    (void)cls;
    Vt *v = vt(h);
    jbyte *buf = (*env)->GetByteArrayElements(env, jbuf, NULL);
    if (!buf) return;
    pthread_mutex_lock(&v->lock);
    ghostty_terminal_vt_write(v->term, (const uint8_t *)buf, (size_t)len);
    pthread_mutex_unlock(&v->lock);
    (*env)->ReleaseByteArrayElements(env, jbuf, buf, JNI_ABORT);
}

// What the terminal has answered the program since the last call, or null.
JNIEXPORT jbyteArray JNICALL FN(replies)(JNIEnv *env, jclass cls, jlong h) {
    (void)cls;
    Vt *v = vt(h);
    jbyteArray out = NULL;
    pthread_mutex_lock(&v->lock);
    if (v->reply_len > 0) {
        out = (*env)->NewByteArray(env, (jsize)v->reply_len);
        if (out) (*env)->SetByteArrayRegion(env, out, 0, (jsize)v->reply_len, (const jbyte *)v->reply);
        v->reply_len = 0;
    }
    pthread_mutex_unlock(&v->lock);
    return out;
}

JNIEXPORT jboolean JNICALL FN(held)(JNIEnv *env, jclass cls, jlong h) {
    (void)env;
    (void)cls;
    return vt(h)->held ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL FN(resize)(JNIEnv *env, jclass cls, jlong h, jint cols, jint rows, jint cellWidth, jint cellHeight) {
    (void)env;
    (void)cls;
    if (cols < 1 || rows < 1 || cols > 65535 || rows > 65535) return;
    Vt *v = vt(h);
    pthread_mutex_lock(&v->lock);
    ghostty_terminal_resize(v->term, (uint16_t)cols, (uint16_t)rows, (uint32_t)cellWidth, (uint32_t)cellHeight);
    pthread_mutex_unlock(&v->lock);
}

// Moves the view through the scrollback: delta rows, up when negative.
JNIEXPORT void JNICALL FN(scroll)(JNIEnv *env, jclass cls, jlong h, jint delta) {
    (void)env;
    (void)cls;
    Vt *v = vt(h);
    GhosttyTerminalScrollViewport by = {.tag = GHOSTTY_TERMINAL_SCROLL_VIEWPORT_DELTA, .value = {.delta = delta}};
    pthread_mutex_lock(&v->lock);
    ghostty_terminal_scroll_viewport(v->term, by);
    pthread_mutex_unlock(&v->lock);
}

// The bytes a key press sends, in the encoding the program asked for (legacy, application cursor keys, the kitty
// keyboard protocol): key a GhosttyKey, mods GhosttyMods, text what the key types (or null), unshifted the
// character it types without modifiers (or 0). Null when it sends nothing.
JNIEXPORT jbyteArray JNICALL FN(key)(JNIEnv *env, jclass cls, jlong h, jint key, jint mods, jstring jtext, jint unshifted) {
    (void)cls;
    Vt *v = vt(h);
    const char *text = jtext ? (*env)->GetStringUTFChars(env, jtext, NULL) : NULL;
    uint8_t out[128];
    size_t len = 0;
    pthread_mutex_lock(&v->lock);
    ghostty_key_encoder_set_from_terminal(v->keys, v->term);
    // Alt is Alt, also in the host build the tests run on macOS, where the encoder would take it for Option.
    GhosttyOptionAsAlt option_as_alt = GHOSTTY_OPTION_AS_ALT_TRUE;
    ghostty_key_encoder_set(v->keys, GHOSTTY_KEY_ENCODER_OPT_MACOS_OPTION_AS_ALT, &option_as_alt);
    ghostty_key_event_set_action(v->key, GHOSTTY_KEY_ACTION_PRESS);
    ghostty_key_event_set_key(v->key, (GhosttyKey)key);
    ghostty_key_event_set_mods(v->key, (GhosttyMods)mods);
    ghostty_key_event_set_unshifted_codepoint(v->key, (uint32_t)unshifted);
    ghostty_key_event_set_utf8(v->key, (GhosttyString){.ptr = (const uint8_t *)text, .len = text ? strlen(text) : 0});
    GhosttyResult r = ghostty_key_encoder_encode(v->keys, v->key, out, sizeof out, &len);
    pthread_mutex_unlock(&v->lock);
    if (text) (*env)->ReleaseStringUTFChars(env, jtext, text);
    if (r != GHOSTTY_SUCCESS || len == 0) return NULL;
    jbyteArray arr = (*env)->NewByteArray(env, (jsize)len);
    if (arr) (*env)->SetByteArrayRegion(env, arr, 0, (jsize)len, (const jbyte *)out);
    return arr;
}

// The screen as plain text, trailing blanks trimmed, as UTF-8.
JNIEXPORT jbyteArray JNICALL FN(text)(JNIEnv *env, jclass cls, jlong h) {
    (void)cls;
    Vt *v = vt(h);
    uint8_t *buf = NULL;
    size_t len = 0;
    pthread_mutex_lock(&v->lock);
    GhosttyResult r = ghostty_formatter_format_alloc(v->text, NULL, &buf, &len);
    pthread_mutex_unlock(&v->lock);
    if (r != GHOSTTY_SUCCESS) return NULL;
    jbyteArray arr = (*env)->NewByteArray(env, (jsize)len);
    if (arr && len) (*env)->SetByteArrayRegion(env, arr, 0, (jsize)len, (const jbyte *)buf);
    ghostty_free(NULL, buf, len);
    return arr;
}

static jint argb(GhosttyColorRgb c) { return (jint)(0xff000000u | (uint32_t)c.r << 16 | (uint32_t)c.g << 8 | c.b); }

// The cells on screen, row by row, into arrays of at least cols * rows: the first code point of each (0 for an
// empty cell), foreground and background as ARGB (the defaults where the cell sets none) and Cells' flags (Vt.kt).
// Cells of more than one code point (an emoji sequence, combining marks) also go into more, as {cell index, count,
// code points...}. Returns INFO_* (Vt.kt): {cols, rows, cursor column, cursor row (-1, -1 when it's off the view),
// cursor visible (0/1), filled (0 when the arrays are too small for cols * rows: nothing was written), cursor style
// (GhosttyCursorStyle), ints used in more (minus what it needs when it was too small), default foreground, default background, cursor
// colour (0 for none), cursor on a wide character's second cell (0/1)}, or null.
JNIEXPORT jintArray JNICALL FN(cells)(JNIEnv *env, jclass cls, jlong h, jintArray jcp, jintArray jfg, jintArray jbg,
                                      jintArray jflags, jintArray jmore) {
    (void)cls;
    Vt *v = vt(h);
    pthread_mutex_lock(&v->lock);
    GhosttyResult updated = ghostty_render_state_update(v->render, v->term);
    pthread_mutex_unlock(&v->lock);
    if (updated != GHOSTTY_SUCCESS) return NULL;
    // The render state is a copy: read without the lock. Only one thread draws.
    uint16_t cols = 0, rows = 0;
    ghostty_render_state_get(v->render, GHOSTTY_RENDER_STATE_DATA_COLS, &cols);
    ghostty_render_state_get(v->render, GHOSTTY_RENDER_STATE_DATA_ROWS, &rows);
    jsize need = (jsize)cols * rows;
    bool fits = (*env)->GetArrayLength(env, jcp) >= need && (*env)->GetArrayLength(env, jfg) >= need &&
                (*env)->GetArrayLength(env, jbg) >= need && (*env)->GetArrayLength(env, jflags) >= need;
    GhosttyRenderStateColors colors = GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
    ghostty_render_state_get(v->render, GHOSTTY_RENDER_STATE_DATA_COLORS, &colors);
    GhosttyRenderStateCursor cursor = GHOSTTY_INIT_SIZED(GhosttyRenderStateCursor);
    ghostty_render_state_get(v->render, GHOSTTY_RENDER_STATE_DATA_CURSOR, &cursor);

    jint *cp = fits ? (*env)->GetIntArrayElements(env, jcp, NULL) : NULL;
    jint *fg = fits ? (*env)->GetIntArrayElements(env, jfg, NULL) : NULL;
    jint *bg = fits ? (*env)->GetIntArrayElements(env, jbg, NULL) : NULL;
    jint *flags = fits ? (*env)->GetIntArrayElements(env, jflags, NULL) : NULL;
    jsize more_cap = (*env)->GetArrayLength(env, jmore);
    jint *more = fits ? (*env)->GetIntArrayElements(env, jmore, NULL) : NULL;
    jint more_len = 0, more_need = 0;
    bool filled = cp && fg && bg && flags && more;
    if (filled) {
        memset(cp, 0, sizeof(jint) * (size_t)need);
        ghostty_render_state_get(v->render, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &v->rows);
        for (int y = 0; y < rows && ghostty_render_state_row_iterator_next(v->rows); y++) {
            ghostty_render_state_row_get(v->rows, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &v->cells);
            for (int x = 0; x < cols && ghostty_render_state_row_cells_next(v->cells); x++) {
                int i = y * cols + x;
                uint32_t glen = 0, cluster[16];
                ghostty_render_state_row_cells_get(v->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN, &glen);
                if (glen) {
                    GhosttyCodepointBuffer b = {.ptr = cluster, .cap = 16};
                    ghostty_render_state_row_cells_get(v->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF, &b);
                    cp[i] = (jint)cluster[0];
                    if (glen > 1) {
                        uint32_t n = glen < 16 ? glen : 16;
                        if (more_len + 2 + (jint)n <= more_cap) {
                            more[more_len++] = i;
                            more[more_len++] = (jint)n;
                            for (uint32_t k = 0; k < n; k++) more[more_len++] = (jint)cluster[k];
                        }
                        more_need += 2 + (jint)n;
                    }
                }
                GhosttyColorRgb c;
                int f = 0;
                fg[i] = ghostty_render_state_row_cells_get(v->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR, &c) == GHOSTTY_SUCCESS
                            ? argb(c) : argb(colors.foreground);
                if (ghostty_render_state_row_cells_get(v->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR, &c) == GHOSTTY_SUCCESS) {
                    bg[i] = argb(c);
                    f |= 1 << 7;
                } else {
                    bg[i] = argb(colors.background);
                }
                GhosttyStyle st = {0};
                ghostty_render_state_row_cells_get(v->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE, &st);
                if (st.flags & GHOSTTY_STYLE_FLAG_BOLD) f |= 1 << 0;
                if (st.flags & GHOSTTY_STYLE_FLAG_ITALIC) f |= 1 << 1;
                if (GHOSTTY_STYLE_UNDERLINE(st)) f |= 1 << 2;
                if (st.flags & GHOSTTY_STYLE_FLAG_INVERSE) f |= 1 << 3;
                if (st.flags & GHOSTTY_STYLE_FLAG_FAINT) f |= 1 << 6;
                if (st.flags & GHOSTTY_STYLE_FLAG_STRIKETHROUGH) f |= 1 << 8;
                if (st.flags & GHOSTTY_STYLE_FLAG_INVISIBLE) f |= 1 << 11;
                bool selected = false;
                ghostty_render_state_row_cells_get(v->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_SELECTED, &selected);
                if (selected) f |= 1 << 9;
                GhosttyCell raw;
                if (ghostty_render_state_row_cells_get(v->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW, &raw) == GHOSTTY_SUCCESS) {
                    GhosttyCellWide w = GHOSTTY_CELL_WIDE_NARROW;
                    ghostty_cell_get(raw, GHOSTTY_CELL_DATA_WIDE, &w);
                    if (w == GHOSTTY_CELL_WIDE_WIDE) f |= 1 << 4;
                    if (w == GHOSTTY_CELL_WIDE_SPACER_TAIL || w == GHOSTTY_CELL_WIDE_SPACER_HEAD) f |= 1 << 5;
                    bool link = false;
                    ghostty_cell_get(raw, GHOSTTY_CELL_DATA_HAS_HYPERLINK, &link);
                    if (link) f |= 1 << 10;
                }
                flags[i] = f;
            }
        }
    }
    if (more) (*env)->ReleaseIntArrayElements(env, jmore, more, 0);
    if (flags) (*env)->ReleaseIntArrayElements(env, jflags, flags, 0);
    if (bg) (*env)->ReleaseIntArrayElements(env, jbg, bg, 0);
    if (fg) (*env)->ReleaseIntArrayElements(env, jfg, fg, 0);
    if (cp) (*env)->ReleaseIntArrayElements(env, jcp, cp, 0);
    jint info[12] = {cols,
                     rows,
                     cursor.viewport_has_value ? cursor.viewport_x : -1,
                     cursor.viewport_has_value ? cursor.viewport_y : -1,
                     cursor.visible ? 1 : 0,
                     filled ? 1 : 0,
                     (jint)cursor.visual_style,
                     more_need > more_len ? -more_need : more_len,
                     argb(colors.foreground),
                     argb(colors.background),
                     colors.cursor_has_value ? argb(colors.cursor) : 0,
                     cursor.wide_tail ? 1 : 0};
    jintArray arr = (*env)->NewIntArray(env, 12);
    if (arr) (*env)->SetIntArrayRegion(env, arr, 0, 12, info);
    return arr;
}

static GhosttyColorRgb rgb(jint c) {
    return (GhosttyColorRgb){.r = (uint8_t)(c >> 16), .g = (uint8_t)(c >> 8), .b = (uint8_t)c};
}

// The default colours, as foot.ini sets them: foreground, background, cursor (0: the foreground's) and the first
// len of the 256 colours (the rest stay xterm's).
JNIEXPORT void JNICALL FN(colors)(JNIEnv *env, jclass cls, jlong h, jint jfg, jint jbg, jint jcursor, jintArray jpalette) {
    (void)cls;
    Vt *v = vt(h);
    GhosttyColorRgb palette[256];
    ghostty_color_palette_default(palette);
    jsize n = jpalette ? (*env)->GetArrayLength(env, jpalette) : 0;
    jint given[256];
    if (n > 256) n = 256;
    if (n) (*env)->GetIntArrayRegion(env, jpalette, 0, n, given);
    for (jsize i = 0; i < n; i++) palette[i] = rgb(given[i]);
    GhosttyColorRgb fg = rgb(jfg), bg = rgb(jbg), cursor = rgb(jcursor);
    pthread_mutex_lock(&v->lock);
    ghostty_terminal_set(v->term, GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND, &fg);
    ghostty_terminal_set(v->term, GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, &bg);
    ghostty_terminal_set(v->term, GHOSTTY_TERMINAL_OPT_COLOR_CURSOR, jcursor ? &cursor : NULL);
    ghostty_terminal_set(v->term, GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, palette);
    pthread_mutex_unlock(&v->lock);
}

static bool mode(Vt *v, uint16_t value, bool ansi) {
    GhosttyTerminalModeConfig m = {.mode = ghostty_mode_new(value, ansi)};
    return ghostty_terminal_get(v->term, GHOSTTY_TERMINAL_DATA_MODE, &m) == GHOSTTY_SUCCESS && m.value;
}

// Vt.state's bits: what the program asked of the terminal, read together.
JNIEXPORT jint JNICALL FN(state)(JNIEnv *env, jclass cls, jlong h) {
    (void)env;
    (void)cls;
    Vt *v = vt(h);
    pthread_mutex_lock(&v->lock);
    bool tracking = false;
    ghostty_terminal_get(v->term, GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING, &tracking);
    GhosttyTerminalScreen screen = GHOSTTY_TERMINAL_SCREEN_PRIMARY;
    ghostty_terminal_get(v->term, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &screen);
    bool bottom = true;
    ghostty_terminal_get(v->term, GHOSTTY_TERMINAL_DATA_VIEWPORT_ACTIVE, &bottom);
    jint s = (tracking ? 1 : 0) | (screen == GHOSTTY_TERMINAL_SCREEN_ALTERNATE ? 2 : 0) | (mode(v, 1007, false) ? 4 : 0) |
             (mode(v, 1, false) ? 8 : 0) | (bottom ? 16 : 0) | (mode(v, 2004, false) ? 32 : 0) | (mode(v, 1004, false) ? 64 : 0);
    pthread_mutex_unlock(&v->lock);
    return s;
}

// {total rows, the view's first row, rows in view}.
JNIEXPORT jlongArray JNICALL FN(scrollbar)(JNIEnv *env, jclass cls, jlong h) {
    (void)cls;
    Vt *v = vt(h);
    GhosttyTerminalScrollbar sb = {0};
    pthread_mutex_lock(&v->lock);
    GhosttyResult r = ghostty_terminal_get(v->term, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &sb);
    pthread_mutex_unlock(&v->lock);
    if (r != GHOSTTY_SUCCESS) return NULL;
    jlong out[3] = {(jlong)sb.total, (jlong)sb.offset, (jlong)sb.len};
    jlongArray arr = (*env)->NewLongArray(env, 3);
    if (arr) (*env)->SetLongArrayRegion(env, arr, 0, 3, out);
    return arr;
}

// Moves the view back to the bottom, where the program writes.
JNIEXPORT void JNICALL FN(scrollToBottom)(JNIEnv *env, jclass cls, jlong h) {
    (void)env;
    (void)cls;
    Vt *v = vt(h);
    GhosttyTerminalScrollViewport by = {.tag = GHOSTTY_TERMINAL_SCROLL_VIEWPORT_BOTTOM};
    pthread_mutex_lock(&v->lock);
    ghostty_terminal_scroll_viewport(v->term, by);
    pthread_mutex_unlock(&v->lock);
}

static jbyteArray bytes(JNIEnv *env, const uint8_t *buf, size_t len) {
    if (len == 0) return NULL;
    jbyteArray arr = (*env)->NewByteArray(env, (jsize)len);
    if (arr) (*env)->SetByteArrayRegion(env, arr, 0, (jsize)len, (const jbyte *)buf);
    return arr;
}

// A mouse event at x, y pixels of a screen width x height with cells of cellWidth x cellHeight and padding left,
// top, encoded as the program asked (null when it didn't ask for mouse reports, or for this one).
// action GhosttyMouseAction, button GhosttyMouseButton (0: none, for motion), mods GhosttyMods, pressed whether a
// button is held (for motion).
JNIEXPORT jbyteArray JNICALL FN(mouse)(JNIEnv *env, jclass cls, jlong h, jint action, jint button, jint mods, jfloat x,
                                       jfloat y, jint width, jint height, jint cellWidth, jint cellHeight, jint padLeft,
                                       jint padTop, jboolean pressed) {
    (void)cls;
    Vt *v = vt(h);
    if (cellWidth < 1 || cellHeight < 1) return NULL;
    GhosttyMouseEncoderSize size = GHOSTTY_INIT_SIZED(GhosttyMouseEncoderSize);
    size.screen_width = (uint32_t)width;
    size.screen_height = (uint32_t)height;
    size.cell_width = (uint32_t)cellWidth;
    size.cell_height = (uint32_t)cellHeight;
    size.padding_left = (uint32_t)padLeft;
    size.padding_top = (uint32_t)padTop;
    bool any = pressed;
    uint8_t out[64];
    size_t len = 0;
    pthread_mutex_lock(&v->lock);
    ghostty_mouse_encoder_set_from_terminal(v->mouse, v->term);
    ghostty_mouse_encoder_set(v->mouse, GHOSTTY_MOUSE_ENCODER_OPT_SIZE, &size);
    ghostty_mouse_encoder_set(v->mouse, GHOSTTY_MOUSE_ENCODER_OPT_ANY_BUTTON_PRESSED, &any);
    ghostty_mouse_event_set_action(v->mouse_event, (GhosttyMouseAction)action);
    if (button) ghostty_mouse_event_set_button(v->mouse_event, (GhosttyMouseButton)button);
    else ghostty_mouse_event_clear_button(v->mouse_event);
    ghostty_mouse_event_set_mods(v->mouse_event, (GhosttyMods)mods);
    ghostty_mouse_event_set_position(v->mouse_event, (GhosttyMousePosition){.x = x, .y = y});
    GhosttyResult r = ghostty_mouse_encoder_encode(v->mouse, v->mouse_event, out, sizeof out, &len);
    pthread_mutex_unlock(&v->lock);
    return r == GHOSTTY_SUCCESS ? bytes(env, out, len) : NULL;
}

// Focus in or out, when the program asked for focus reports (mode 1004); else null.
JNIEXPORT jbyteArray JNICALL FN(focus)(JNIEnv *env, jclass cls, jlong h, jboolean gained) {
    (void)cls;
    Vt *v = vt(h);
    pthread_mutex_lock(&v->lock);
    bool wanted = mode(v, 1004, false);
    pthread_mutex_unlock(&v->lock);
    if (!wanted) return NULL;
    uint8_t out[8];
    size_t len = 0;
    if (ghostty_focus_encode(gained ? GHOSTTY_FOCUS_GAINED : GHOSTTY_FOCUS_LOST, out, sizeof out, &len) != GHOSTTY_SUCCESS) return NULL;
    return bytes(env, out, len);
}

// Text pasted, as the program takes it: bracketed (mode 2004), or with line feeds as returns; control bytes that
// could end the paste early are made spaces.
JNIEXPORT jbyteArray JNICALL FN(paste)(JNIEnv *env, jclass cls, jlong h, jbyteArray jtext) {
    (void)cls;
    Vt *v = vt(h);
    jsize n = (*env)->GetArrayLength(env, jtext);
    char *data = malloc((size_t)n + 1);
    if (!data) return NULL;
    (*env)->GetByteArrayRegion(env, jtext, 0, n, (jbyte *)data);
    pthread_mutex_lock(&v->lock);
    bool bracketed = mode(v, 2004, false);
    pthread_mutex_unlock(&v->lock);
    size_t need = 0;
    jbyteArray arr = NULL;
    GhosttyResult r = ghostty_paste_encode(data, (size_t)n, bracketed, NULL, 0, &need);
    if (r == GHOSTTY_SUCCESS || r == GHOSTTY_OUT_OF_SPACE) {
        uint8_t *out = malloc(need + 1);
        size_t len = 0;
        if (out && ghostty_paste_encode(data, (size_t)n, bracketed, out, need, &len) == GHOSTTY_SUCCESS) arr = bytes(env, out, len);
        free(out);
    }
    free(data);
    return arr;
}

static bool ref_at(Vt *v, int x, int y, GhosttyGridRef *ref) {
    if (x < 0 || y < 0) return false;
    GhosttyPoint pt = {.tag = GHOSTTY_POINT_TAG_VIEWPORT, .value = {.coordinate = {.x = (uint16_t)x, .y = (uint32_t)y}}};
    *ref = (GhosttyGridRef){0};
    return ghostty_terminal_grid_ref(v->term, pt, ref) == GHOSTTY_SUCCESS;
}

// The link at the cell x, y of the view (OSC 8), or null.
JNIEXPORT jbyteArray JNICALL FN(link)(JNIEnv *env, jclass cls, jlong h, jint x, jint y) {
    (void)cls;
    Vt *v = vt(h);
    jbyteArray arr = NULL;
    pthread_mutex_lock(&v->lock);
    GhosttyGridRef ref;
    size_t len = 0;
    if (ref_at(v, x, y, &ref) && ghostty_grid_ref_hyperlink_uri(&ref, NULL, 0, &len) == GHOSTTY_OUT_OF_SPACE && len) {
        uint8_t *uri = malloc(len);
        if (uri && ghostty_grid_ref_hyperlink_uri(&ref, uri, len, &len) == GHOSTTY_SUCCESS) arr = bytes(env, uri, len);
        free(uri);
    }
    pthread_mutex_unlock(&v->lock);
    return arr;
}

// Selects from cell x1, y1 to x2, y2 of the view, both included; the word at x1, y1 when word.
// Which end stays where it is when a handle is dragged: SELECT_* (Vt.kt). False when nothing was selected.
JNIEXPORT jboolean JNICALL FN(select)(JNIEnv *env, jclass cls, jlong h, jint x1, jint y1, jint x2, jint y2, jint how) {
    (void)env;
    (void)cls;
    Vt *v = vt(h);
    bool ok = false;
    pthread_mutex_lock(&v->lock);
    GhosttySelection sel = {0};
    GhosttyGridRef a, b;
    if (how == 1) {
        // A word.
        GhosttyTerminalSelectWordOptions o = GHOSTTY_INIT_SIZED(GhosttyTerminalSelectWordOptions);
        if (ref_at(v, x1, y1, &o.ref)) ok = ghostty_terminal_select_word(v->term, &o, &sel) == GHOSTTY_SUCCESS;
    } else if (how == 2 || how == 3) {
        // Moves the start (2) or the end (3) of the selection there is, in order, to x1, y1.
        GhosttySelection cur = {0}, ordered = {0};
        if (ghostty_terminal_get(v->term, GHOSTTY_TERMINAL_DATA_SELECTION, &cur) == GHOSTTY_SUCCESS &&
            ghostty_terminal_selection_ordered(v->term, &cur, GHOSTTY_SELECTION_ORDER_FORWARD, &ordered) == GHOSTTY_SUCCESS &&
            ref_at(v, x1, y1, &a)) {
            sel = ordered;
            if (how == 2) sel.start = a;
            else sel.end = a;
            ok = true;
        }
    } else if (ref_at(v, x1, y1, &a) && ref_at(v, x2, y2, &b)) {
        sel.start = a;
        sel.end = b;
        ok = true;
    }
    if (ok) ghostty_terminal_set(v->term, GHOSTTY_TERMINAL_OPT_SELECTION, &sel);
    pthread_mutex_unlock(&v->lock);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL FN(clearSelection)(JNIEnv *env, jclass cls, jlong h) {
    (void)env;
    (void)cls;
    Vt *v = vt(h);
    pthread_mutex_lock(&v->lock);
    ghostty_terminal_set(v->term, GHOSTTY_TERMINAL_OPT_SELECTION, NULL);
    pthread_mutex_unlock(&v->lock);
}

// The selection's ends, first to last, as cells of the view: {x1, y1, x2, y2}, an end off the view at -1, -1; null
// without a selection.
JNIEXPORT jintArray JNICALL FN(selection)(JNIEnv *env, jclass cls, jlong h) {
    (void)cls;
    Vt *v = vt(h);
    jint out[4] = {-1, -1, -1, -1};
    bool has = false;
    pthread_mutex_lock(&v->lock);
    GhosttySelection cur = {0}, ordered = {0};
    if (ghostty_terminal_get(v->term, GHOSTTY_TERMINAL_DATA_SELECTION, &cur) == GHOSTTY_SUCCESS &&
        ghostty_terminal_selection_ordered(v->term, &cur, GHOSTTY_SELECTION_ORDER_FORWARD, &ordered) == GHOSTTY_SUCCESS) {
        has = true;
        GhosttyPointCoordinate p;
        if (ghostty_terminal_point_from_grid_ref(v->term, &ordered.start, GHOSTTY_POINT_TAG_VIEWPORT, &p) == GHOSTTY_SUCCESS) {
            out[0] = p.x;
            out[1] = (jint)p.y;
        }
        if (ghostty_terminal_point_from_grid_ref(v->term, &ordered.end, GHOSTTY_POINT_TAG_VIEWPORT, &p) == GHOSTTY_SUCCESS) {
            out[2] = p.x;
            out[3] = (jint)p.y;
        }
    }
    pthread_mutex_unlock(&v->lock);
    if (!has) return NULL;
    jintArray arr = (*env)->NewIntArray(env, 4);
    if (arr) (*env)->SetIntArrayRegion(env, arr, 0, 4, out);
    return arr;
}

// The selected text as UTF-8, lines a program wrapped joined again, or null.
JNIEXPORT jbyteArray JNICALL FN(selectedText)(JNIEnv *env, jclass cls, jlong h) {
    (void)cls;
    Vt *v = vt(h);
    jbyteArray arr = NULL;
    pthread_mutex_lock(&v->lock);
    GhosttySelection cur = {0};
    if (ghostty_terminal_get(v->term, GHOSTTY_TERMINAL_DATA_SELECTION, &cur) == GHOSTTY_SUCCESS) {
        uint8_t *buf = NULL;
        size_t len = 0;
        ghostty_formatter_set(v->selection, GHOSTTY_FORMATTER_OPT_SELECTION, &cur);
        if (ghostty_formatter_format_alloc(v->selection, NULL, &buf, &len) == GHOSTTY_SUCCESS) {
            arr = bytes(env, buf, len);
            ghostty_free(NULL, buf, len);
        }
        ghostty_formatter_set(v->selection, GHOSTTY_FORMATTER_OPT_SELECTION, NULL);
    }
    pthread_mutex_unlock(&v->lock);
    return arr;
}

static jstring utf(JNIEnv *env, const char *s) {
    if (!s) return NULL;
    // Modified UTF-8 can't take what a program sent byte for byte: build the string from its bytes in Kotlin instead.
    jsize n = (jsize)strlen(s);
    jbyteArray arr = (*env)->NewByteArray(env, n);
    if (!arr) return NULL;
    (*env)->SetByteArrayRegion(env, arr, 0, n, (const jbyte *)s);
    jclass str = (*env)->FindClass(env, "java/lang/String");
    jmethodID init = (*env)->GetMethodID(env, str, "<init>", "([BLjava/lang/String;)V");
    jstring out = (*env)->NewObject(env, str, init, arr, (*env)->NewStringUTF(env, "UTF-8"));
    return out;
}

// What happened since the last call, as pairs of strings: {"bell", ""}, {"title", the title}, {"clipboard", text},
// {"notify", title + "\n" + body}. Null when nothing did.
JNIEXPORT jobjectArray JNICALL FN(events)(JNIEnv *env, jclass cls, jlong h) {
    (void)cls;
    Vt *v = vt(h);
    pthread_mutex_lock(&v->lock);
    int bells = v->bells, titled = v->title_changed;
    char *clip = v->clipboard, *nt = v->notify_title, *nb = v->notify_body, *title = NULL;
    v->bells = 0;
    v->title_changed = 0;
    v->clipboard = v->notify_title = v->notify_body = NULL;
    if (titled) {
        GhosttyString t = {0};
        if (ghostty_terminal_get(v->term, GHOSTTY_TERMINAL_DATA_TITLE, &t) == GHOSTTY_SUCCESS) title = copy(t);
    }
    pthread_mutex_unlock(&v->lock);
    int n = (bells ? 2 : 0) + (title ? 2 : 0) + (clip ? 2 : 0) + (nt || nb ? 2 : 0);
    jobjectArray out = NULL;
    if (n) {
        out = (*env)->NewObjectArray(env, n, (*env)->FindClass(env, "java/lang/String"), NULL);
        int i = 0;
#define PUT(tag, val)                                                                   \
    do {                                                                                \
        if (out) {                                                                      \
            (*env)->SetObjectArrayElement(env, out, i++, (*env)->NewStringUTF(env, tag)); \
            (*env)->SetObjectArrayElement(env, out, i++, val);                          \
        }                                                                               \
    } while (0)
        if (bells) PUT("bell", (*env)->NewStringUTF(env, ""));
        if (title) PUT("title", utf(env, title));
        if (clip) PUT("clipboard", utf(env, clip));
        if (nt || nb) {
            size_t a = nt ? strlen(nt) : 0, b = nb ? strlen(nb) : 0;
            char *both = malloc(a + b + 2);
            if (both) {
                memcpy(both, nt ? nt : "", a);
                both[a] = '\n';
                memcpy(both + a + 1, nb ? nb : "", b);
                both[a + b + 1] = 0;
                PUT("notify", utf(env, both));
                free(both);
            }
        }
#undef PUT
    }
    free(title);
    free(clip);
    free(nt);
    free(nb);
    return out;
}

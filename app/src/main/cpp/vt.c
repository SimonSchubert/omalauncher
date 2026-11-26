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
    // The grid cells() last read: another one is read whole.
    int read_cols, read_rows;
    // Which row was where at that read, ids_rows of them (0: not known), and room for the next read's: a screen
    // that only scrolled is told by them.
    GhosttyRenderStateRowId *ids, *ids_next;
    int ids_rows, ids_cap;
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
    free(v->ids);
    free(v->ids_next);
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

// Feeds len bytes the program wrote. Returns what that left to fetch: 1 for replies(), 2 for events(), or both.
JNIEXPORT jint JNICALL FN(write)(JNIEnv *env, jclass cls, jlong h, jbyteArray jbuf, jint len) {
    (void)cls;
    Vt *v = vt(h);
    jbyte *buf = (*env)->GetByteArrayElements(env, jbuf, NULL);
    if (!buf) return 0;
    pthread_mutex_lock(&v->lock);
    ghostty_terminal_vt_write(v->term, (const uint8_t *)buf, (size_t)len);
    jint left = (v->reply_len ? 1 : 0) |
                (v->bells || v->title_changed || v->clipboard || v->notify_title || v->notify_body ? 2 : 0);
    pthread_mutex_unlock(&v->lock);
    (*env)->ReleaseByteArrayElements(env, jbuf, buf, JNI_ABORT);
    return left;
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
// keyboard protocol): key a GhosttyKey, mods GhosttyMods, consumed the mods that went into text (Shift for "A"),
// text what the key types (or null), unshifted the character it types without modifiers (or 0). Null when it sends
// nothing.
JNIEXPORT jbyteArray JNICALL FN(key)(JNIEnv *env, jclass cls, jlong h, jint key, jint mods, jint consumed, jstring jtext,
                                     jint unshifted) {
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
    ghostty_key_event_set_consumed_mods(v->key, (GhosttyMods)consumed);
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

static bool mode(Vt *v, uint16_t value, bool ansi) {
    GhosttyTerminalModeConfig m = {.mode = ghostty_mode_new(value, ansi)};
    return ghostty_terminal_get(v->term, GHOSTTY_TERMINAL_DATA_MODE, &m) == GHOSTTY_SUCCESS && m.value;
}

// Vt.state's bits: what the program asked of the terminal, read together. With the lock held.
static jint state(Vt *v) {
    bool tracking = false;
    ghostty_terminal_get(v->term, GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING, &tracking);
    GhosttyTerminalScreen screen = GHOSTTY_TERMINAL_SCREEN_PRIMARY;
    ghostty_terminal_get(v->term, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &screen);
    bool bottom = true;
    ghostty_terminal_get(v->term, GHOSTTY_TERMINAL_DATA_VIEWPORT_ACTIVE, &bottom);
    return (tracking ? 1 : 0) | (screen == GHOSTTY_TERMINAL_SCREEN_ALTERNATE ? 2 : 0) | (mode(v, 1007, false) ? 4 : 0) |
           (mode(v, 1, false) ? 8 : 0) | (bottom ? 16 : 0) | (mode(v, 2004, false) ? 32 : 0) | (mode(v, 1004, false) ? 64 : 0) |
           (v->held ? 128 : 0);
}

JNIEXPORT jint JNICALL FN(state)(JNIEnv *env, jclass cls, jlong h) {
    (void)env;
    (void)cls;
    Vt *v = vt(h);
    pthread_mutex_lock(&v->lock);
    jint s = state(v);
    pthread_mutex_unlock(&v->lock);
    return s;
}

// What cells() writes into its info array (Vt.kt has the same), and INFO_RESULT's values.
enum {
    INFO_RESULT,
    INFO_COLS,
    INFO_ROWS,
    INFO_CURSOR_X,
    INFO_CURSOR_Y,
    INFO_CURSOR_VISIBLE,
    INFO_CURSOR_STYLE,
    INFO_CURSOR_COLOR,
    INFO_CURSOR_WIDE_TAIL,
    INFO_FG,
    INFO_BG,
    INFO_MORE,
    INFO_STATE,
    INFO_ALL,
    INFO_SCROLLED,
    INFO_COUNT
};
enum { RESULT_HELD, RESULT_READ, RESULT_SMALL, RESULT_MORE };

// The arrays cells() fills, and how much of more is used and would be needed.
typedef struct {
    jint *cp, *fg, *bg, *flags, *more;
    jsize more_cap;
    jint more_len, more_need;
} Out;

// The row the iterator is on into the arrays from index at, cols cells. Whether any cell differs from what was there.
static bool read_row(Vt *v, Out *o, int at, int cols, const GhosttyRenderStateColors *colors) {
    bool changed = false;
    jint default_fg = argb(colors->foreground), default_bg = argb(colors->background);
    GhosttyRenderStateRowSelection sel = GHOSTTY_INIT_SIZED(GhosttyRenderStateRowSelection);
    bool selected = ghostty_render_state_row_get(v->rows, GHOSTTY_RENDER_STATE_ROW_DATA_SELECTION, &sel) == GHOSTTY_SUCCESS;
    ghostty_render_state_row_get(v->rows, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &v->cells);
    for (int x = 0; x < cols; x++) {
        int i = at + x;
        jint cp = 0, fg = default_fg, bg = default_bg, f = 0;
        // A row shorter than the screen ends in empty cells.
        if (ghostty_render_state_row_cells_next(v->cells)) {
            uint32_t glen = 0, cluster[16];
            ghostty_render_state_row_cells_get(v->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN, &glen);
            if (glen) {
                GhosttyCodepointBuffer b = {.ptr = cluster, .cap = 16};
                ghostty_render_state_row_cells_get(v->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF, &b);
                cp = (jint)cluster[0];
                if (glen > 1) {
                    jint n = glen < 16 ? (jint)glen : 16;
                    if (o->more_len + 2 + n <= o->more_cap) {
                        o->more[o->more_len++] = i;
                        o->more[o->more_len++] = n;
                        for (jint k = 0; k < n; k++) o->more[o->more_len++] = (jint)cluster[k];
                    }
                    o->more_need += 2 + n;
                    f |= 1 << 12;
                }
            }
            GhosttyColorRgb c;
            if (ghostty_render_state_row_cells_get(v->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR, &c) == GHOSTTY_SUCCESS) fg = argb(c);
            if (ghostty_render_state_row_cells_get(v->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR, &c) == GHOSTTY_SUCCESS) {
                bg = argb(c);
                f |= 1 << 7;
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
            if (selected && x >= sel.start_x && x <= sel.end_x) f |= 1 << 9;
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
        }
        if (o->cp[i] != cp || o->fg[i] != fg || o->bg[i] != bg || o->flags[i] != f) {
            o->cp[i] = cp;
            o->fg[i] = fg;
            o->bg[i] = bg;
            o->flags[i] = f;
            changed = true;
        }
    }
    return changed;
}

// The ids of the render state's rows into ids_next. False without room for them.
static bool row_ids(Vt *v, int rows) {
    if (rows > v->ids_cap) {
        GhosttyRenderStateRowId *a = realloc(v->ids, sizeof *a * (size_t)rows);
        if (a) v->ids = a;
        GhosttyRenderStateRowId *b = realloc(v->ids_next, sizeof *b * (size_t)rows);
        if (b) v->ids_next = b;
        v->ids_rows = 0;
        if (!a || !b) return false;
        v->ids_cap = rows;
    }
    memset(v->ids_next, 0, sizeof *v->ids_next * (size_t)rows);
    ghostty_render_state_get(v->render, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &v->rows);
    for (int y = 0; y < rows && ghostty_render_state_row_iterator_next(v->rows); y++)
        ghostty_render_state_row_get(v->rows, GHOSTTY_RENDER_STATE_ROW_DATA_ID, &v->ids_next[y]);
    return true;
}

static bool same_row(GhosttyRenderStateRowId a, GhosttyRenderStateRowId b) {
    return (a.bits[0] || a.bits[1]) && a.bits[0] == b.bits[0] && a.bits[1] == b.bits[1];
}

// How many rows the screen moved up since the last read (down when negative), going by where its first or its last
// row was then; 0 when neither was on screen. A guess is enough: rows are still compared with what's moved there.
static int scrolled(const Vt *v, int rows) {
    for (int d = 1; d < rows; d++)
        if (same_row(v->ids_next[0], v->ids[d])) return d;
    for (int d = 1; d < rows; d++)
        if (same_row(v->ids_next[rows - 1], v->ids[rows - 1 - d])) return -d;
    return 0;
}

// Moves the rows of a by d rows up (down when negative), as the screen moved.
static void move_rows(jint *a, int cols, int rows, int d) {
    size_t n = sizeof(jint) * (size_t)cols;
    if (d > 0) memmove(a, a + (size_t)d * cols, n * (size_t)(rows - d));
    else memmove(a + (size_t)-d * cols, a, n * (size_t)(rows + d));
}

// The screen into arrays of at least cols * rows, row by row: the first code point of each cell (0 for an empty
// one), foreground and background as ARGB (the defaults where the cell sets none) and Cells' flags (Vt.kt). Only the
// rows the program wrote to since the last call are read; changed (a boolean per row) says which of them now
// differ from what the arrays held, so the arrays must be the last call's, or all is set: then every row is read and
// counts as changed. When the screen only scrolled since then, the arrays' rows are moved first and INFO_SCROLLED
// says by how many (up when positive), so rows that just moved haven't changed; the rows that came into view have.
// Cells of more than one code point (an emoji sequence, combining marks) in the rows read also go
// into more, as {cell index, count, code points...}. Nothing is read while the program holds the screen
// (synchronized output), unless while_held. info gets INFO_*: the result, the grid, the cursor (column and row -1
// when it's off the view, its GhosttyCursorStyle, colour 0 for none, whether it's on a wide character's second
// cell), the default colours, the ints used in more, state()'s bits and whether every row was read. The result is
// RESULT_HELD (nothing read), RESULT_READ, RESULT_SMALL (the arrays don't hold cols * rows cells, nothing read) or
// RESULT_MORE (more is too small: INFO_MORE says what it needs; call again with all). False when it fails.
JNIEXPORT jboolean JNICALL FN(cells)(JNIEnv *env, jclass cls, jlong h, jintArray jcp, jintArray jfg, jintArray jbg,
                                     jintArray jflags, jintArray jmore, jbooleanArray jchanged, jintArray jinfo,
                                     jboolean all, jboolean while_held) {
    (void)cls;
    Vt *v = vt(h);
    jint info[INFO_COUNT] = {0};
    if ((*env)->GetArrayLength(env, jinfo) < INFO_COUNT) return JNI_FALSE;
    // Only taking the terminal's changes needs the lock; the render state is a copy that one thread reads.
    pthread_mutex_lock(&v->lock);
    info[INFO_STATE] = state(v);
    bool held = v->held && !while_held;
    GhosttyResult updated = held ? GHOSTTY_SUCCESS : ghostty_render_state_begin_update(v->render, v->term);
    pthread_mutex_unlock(&v->lock);
    if (updated != GHOSTTY_SUCCESS) return JNI_FALSE;
    jsize rows_cap = (*env)->GetArrayLength(env, jchanged);
    jboolean *changed = (*env)->GetBooleanArrayElements(env, jchanged, NULL);
    if (!changed) return JNI_FALSE;
    memset(changed, 0, (size_t)rows_cap);
    if (held) {
        info[INFO_RESULT] = RESULT_HELD;
    } else {
        ghostty_render_state_end_update(v->render);
        uint16_t cols = 0, rows = 0;
        ghostty_render_state_get(v->render, GHOSTTY_RENDER_STATE_DATA_COLS, &cols);
        ghostty_render_state_get(v->render, GHOSTTY_RENDER_STATE_DATA_ROWS, &rows);
        GhosttyRenderStateColors colors = GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
        ghostty_render_state_get(v->render, GHOSTTY_RENDER_STATE_DATA_COLORS, &colors);
        GhosttyRenderStateCursor cursor = GHOSTTY_INIT_SIZED(GhosttyRenderStateCursor);
        ghostty_render_state_get(v->render, GHOSTTY_RENDER_STATE_DATA_CURSOR, &cursor);
        GhosttyRenderStateDirty dirty = GHOSTTY_RENDER_STATE_DIRTY_FULL;
        ghostty_render_state_get(v->render, GHOSTTY_RENDER_STATE_DATA_DIRTY, &dirty);
        info[INFO_COLS] = cols;
        info[INFO_ROWS] = rows;
        info[INFO_CURSOR_X] = cursor.viewport_has_value ? cursor.viewport_x : -1;
        info[INFO_CURSOR_Y] = cursor.viewport_has_value ? cursor.viewport_y : -1;
        info[INFO_CURSOR_VISIBLE] = cursor.visible ? 1 : 0;
        info[INFO_CURSOR_STYLE] = (jint)cursor.visual_style;
        info[INFO_CURSOR_COLOR] = colors.cursor_has_value ? argb(colors.cursor) : 0;
        info[INFO_CURSOR_WIDE_TAIL] = cursor.wide_tail ? 1 : 0;
        info[INFO_FG] = argb(colors.foreground);
        info[INFO_BG] = argb(colors.background);
        jsize need = (jsize)cols * rows;
        if (cols != v->read_cols || rows != v->read_rows) all = JNI_TRUE;
        if ((*env)->GetArrayLength(env, jcp) < need || (*env)->GetArrayLength(env, jfg) < need ||
            (*env)->GetArrayLength(env, jbg) < need || (*env)->GetArrayLength(env, jflags) < need || rows_cap < rows) {
            info[INFO_RESULT] = RESULT_SMALL;
        } else if (all || dirty != GHOSTTY_RENDER_STATE_DIRTY_FALSE) {
            bool ids = row_ids(v, rows);
            int moved = ids && !all && v->ids_rows == rows ? scrolled(v, rows) : 0;
            // A screen that moved is compared row by row, whatever the terminal marked.
            bool every = all || moved || dirty == GHOSTTY_RENDER_STATE_DIRTY_FULL;
            // No JNI call and nothing that waits while the arrays are held.
            Out o = {.more_cap = (*env)->GetArrayLength(env, jmore)};
            o.cp = (*env)->GetPrimitiveArrayCritical(env, jcp, NULL);
            o.fg = (*env)->GetPrimitiveArrayCritical(env, jfg, NULL);
            o.bg = (*env)->GetPrimitiveArrayCritical(env, jbg, NULL);
            o.flags = (*env)->GetPrimitiveArrayCritical(env, jflags, NULL);
            o.more = (*env)->GetPrimitiveArrayCritical(env, jmore, NULL);
            bool got = o.cp && o.fg && o.bg && o.flags && o.more;
            if (got) {
                if (moved) {
                    move_rows(o.cp, cols, rows, moved);
                    move_rows(o.fg, cols, rows, moved);
                    move_rows(o.bg, cols, rows, moved);
                    move_rows(o.flags, cols, rows, moved);
                }
                ghostty_render_state_get(v->render, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &v->rows);
                // Every row, or the ones the terminal marked: clean rows are as the arrays have them.
                for (uint16_t y = 0, n = 0;; n++) {
                    if (every) {
                        if (n >= rows || !ghostty_render_state_row_iterator_next(v->rows)) break;
                        y = n;
                    } else if (!ghostty_render_state_row_iterator_next_dirty(v->rows, &y) || y >= rows) {
                        break;
                    }
                    // A row that came into view counts as changed even if it looks like the one that was there.
                    bool came = moved > 0 ? y >= rows - moved : y < -moved;
                    changed[y] = read_row(v, &o, y * cols, cols, &colors) || all || came;
                }
            }
            if (o.more) (*env)->ReleasePrimitiveArrayCritical(env, jmore, o.more, 0);
            if (o.flags) (*env)->ReleasePrimitiveArrayCritical(env, jflags, o.flags, 0);
            if (o.bg) (*env)->ReleasePrimitiveArrayCritical(env, jbg, o.bg, 0);
            if (o.fg) (*env)->ReleasePrimitiveArrayCritical(env, jfg, o.fg, 0);
            if (o.cp) (*env)->ReleasePrimitiveArrayCritical(env, jcp, o.cp, 0);
            if (!got) {
                (*env)->ReleaseBooleanArrayElements(env, jchanged, changed, JNI_ABORT);
                return JNI_FALSE;
            }
            // The arrays no longer hold the last call's rows, whether or not all of more fitted.
            v->read_cols = v->read_rows = v->ids_rows = 0;
            if (o.more_need > o.more_len) {
                info[INFO_RESULT] = RESULT_MORE;
                info[INFO_MORE] = o.more_need;
            } else {
                info[INFO_RESULT] = RESULT_READ;
                info[INFO_MORE] = o.more_len;
                info[INFO_ALL] = all ? 1 : 0;
                info[INFO_SCROLLED] = moved;
                v->read_cols = cols;
                v->read_rows = rows;
                if (ids) {
                    GhosttyRenderStateRowId *was = v->ids;
                    v->ids = v->ids_next;
                    v->ids_next = was;
                    v->ids_rows = rows;
                }
                ghostty_render_state_clean(v->render);
            }
        } else {
            info[INFO_RESULT] = RESULT_READ;
        }
    }
    (*env)->ReleaseBooleanArrayElements(env, jchanged, changed, 0);
    (*env)->SetIntArrayRegion(env, jinfo, 0, INFO_COUNT, info);
    return JNI_TRUE;
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

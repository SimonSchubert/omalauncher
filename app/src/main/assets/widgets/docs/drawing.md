# Drawing

Part of the widget docs: README.md, one folder up, comes first, and paths here are from the widgets folder.

## Canvas

Drawing calls in dp, with `width`, `height` and `time` available. Angles are
degrees, with 0 at 12 o'clock, running clockwise.

```owl
Canvas {
  let r = min(width, height) / 2 - 4
  circle(width / 2, height / 2, r, stroke: border, width: 2)
  arc(width / 2, height / 2, r, 0, load * 3.6, stroke: accent, width: 6, cap: round)
  for i in range(12) {
    rotate(i * 30, width / 2, height / 2) { line(width / 2, 6, width / 2, 12, stroke: muted) }
  }
  text("${load}%", width / 2, height / 2, size: 18, align: center, color: foreground)
}
```

| call | |
|---|---|
| `rect(x, y, w, h)` | `radius:` |
| `circle(cx, cy, r)` | |
| `arc(cx, cy, r, start, sweep)` | filled arcs are pie slices |
| `line(x1, y1, x2, y2)` | |
| `path(points)` | points are `[x, y]` or `{x, y}`; `closed: true` |
| `text(s, x, y)` | `size:`, `color:`, `align: start/center/end`, `bold:`; y is the text's middle. Since owl 10 also `font:`, `fontWeight:`, `italic:` and `letterSpacing:` as on a Text, and `s` can be a list of spans |
| `image(src, x, y, w, h)` | `src` as for `Image`; `color:` paints an SVG in one color |
| `sprite(rows, x, y, pixel)` | pixel art (owl 3): `rows` is a list of strings, or one `"""` string of lines, with one letter per art pixel and `.` for empty. `palette: {b: accent, e: background}` colors each letter; letters it doesn't name aren't drawn, so one art can be drawn in parts that move separately. Without a palette every letter takes `fill`/`color`. `pixel` is the size of an art pixel in dp; edges land on whole screen pixels, so no seams show |
| `translate(x, y) { }`, `rotate(deg, cx, cy) { }`, `scale(sx, sy, cx, cy) { }`, `clip(x, y, w, h) { }`, `layer(alpha) { }` | apply to the calls inside |

Styles: `fill:` (a color or gradient), `stroke:`, `width:` (stroke width),
`cap: round/butt/square`. Without either, a shape is filled with `color:` or
the foreground. A widget that reads `time` animates every frame while it's on
screen, and only while it reads it: a `time` under an `if` that's false, like a
backdrop drawn only while music plays, costs nothing (older launchers
animate whenever `time` appears at all).

## Shader

A GPU fragment shader in AGSL (Android 13+; older phones show `fallback`).
Write `half4 main(float2 p)`. `size` (pixels) and `time` (seconds) are
declared for you. Declare your own uniforms and pass them in `uniforms`:
numbers become `float`, lists become `float2`–`float4`, colors become `half4`.

```owl
Shader {
  fallback: surface
  uniforms: { tint: accent, speed: 0.5 }
  code: """
    uniform half4 tint;
    uniform float speed;
    half4 main(float2 p) {
      float w = sin(p.x / size.x * 6.0 + time * speed) * 0.5 + 0.5;
      return half4(tint.rgb * w, 1.0);
    }
  """
}
```

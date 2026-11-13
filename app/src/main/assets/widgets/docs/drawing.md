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
the foreground. A widget that reads `time` animates while it's on screen, up to
60 frames a second on any screen, and only while it reads it: a `time` under an `if` that's false, like a
backdrop drawn only while music plays, costs nothing (older launchers
animate whenever `time` appears at all). Each frame costs battery for as long as the page shows, so say how many
the animation needs with `fps:` (owl 19, see docs/declarations.md): `fps: 10` for a pet that blinks and breathes,
`fps: tapped ? 30 : 10` for one that hops after a tap. Move things by `time`, not by counting frames, so they keep
their speed at any rate. A game whose things move by their speed, bounce and collide keeps them in states and
moves them in `every frame` by `dt` (owl 21, see Triggers in docs/declarations.md, and `examples/space-bounce`).

## 3D

`scene(camera) { }` in a Canvas draws 3D models (owl 22). Each `model(mesh, ...)` inside it is placed,
turned, lit and seen through the camera; the launcher does that work, not the widget, so a scene of a
few thousand triangles keeps up at 60 frames a second. A scene holds only `model` calls (with `if`, `for`
and `let` around them); draw the Canvas's 2D calls before it, to show behind, or after it, over it.

```owl
app "spin" {
  meta { owl: 22 }
  let cam = {eye: [0, 2, 5], look: [0, 0, 0]}
  Canvas {
    fill: true
    scene(cam, fog: background) {
      model(cube(), turn: [0, time * 40, 0], color: accent)
      model(plane(8), at: [0, -0.5, 0], scale: 10, color: surface)
    }
    text("a cube", width / 2, 40, align: center)
  }
}
```

Space: `y` is up, and with the default camera `x` runs right and `z` toward you, as in OBJ files and OpenGL.
Units are whatever the game decides; the built-in meshes are one unit across.

**The camera** is a map: `eye` (where it is, `[0, 0, 5]` if left out), `look` (the point it looks at, `[0, 0, 0]`),
`up` (`[0, 1, 0]`), `fov` (degrees seen from the canvas's top to its bottom, 60), `near` and `far` (nothing nearer
than `near`, 0.1, or past `far`, 1000, is drawn; keep `far` as short as the game allows, as less is then drawn).
Points can be lists or `{x, y, z}` maps. Build it in a let from states, and the camera moves with them.

`scene(camera, ...)` takes `light:` (the direction the light comes from, `[0.4, 1, 0.6]`: above, right and in front),
`ambient:` (how bright the side turned from the light still is, 0 to 1, 0.35) and `fog:` (a color, usually
`background`, that things fade into from halfway to `far`, so the world's end doesn't show).

`model(mesh, ...)` takes `at:` (where, `[0, 0, 0]`), `turn:` (degrees around x, y and z: `[0, 90, 0]` turns a model
facing +z to face +x; it turns around z first, then x, then y, so `[pitch, heading, roll]`), `scale:` (a number, or
`[x, y, z]` to stretch), `color:` (for faces without a color of their own, the foreground if left out), `image:` (a
texture, see below), `ambient:` (its own; 1 is unlit, for things that glow), `smooth:` (true shades it smoothly across
its faces, false face by face; spheres, cylinders and terrain are smooth unless told) and `twoSided: true`
(draws the faces turned away too: for a flat sign seen from behind, or a mesh whose faces aren't wound outward).

**Meshes** are values made once, in a top-level let, and drawn as often as wanted:

| | |
|---|---|
| `cube()` | from -0.5 to 0.5 each way |
| `sphere(n)` | one unit across, `n` segments around (16; 6 to 8 looks low-poly with `smooth: false`) |
| `cylinder(n)`, `cone(n)` | one unit across and high, standing on y |
| `plane(n)` | a square on the ground, facing up, of `n` x `n` cells; an `image` repeats once per cell |
| `terrain(heights, colors)` | ground from rows of heights, one unit apart, the first row at the back (-z); `colors`, optional, are its cells', rows one shorter each way. An `image` repeats once per cell |
| `mesh(points, faces, colors)` | your own: `points` are `[x, y, z]`, `faces` lists of three or more point numbers (from 0) going counterclockwise as seen from outside, `colors` optional, one per face |
| `parseObj(text, materials)` | a Wavefront OBJ file's text (from `file(...)` or `http(...)`): its points, faces and texture places. `materials` maps the names its `usemtl` lines use to colors, `{hull: accent, glass: cyan}` |

A mesh isn't a list: it has no 1000-item limit, up to 65536 triangles. Colors in a mesh, like a face's `accent`,
follow the theme. A file's mesh is `null` until the file has loaded, and a `model` of `null` draws nothing.

**Textures**: `image:` takes a src as `Image` does (an asset, a file, a URL) and lays it on the mesh by its texture
places: the built-in meshes have them, as do OBJ files with `vt` lines; `mesh(...)` doesn't. The face's color and
light tint the image, so give a textured model `color: white`. Textures are laid on straight across each triangle,
as on early 3D consoles: a big textured triangle near the camera bends the image. Give large ground more cells,
`plane(16)` rather than `plane(1)`, and it stays straight.

**Games** move things in `every frame` by `dt`, in world units, and check hits as distances between points:
`dist(a, b)` is how far apart two points are (`dist(v)` a vector's length), and with it a sphere hits a sphere when
`dist(a.at, b.at) < a.r + b.r`. Also `dot(a, b)`, `cross(a, b)`, `norm(v)` (the same direction, one unit long) and
`turn(v, [pitch, heading, roll])`, which turns a vector as `model`'s `turn:` turns a model: `turn([0, 0, -1], [0, heading, 0])`
is the way a ship with that heading flies. `project(point, camera, width, height)` is where a point shows on a
Canvas that size, `{x, y, depth}`, or `null` behind the camera: for a label over a model, a sun behind the
scene, or what a tap hit (the model whose point shows nearest the finger).

**Ground under everything**: a scene sorts its own triangles only, and scenes draw one after another. Draw the
ground (roads, floors, water) in a scene of its own first, and what stands on it in a second scene with the same
camera: then nothing on the ground is ever sorted under it, however big its triangles are.

What it can't do: there's no depth buffer. Triangles are sorted by their distance from the camera and drawn
far to near, so two that cut through each other, or a long one lying over a short one, can show in the wrong
order; keep models apart, and split long thin ground into cells. Edges aren't smoothed. One scene draws at most
60000 triangles a frame, after those out of view or facing away are dropped. See `examples/canyon-run`.

## Shader

A GPU fragment shader in AGSL (Android 13+; older phones show `fallback`).
Write `half4 main(float2 p)`. `size` (pixels) and `time` (seconds) are
declared for you. Declare your own uniforms and pass them in `uniforms`:
numbers become `float`, lists become `float2`–`float4`, colors become `half4`.
Since owl 22 a list can fill an array uniform, its numbers in order, lists in it
flattened, at most 4096: `uniform float3 rocks[8];` takes `rocks.map(r => [r.x, r.y, r.size])`
with exactly 8 of them. And `uniform shader name;` samples an image, given as a src the way
`Image` takes one: `name.eval(uv)` is its color at `uv`, from 0 to 1 across it and repeating past.

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

The code runs for every pixel of every frame, so keep its loops small: a pixel
may take at most 1000 loop turns, counting a loop inside another as the outer
loop's turns times the inner one's, and a call to your own function as that
function's turns: a loop of 16 turns inside one of 8 takes 128. Over that, the shader
isn't drawn (the tile shows `fallback`), the widget's error says how many it
took, and the store won't take it. Count with fixed numbers or a `const`:
`for (int i = 0; i < 8; i++)` or `i < STEPS` after `const int STEPS = 8;`.

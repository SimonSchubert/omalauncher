# LinuxApp and LinuxKey

Part of the widget docs: README.md, one folder up, comes first, and paths here are from the widgets folder.

Use `LinuxApp` to display one foreground Linux Wayland window inside a face
or widget (owl 13). Keep the surrounding controls and layout in OWL. Use
`Terminal` when you want its automatic foot startup and keyboard behaviour.

This complete example uses foot, available in the launcher's Linux installation:

```owl
face "linux window" {
  meta { owl: 13 }
  requires { pacman: ["foot", "bash"] }
  page: -1
  padding: 0
  LinuxApp {
    command: 'foot --app-id="$OMALAUNCHER_APP_ID" -e bash'
    fill: true
  }
}
```

`examples/46-linux-app.owl` is a ready-to-copy version;
`examples/47-linux-keys.owl` adds OWL keyboard controls. Examples are inactive
until copied from `examples/` into the widgets directory. Declare every package
your command needs in `requires { pacman: [...] }`; declare the Linux client and
its runtime dependencies, not just the shell used to launch it.

The command runs in `bash -c`; quote values with `sh(value)`. Each instance gets
an automatic Wayland ID exported in `OMALAUNCHER_APP_ID`,
`SDL_VIDEO_WAYLAND_WMCLASS` (SDL2) and `SDL_APP_ID` (SDL3). Other clients must use
that ID (for example `foot --app-id="$OMALAUNCHER_APP_ID"`) or declare `appId`
with the exact ID the client publishes. Run in the foreground and disable
single-instance/daemon behaviour. A mismatched ID opens a normal Linux task;
the node displays the ID it is waiting for. Fixed IDs must be unique.

Tap to start, then tap the window to activate input. The soft keyboard opens
only through the ⌨ button. Outside tap, Back, page changes and Android focus
loss release input. The × button stops the program, including during startup;
tap the node to restart. Exit errors show recent command output. Removing the
widget or changing its command/ID closes its window and cancels its process.
Leaving its page keeps it running, without any application-specific pause.
A hidden conditional node or an item removed from a list also retains its session
until the widget is removed/reloaded or its command/ID changes when rendered again;
use × before hiding a program you want to stop. In a `for`, give each item a stable
`key` so reordering keeps the correct session. Repeated component uses have
independent sessions, including when their commands are identical.

Stopping keeps the session reserved until its process actually exits. If it takes
more than three seconds, the node reports that stopping is incomplete; it does not
start a second copy. The launcher avoids forcibly killing just proot, which can
leave guest processes behind. Error output is bounded even for programs that write
long lines without line breaks.

Only one toplevel per node is embedded, using the existing Wayland renderer;
additional windows open normally. This is not Android app embedding or X11
support. Start with a software-rendered Wayland/SDL client. Terminal nodes retain
their automatic startup and keyboard behaviour.

### Writing a face around a Linux application

Choose a client with a Wayland backend that works with this compositor. Start
with software rendering, and use a foreground, separate instance. Pass the
automatic ID through the client's supported option or SDL hint; use a fixed
`appId` only when the program cannot take a generated one. Quote file names and
other values inserted into `command` with `sh(value)`.

The node receives a new window size when its layout changes. Opening the soft
keyboard lifts the face and reduces the available height when needed; hiding it
restores the space. Design the face to resize, and preserve the aspect ratio of
game or video content in the client when that matters.

An existing emulator with a compatible Wayland/SDL backend can be the Linux
client, with the user's ROM path passed using `sh(value)` and the emulator's
packages declared in `requires`. Touch/pointer and keyboard input inside the
active LinuxApp window already reach that client. Verify its app ID, rendering
backend and controls on the phone, as with any other embedded application.

### Keyboard controls in OWL

Give the window an `inputId`, then use `LinuxKey` nodes to send ordinary physical
keyboard keys to it. The client needs keyboard bindings, not a special controller
API. This complete shell example holds Z and Left independently; the Save control
holds Ctrl+S as a chord:

```owl
face "linux keyboard controls" {
  meta { owl: 13 }
  requires { pacman: ["foot", "bash"] }
  page: 0
  Column {
    fill: true
    LinuxApp {
      inputId: "client"
      command: 'foot --app-id="$OMALAUNCHER_APP_ID" -e bash'
      weight: 1
      fill: width
    }
    Row {
      LinuxKey { target: "client"; keys: "left"; text: "←"; size: 56 }
      LinuxKey { target: "client"; keys: "z"; text: "A"; size: 56 }
      LinuxKey { target: "client"; keys: ["leftctrl", "s"]; text: "Save" }
    }
  }
}
```

`inputId` and `target` are names local to one running face/widget. Use 1–64 letters,
digits, underscores, dots or hyphens, starting with a letter, digit or underscore.
They are independent of the Wayland `appId`. Names must identify one session;
duplicate names on different sessions disable their controls. In loops/components,
pass a unique name per session. Targets must be rendered; hiding/removing the
LinuxApp disables its controls even though its process remains running.

Start the program by tapping its LinuxApp first. Controls are disabled until its
window exists. A press activates that client without opening the soft keyboard,
sends key-down immediately and holds until release; sliding outside the control
cancels the hold. Several fingers can hold different controls, or the same control.
Overlapping chords and a real keyboard retain their independent holds. Back, page
changes, focus loss, stop, node removal and target/key changes release held keys.
Cancelled queued presses cannot reach a different window or reappear after focus
returns. Controls do not automatically repeat: games can poll the held key state.
An accessibility click sends one press/release pair. `LinuxKey` owns its gesture;
do not attach `onTap`, `onDrag`, `onLongPress`, `onPress` or `onRelease` handlers to it.

`keys` accepts one case-insensitive name or a list of 1–16 names for a chord.
Names are physical US-layout positions: `a`–`z`, `0`–`9`, `left`, `right`, `up`,
`down`, `enter`, `space`, `escape`, `tab`, `backspace`, `home`, `end`, `pageup`,
`pagedown`, `insert`, `delete`, `f1`–`f12`, `leftshift`, `rightshift`, `leftctrl`,
`rightctrl`, `leftalt`, `rightalt`, `leftmeta`, `rightmeta`, `minus`, `equal`,
`leftbracket`, `rightbracket`, `semicolon`, `apostrophe`, `grave`, `backslash`,
`comma`, `period`, `slash`. Order chord modifiers before the other keys.
Invalid dynamic names/keys disable the whole control. Labeling a button A does
not choose an emulator binding: set `keys: "z"` and configure the emulator's A
binding to that key. For a Game Boy face, lay out a directional pad, A/B and
Start/Select with these nodes, and choose a compatible Linux emulator separately.
The SDL demo in the repository's `examples/linux-app/` directory shows eight controls and visible held-key
indicators without requiring an emulator or ROM.

After saving, run `owl-check <file>` to validate the OWL and inspect the layout.
The preview does not launch the graphical client, so it cannot establish live
rendering, keyboard/touch input, application compatibility or process cleanup.
Ask the user to check those on the phone, including stop/restart, focus loss,
orientation and keyboard resizing, before describing the face as working.

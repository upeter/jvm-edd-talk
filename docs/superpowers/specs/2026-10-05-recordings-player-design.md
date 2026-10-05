# Recordings player for the live talk

Date: 2026-10-05

## Problem

The talk switches between Keynote and screen recordings in `recordings/`. Today each recording is
opened in QuickTime Player fullscreen, and moving to the next clip means leaving fullscreen, closing
the document and opening the next file. That transition looks clumsy on stage.

Wanted: a list of clips that stays fullscreen the whole time, with QuickTime-like controls plus a
key combination to jump to the next or previous clip.

## Decision

Use mpv (already installed, v0.40.0 at `/usr/local/bin/mpv`) with a project-local configuration.
QuickTime cannot do this without a visible exit-fullscreen animation; VLC is less flexible for the
scroll-wheel and speed behavior. mpv's playlist stays fullscreen between files and every key is
rebindable.

## Components

All files live in `recordings/`. Nothing outside the repo is modified.

| File | Purpose |
|---|---|
| `playlist.m3u` | One filename per line, in talk order. Hand-edited. Initially the `Nr 0x` series. |
| `mpv/mpv.conf` | Player options (fullscreen, start paused, keep last frame, screenshot location, OSD settings). |
| `mpv/input.conf` | Key bindings, replacing mpv defaults for the keys listed below. |
| `play.sh` | Launcher: `cd` to `recordings/`, run `mpv --config-dir=mpv --playlist=playlist.m3u`. Optional first argument: clip number to start at (1-based), passed as `--playlist-start`. |

`--config-dir` makes the config self-contained, so the user's global `~/.config/mpv` is never read
or changed.

## Behavior

| Key / input | Action |
|---|---|
| Space | Toggle play / pause. |
| Right arrow | Speed +0.5x (`add speed 0.5`). Speed shown on screen briefly. |
| Left arrow | Speed −0.5x, floor 0.5x. |
| Backspace | Reset speed to 1x. |
| Scroll wheel | Scrub: each tick seeks ±1 second with `exact`, so the frame updates live, like QuickTime scrubbing. Step tunable in `input.conf`. |
| Ctrl+Right / Ctrl+Left | Next / previous clip. The new clip loads paused at 1x speed. No filename overlay. |
| `t` | Save a full-resolution screenshot of the current frame and open it in Preview, where macOS Live Text allows selecting text in the frame. Optional feature, kept only if the switch looks acceptable on stage. |
| `q` | Quit. |
| Escape | Unbound. Fullscreen cannot be left accidentally. |

Startup: fullscreen, first clip paused on its first frame.

End of a clip: freeze on the last frame (`keep-open=yes`), never auto-advance.

Other mpv defaults that are not listed above stay as they are unless they conflict (for example
the default Right/Left seek bindings and the default scroll-wheel bindings are overridden).

## Implementation notes

- "Load paused" for next/previous: bind to `playlist-next ; set pause yes ; set speed 1`. The
  `pause` property persists across file changes, so the new file starts paused. Verify this during
  testing; if the next file starts playing, add a tiny Lua script that sets `pause` on
  `file-loaded`.
- Scrubbing: `WHEEL_UP seek 1 exact` and `WHEEL_DOWN seek -1 exact`. Exact seeks decode to the
  requested frame so the picture updates while paused.
- Screenshot to Preview: mpv's `screenshot-to-file` with a fixed path in the scratch or a
  `recordings/.screenshots/` directory (git-ignored), followed by `run open -a Preview <path>`.
- Speed display: `osd-msg` or `show-text "${speed}x"` after each speed change.

## Out of scope

No GUI, thumbnails or clip picker. No auto-discovery of files. No hotkey to switch between
Keynote and the player. No drawing or spotlight tools.

## Testing

Manual, before the spec is considered done:

1. `./play.sh` opens fullscreen, paused, on the first clip of `playlist.m3u`.
2. Each binding in the table behaves as described, checked against mpv's on-screen display.
3. Ctrl+Right loads the next clip paused at 1x; Ctrl+Left goes back.
4. The last frame stays on screen when a clip ends.
5. `t` opens Preview with the frame and Live Text works; cmd+W returns to mpv fullscreen.
6. `./play.sh 3` starts at the third clip.

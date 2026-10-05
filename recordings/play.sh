#!/usr/bin/env bash
# Plays playlist.m3u fullscreen with the talk key bindings. Optional argument: clip number to start at (1-based).
set -euo pipefail
cd "$(dirname "$0")"

args=(--config-dir=mpv --playlist=playlist.m3u)
if [[ $# -ge 1 ]]; then
    [[ "$1" =~ ^[1-9][0-9]*$ ]] || { echo "usage: $0 [clip-number]" >&2; exit 1; }
    args+=(--playlist-start=$(( $1 - 1 )))
fi
exec mpv "${args[@]}"

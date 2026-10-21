#!/usr/bin/env bash
# Put a folder of photos on the TV for the Photos screensaver (PhotoDream, prints with the month written under
# each). Replaces the photos already there.
# Each photo is converted to a JPEG no larger than 1280 px on its long edge first (sips, macOS), so HEIC
# from an iPhone works and the TV decodes a 200 KB file instead of a 5 MB one; a print is about 400 px on the
# screen, so nothing shows the difference. Nothing is uploaded anywhere else: the files go straight to the
# launcher's own folder on the TV.
#
# Usage: TV_HOST=192.168.1.50 tools/photos.sh ~/Pictures/TV
#        TV_HOST=192.168.1.50 tools/photos.sh --clear       # remove them; the screensaver shows the clock
# Then pick it: Settings > Screen saver > Photos, or
#        tools/tv.py 'settings put secure screensaver_components com.gathin.tvhome/.PhotoDream'
set -euo pipefail
SRC="${1:-}"
case "$SRC" in --clear|"") ;; /*) ;; *) SRC="$(pwd)/$SRC" ;; esac
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0 ~/Pictures/TV}"
PY=venv/bin/python
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
DIR=/sdcard/Android/data/com.gathin.tvhome/files/photos
tv() { "$PY" -I tv.py "$@"; }

if [ "$SRC" = --clear ]; then
  tv "rm -rf $DIR; echo cleared"
  exit 0
fi
[ -d "${SRC:?usage: photos.sh <folder> | --clear}" ] || { echo "not a folder: $SRC" >&2; exit 1; }
command -v sips >/dev/null || { echo "sips not found: this script converts photos with macOS's sips" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
N=0
while IFS= read -r -d '' FILE; do
  N=$((N + 1))
  # The day the photo was taken, carried in the name since the TV only sees the converted copy: the screensaver
  # writes the month and year under it, and Home's "On this day" matches the day. From a date in the file name
  # (20231202_092230.jpg, IMG-20260110-WA0025.jpg), else from when the camera says it was taken. None found: no
  # date in the name, the strip stays blank and the photo is never an "On this day".
  YMD=""
  if [[ "$(basename "$FILE")" =~ (20[0-9]{2})(0[1-9]|1[0-2])([0-3][0-9]) ]]; then
    YMD="-${BASH_REMATCH[1]}${BASH_REMATCH[2]}${BASH_REMATCH[3]}"
  else
    TAKEN="$(sips -g creation "$FILE" 2>/dev/null | awk '/creation:/{print $2}')"
    if [[ "$TAKEN" =~ ^([0-9]{4}):([0-9]{2}):([0-9]{2}) ]]; then
      YMD="-${BASH_REMATCH[1]}${BASH_REMATCH[2]}${BASH_REMATCH[3]}"
    fi
  fi
  OUT="$WORK/$(printf 'photo-%04d' "$N")$YMD.jpg"
  # -Z keeps the aspect ratio; the EXIF orientation tag is kept, and PhotoDream turns the photo upright.
  if ! sips -s format jpeg -s formatOptions 85 -Z 1280 "$FILE" --out "$OUT" >/dev/null 2>&1; then
    echo "skipped (not a photo sips can read): $FILE" >&2
    N=$((N - 1))
  fi
done < <(find "$SRC" -maxdepth 1 -type f \( -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.png' \
  -o -iname '*.heic' -o -iname '*.webp' \) -print0 | sort -z)
[ "$N" -gt 0 ] || { echo "no photos (jpg, jpeg, png, heic, webp) in $SRC" >&2; exit 1; }

tv "rm -rf $DIR; mkdir -p $DIR" >/dev/null
I=0
for OUT in "$WORK"/photo-*.jpg; do
  I=$((I + 1))
  printf '\r%d/%d %s' "$I" "$N" "$(basename "$OUT")"
  "$PY" -I b64push.py "$OUT" "$DIR/$(basename "$OUT")" >/dev/null
done
echo
echo "$N photos on the TV. The screensaver picks them up the next time it starts."

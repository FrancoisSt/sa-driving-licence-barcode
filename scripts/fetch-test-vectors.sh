#!/usr/bin/env bash
# Fetches the six public encrypted card-back vectors (DanieLeeuwner/Reply.Net.SADL, MIT) into third_party/, which
# is git-ignored. They hold what look like real people's details: use them in tests only, never commit them, never
# bundle them in an app, and never print their decoded values.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
dest="$root/third_party/Reply.Net.SADL"
commit=292402a9d437fbb44568dd5076eb50cc86040245
if [ ! -d "$dest/.git" ]; then
  git clone --quiet https://github.com/DanieLeeuwner/Reply.Net.SADL.git "$dest"
fi
git -C "$dest" checkout --quiet "$commit"
echo "Test vectors at $dest ($commit)"

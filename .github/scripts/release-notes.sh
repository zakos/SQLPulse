#!/usr/bin/env bash
# Prints Markdown release notes: the commit subjects since the previous stable tag (v*), merge
# commits left out. Usage: release-notes.sh [exclude-tag]
# The excluded tag is the one being created, so a stable release lists what changed since the
# stable release before it rather than since itself.
set -euo pipefail

exclude="${1:-}"
prev=""
for tag in $(git tag --list 'v*' --sort=-v:refname); do
  if [ "$tag" != "$exclude" ]; then prev="$tag"; break; fi
done

if [ -n "$prev" ]; then
  range="$prev..HEAD"
  echo "Changes since $prev:"
else
  range="HEAD"
  echo "Changes:"
fi
echo
git log --no-merges --pretty='- %s (%h)' "$range" | head -n 200

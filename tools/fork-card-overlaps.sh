#!/usr/bin/env bash
#
# fork-card-overlaps.sh — which cards did this fork write that upstream has now ALSO written?
#
#   tools/fork-card-overlaps.sh [upstream-ref]      (default: upstream/master; fetch first)
#
# Prints one line per overlap: "<CardClass>\t<our card file>\t<how upstream has it>", nothing if
# none. Always exits 0; callers branch on the output.
#
# Fork-written cards are found automatically: card classes under Mage.Sets/src/mage/cards that
# exist on this branch but not on upstream at the merge base. An overlap is upstream gaining
# the same file, or a card with the same name in any of its set files (in case it named the
# class differently).
#
# Policy (owner, 2026-11-17): when upstream lands its own version of one of our cards, compare
# the two and take upstream's unless ours is clearly better. See tools/take-upstream-card.sh.
set -uo pipefail
cd "$(dirname "$0")/.."
UP=${1:-upstream/master}

mb=$(git merge-base HEAD "$UP") || { echo "fork-card-overlaps: no merge base with $UP" >&2; exit 0; }

git diff --name-only --diff-filter=A "$mb" HEAD -- 'Mage.Sets/src/mage/cards/*.java' | while read -r f; do
  cls=$(basename "$f" .java)
  if git cat-file -e "$UP:$f" 2>/dev/null; then
    printf '%s\t%s\t%s\n' "$cls" "$f" "same file"
    continue
  fi
  # same card under a different class name: match our SetCardInfo name in upstream's set files
  name=$(grep -h -oE "SetCardInfo\(\"[^\"]+\", [^,]+, [^,]+, mage\.cards\.[a-z]\.${cls}\.class" Mage.Sets/src/mage/sets/*.java \
         | head -1 | sed -E 's/^SetCardInfo\("([^"]+)".*/\1/')
  [ -n "$name" ] || continue
  hit=$(git grep -h -oE "SetCardInfo\(\"$(printf '%s' "$name" | sed 's/[.[\*^$/]/\\&/g')\", [^,]+, [^,]+, mage\.cards\.[a-z]\.[A-Za-z0-9]+\.class" "$UP" -- 'Mage.Sets/src/mage/sets/*.java' 2>/dev/null \
        | head -1 | grep -oE 'mage\.cards\.[a-z]\.[A-Za-z0-9]+' )
  [ -n "$hit" ] && printf '%s\t%s\t%s\n' "$cls" "$f" "as $hit"
done
exit 0

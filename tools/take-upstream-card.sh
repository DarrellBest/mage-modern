#!/usr/bin/env bash
#
# take-upstream-card.sh — drop the fork's version of a card so upstream's comes in cleanly.
#
#   tools/take-upstream-card.sh <CardClass> [<CardClass> ...]
#
# Run it BEFORE merging upstream, once you've compared the two versions and decided upstream's
# is as good or better (the default, per the owner's policy in tools/fork-card-overlaps.sh).
# For each card it removes, in one commit:
#   - our card class (Mage.Sets/src/mage/cards/<x>/<CardClass>.java)
#   - our SetCardInfo lines for it in the set files (upstream's merge brings its own)
#   - our test, only if upstream ships a test file at the same path (otherwise ours is KEPT,
#     so after the merge it runs against upstream's implementation, a free check of theirs)
#
# Compare first:
#   git diff HEAD upstream/master -- Mage.Sets/src/mage/cards/<x>/<CardClass>.java
set -euo pipefail
cd "$(dirname "$0")/.."
UP=${UP:-upstream/master}
[ $# -gt 0 ] || { sed -n '3,4p' "$0"; exit 2; }
[ -z "$(git status --porcelain)" ] || { git status -s; echo "working tree not clean" >&2; exit 1; }

done_list=""
for cls in "$@"; do
  f=$(ls Mage.Sets/src/mage/cards/?/"$cls".java 2>/dev/null | head -1)
  [ -n "$f" ] || { echo "no card class $cls" >&2; exit 1; }
  pkg=$(basename "$(dirname "$f")")
  git rm -q "$f"
  # our set-file lines for this class
  for s in $(grep -l "mage\.cards\.${pkg}\.${cls}\.class" Mage.Sets/src/mage/sets/*.java 2>/dev/null); do
    sed -i "/mage\.cards\.${pkg}\.${cls}\.class/d" "$s"; git add "$s"
  done
  # our test, only when upstream has one at the same path
  for t in $(git ls-files "Mage.Tests/src/test/java/*/${cls}Test.java"); do
    if git cat-file -e "$UP:$t" 2>/dev/null; then git rm -q "$t"; echo "  $cls: upstream has its own test, dropped ours"
    else echo "  $cls: kept our test $t (will run against upstream's card after the merge)"; fi
  done
  done_list="$done_list $cls"
done
git commit -q -m "fork cards: take upstream's version of${done_list}"
echo "committed: dropped fork version of${done_list}. Now merge upstream and run the kept tests."

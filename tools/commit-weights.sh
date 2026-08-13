#!/usr/bin/env bash
# Commit the learner's global model if it changed. Run on a timer.
#
# The learner merges its delta at the END OF EVERY GAME (FederatedWeights.merge, under an OS
# file lock), so the file on disk is always current. This script only handles DURABILITY --
# getting that state into git history so it survives the machine.
#
# Deliberately NOT once per game: that would be a commit every few minutes, burying every real
# change in the repo. Hourly keeps at most an hour of learning at risk, against a file that is
# rewritten continuously anyway.
#
# Safety: commits ONLY ai-weights/, never a stray edit elsewhere in the tree, and never
# switches branch. If the working tree has other staged changes it leaves them alone.
set -uo pipefail
REPO=/home/user/projects/mage-modern
W=ai-weights/commander-weights.txt

cd "$REPO" || exit 1
[ -f "$W" ] || { echo "$(date '+%F %T') no weights file yet"; exit 0; }

# nothing to do if the model has not moved
if git diff --quiet -- "$W" 2>/dev/null; then
  exit 0
fi

version=$(grep -m1 '^version=' "$W" | cut -d= -f2 | tr -d ' ')
games=${version:-unknown}

git add -- "$W"
# --only: commit just this path, leaving anything else staged untouched
# git options MUST come before the "--" pathspec separator; with them after, git treats
# -q, -m and the message itself as pathspecs and the commit fails with
# "error: pathspec '-q' did not match any file(s)". It failed that way every hour, silently,
# because cron output went to a log nobody reads.
git commit -q -m "ai-weights: learner model at version ${games}

Global model for Computer - learner, merged at the end of every game it plays
(FedAvg on deltas, damped by checkout staleness). version= is the number of games
that have contributed, and is the honest measure of how much training this has had.

Committed by tools/commit-weights.sh on a timer -- the file itself updates per game." \
  -- "$W" \
  && echo "$(date '+%F %T') committed weights at version ${games}"

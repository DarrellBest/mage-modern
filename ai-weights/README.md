# Commander bot learned weights

`commander-weights.txt` is the **global model** for `Computer - learner`
(`ComputerPlayerLearner`). It lives in the repo, and is committed, so learning
persists across server restarts, redeploys and machine moves — a redeploy replaces
every jar, and anything kept only under `mage-server/` would be lost with it.

## How it updates

Each game checks the model out at its current version, learns privately during play
(`OnlineTDLearner`, TD(lambda)), and merges its delta back **when the game ends** —
FedAvg, weighted by how much that game actually learned, damped by how stale the
checkout was. The merge takes an OS file lock, so several games finishing at once
cannot lose each other's learning.

`version=` increments on every merge. It is the count of games that have contributed,
and it is the honest measure of how much training this model has had.

## Cold start is deliberate

With no file, or with a zero weight vector, `learnedWeight()` is 0 and the learner
blends to **pure hand-tuned evaluation** — it plays exactly like `Computer - commander`
rather than randomly. So a missing or fresh file degrades to the tuned bot, never to
a worse one.

## Do not hand-edit

Keys are feature names from `StateFeatures.NAMES`. Loading maps by NAME, so appending
a feature is safe and an old file still lines up. Two things are NOT safe:

- Renaming or reordering `StateFeatures.NAMES` — old files then map onto the wrong
  features silently.
- Appending to `NAMES` without appending to `OnlineTDLearner.SCALE`. That threw
  `ArrayIndexOutOfBoundsException` 61 times in three games while the surrounding code
  swallowed it, so the learner appeared to run and trained nothing. There is now a
  static check that fails loudly instead.

Weights written before 2026-08-13 were produced while the feature extractor was
throwing on every call and are meaningless.

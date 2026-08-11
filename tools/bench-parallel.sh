#!/usr/bin/env bash
#
# bench-parallel.sh — run Mage.Bench across N worker JVMs and print ONE pooled summary.
#
#   ./tools/bench-parallel.sh --games=1000 --workers=12 \
#       --playerA=cp7 --playerB=commander --gameType=commander --turnCap=50 \
#       --deckDir=. --deckA="benchdecks/Krenko-R-EDH.dck" --deckB="benchdecks/Krenko-R-EDH.dck" \
#       --out=/abs/path/run.jsonl
#
# Anything this script does not recognise is forwarded verbatim to BenchRunner, so
# new BenchConfig options (--paramsA/--paramsB for the evaluator sweep, --trackCards,
# --model, --skill, ...) work here with no change to this file. A typo still fails
# loudly: BenchRunner rejects unknown arguments, and it does so in every worker at once,
# within seconds of launch.
#
# WHY SEPARATE PROCESSES, never threads and never `mvn exec:java`:
#   The engine's ThreadUtils.ensureRunInGameThread() allowlists a thread literally named
#   "main". Run games on any other thread and they do not fail — they silently CORRUPT:
#   spurious winners, games ending after a handful of turns. Every worker here is its own
#   `java ... mage.bench.BenchRunner` process, so every game runs on a real "main" thread.
#   RandomUtil is also one process-wide static Random, so in-JVM parallel games would
#   additionally share one random stream. Do not "optimise" this into a thread pool.
#
# WHY cwd IS Mage.Tests:
#   The card database URL is jdbc:h2:file:./db/cards.h2 (DatabaseUtils) — resolved against
#   the CURRENT WORKING DIRECTORY. Mage.Tests is where the built card DB, cp.txt and the
#   benchdecks/ live, so every worker runs from there and --deckDir=. means Mage.Tests.
#   All workers therefore share one card DB file; that is fine and deliberate — the URL
#   carries AUTO_SERVER=TRUE, so the first JVM opens it embedded and starts a server the
#   rest connect to as clients. Workers are launched with a small stagger (--stagger)
#   because that election is a race when a dozen JVMs open the file in the same instant.
#
# SEED SPLIT:
#   BenchRunner uses seed = baseSeed + i for its i-th game, and swaps seats on odd i.
#   Worker w is given --seed=(baseSeed + offset_w) --games=chunk_w with offsets running
#   cumulatively from 0, so the workers cover [baseSeed, baseSeed+games) exactly once:
#   disjoint ranges, no worker replaying another's games. Chunks are allocated in PAIRS so
#   every offset is even, which keeps each worker's local odd/even seat-swap parity equal
#   to the global one — the pooled set of games is then the same set a single serial run of
#   --games would have played, seat assignments included. Only the last worker can get an
#   odd chunk (the leftover game when --games is odd), which disturbs no other offset.
#
# POOLING:
#   The merged report is computed by mage.bench.MergeReporter over the concatenated RAW
#   games, reusing SummaryReporter's Wilson interval rather than reimplementing it. Never
#   averaged across per-worker win rates — workers finish different numbers of games (game
#   length varies 10s-149s, and a killed worker contributes a short shard), so an average of
#   rates would silently weight a 3-game worker like a 200-game one.
#
set -euo pipefail

INVOKE_PWD="$PWD"                              # captured before the cd: a relative --out
cd "$(dirname "$0")/.."                        # repo root   is resolved against it, not Mage.Tests
REPO_DIR="$(pwd)"
WORK_DIR="$REPO_DIR/Mage.Tests"                # see "WHY cwd IS Mage.Tests" above
BENCH_LOG4J="$REPO_DIR/Mage.Tests/src/test/resources/log4j-bench.properties"

say(){ printf '\n\033[1m==> %s\033[0m\n' "$*"; }
warn(){ printf '\033[33mWARNING: %s\033[0m\n' "$*" >&2; }
die(){ printf '\033[31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

usage(){
  cat <<'EOF'
bench-parallel.sh — run Mage.Bench across N worker JVMs and print ONE pooled summary.

  ./tools/bench-parallel.sh --games=1000 --workers=12 \
      --playerA=cp7 --playerB=commander --gameType=commander --turnCap=50 \
      --deckDir=. --deckA="benchdecks/Krenko-R-EDH.dck" --deckB="benchdecks/Krenko-R-EDH.dck" \
      --out=/abs/path/run.jsonl

Workers run from Mage.Tests on disjoint seed ranges, each in its own JVM (required:
the engine only runs games correctly on a thread named "main"). Read the comment
block at the top of this file before changing any of that.

Useful BenchRunner options (forwarded verbatim, not interpreted here):

  --maxGameSeconds=N   cut a game off at N seconds and record it as TIMEOUT. Bounds
                       games in TIME, which --turnCap does not. Strongly recommended
                       for sweeps. It only fires at a turn boundary, so keep
                       --timeoutPerGame as the outer backstop as well.

Options owned by this script (everything else goes to BenchRunner):

  --games=N            total games across all workers            (required)
  --workers=N          worker JVMs to run concurrently           (default 12)
  --seed=N             base seed; workers split it into disjoint ranges (default 12345)
  --out=PATH           merged .jsonl; parts land in PATH.parts/  (default bench-parallel-<ts>.jsonl)
  --logs=MODE          bench (default) | default | /path/to/log4j.properties
  --heap=SIZE          -Xmx per worker, e.g. 4g; "default" to pass none  (default 4g)
  --timeoutPerGame=SEC per-worker timeout = SEC x its chunk; 0 disables  (default 300)
  --stagger=SEC        delay between worker launches                     (default 3)
  --progress=SEC       progress line interval; 0 disables                (default 60)
  --dryRun             print the worker/seed split and exit, launching nothing
  -h | --help          this text
EOF
}

# ------------------------------------------------------------------ arguments
GAMES=""; WORKERS=12; BASE_SEED=12345; OUT=""; LOGS="bench"; HEAP="4g"
TIMEOUT_PER_GAME=300; STAGGER=3; PROGRESS=60; DRY_RUN=0
PLAYER_A="playerA"; PLAYER_B="playerB"
PASSTHRU=()

for arg in "$@"; do
  case "$arg" in
    -h|--help) usage; exit 0 ;;
    --dryRun)  DRY_RUN=1; continue ;;
  esac
  [[ "$arg" == --*=* ]] || die "bad argument '$arg' — expected --name=value (see --help)"
  key="${arg%%=*}"; key="${key#--}"; value="${arg#*=}"
  case "$key" in
    games)            GAMES="$value" ;;
    workers)          WORKERS="$value" ;;
    seed)             BASE_SEED="$value" ;;
    out)              OUT="$value" ;;
    logs)             LOGS="$value" ;;
    heap)             HEAP="$value" ;;
    timeoutPerGame)   TIMEOUT_PER_GAME="$value" ;;
    stagger)          STAGGER="$value" ;;
    progress)         PROGRESS="$value" ;;
    # captured for the merged report's labels AND still forwarded to BenchRunner
    playerA)          PLAYER_A="$value"; PASSTHRU+=("$arg") ;;
    playerB)          PLAYER_B="$value"; PASSTHRU+=("$arg") ;;
    *)                PASSTHRU+=("$arg") ;;
  esac
done

[ -n "$GAMES" ] || die "--games is required (see --help)"
[[ "$GAMES"   =~ ^[0-9]+$ ]] || die "--games must be a non-negative integer, got '$GAMES'"
[[ "$WORKERS" =~ ^[0-9]+$ ]] || die "--workers must be a non-negative integer, got '$WORKERS'"
[[ "$BASE_SEED" =~ ^-?[0-9]+$ ]] || die "--seed must be an integer, got '$BASE_SEED'"
[ "$GAMES"   -gt 0 ] || die "--games must be at least 1"
[ "$WORKERS" -gt 0 ] || die "--workers must be at least 1"

# --out is resolved against the directory the USER ran this from, before the cd below,
# so a relative --out does not silently land inside Mage.Tests.
[ -n "$OUT" ] || OUT="bench-parallel-$(date +%Y%m%d-%H%M%S).jsonl"
case "$OUT" in
  /*) ;;
   *) OUT="$INVOKE_PWD/$OUT" ;;
esac
OUT_DIR="$(dirname "$OUT")"
[ -d "$OUT_DIR" ] || die "--out directory does not exist: $OUT_DIR"
[ -w "$OUT_DIR" ] || die "--out directory is not writable: $OUT_DIR"
# Refuse to reuse an output: ResultWriter opens for APPEND by design (a long run must survive
# a crash), so re-running onto an existing file would fold an old run's games into this run's
# pooled statistics — a silently wrong confidence interval, the exact failure this tool exists
# to prevent.
if [ -e "$OUT" ]; then
  die "--out already exists, refusing to overwrite or append: $OUT"
fi
PARTS="$OUT.parts"
if [ -e "$PARTS" ]; then
  die "worker part directory already exists (leftover from an earlier run?): $PARTS"
fi

# ------------------------------------------------------------------ preflight
[ -d "$WORK_DIR" ] || die "expected the bench working directory at $WORK_DIR"
cd "$WORK_DIR"
[ -f cp.txt ] || die "$WORK_DIR/cp.txt is missing — regenerate it with:
    mvn -pl Mage.Tests dependency:build-classpath -Dmdep.outputFile=cp.txt"
CP="target/classes:target/test-classes:$(cat cp.txt)"

# MergeReporter is newer than the rest of Mage.Bench; a stale installed mage-bench jar on
# cp.txt would let all the workers run for an hour and only then fail at the merge. Probed
# by capturing output rather than piping into grep, because `set -o pipefail` makes the exit
# status of `java ... | grep -q` that of the (always non-zero) java, not of the grep.
CLASS_PROBE="$(java -cp "$CP" mage.bench.MergeReporter 2>&1 || true)"
case "$CLASS_PROBE" in
  *"Could not find or load main class"*|*NoClassDefFoundError*)
    die "mage.bench.MergeReporter is not on the classpath — rebuild the module with:
    mvn -o -pl Mage.Bench -DskipTests install" ;;
esac

case "$LOGS" in
  bench)   [ -f "$BENCH_LOG4J" ] || die "missing $BENCH_LOG4J"
           JVM_LOG_FLAG="-Dlog4j.configuration=file:$BENCH_LOG4J" ;;
  default) JVM_LOG_FLAG="" ;;
  *)       [ -f "$LOGS" ] || die "--logs must be 'bench', 'default', or a readable file; got '$LOGS'"
           JVM_LOG_FLAG="-Dlog4j.configuration=file:$(cd "$(dirname "$LOGS")" && pwd)/$(basename "$LOGS")" ;;
esac

JVM_HEAP_FLAG=""
# 1 GB was measured to OOM ("GC overhead limit exceeded") on AI Commander games and the live
# server runs -Xmx8192m; 4g is the compromise that keeps a dozen concurrent workers from
# each claiming a quarter of system RAM as their default max heap.
[ "$HEAP" = "default" ] || JVM_HEAP_FLAG="-Xmx$HEAP"

CORES="$(nproc 2>/dev/null || echo 0)"
if [ "$CORES" -gt 0 ] && [ "$WORKERS" -gt 12 ]; then
  warn "--workers=$WORKERS on a ${CORES}-core machine: one BenchRunner JVM was measured at
         ~1.5 cores, and this box also runs the XMage server and Ollama. 12 is the tested ceiling."
fi

# ------------------------------------------------------------------ split the games
# Allocate in pairs so every offset stays even (see "SEED SPLIT" in the header).
declare -a CHUNK
pairs=$(( GAMES / 2 )); leftover=$(( GAMES % 2 ))
base_pairs=$(( pairs / WORKERS )); extra_pairs=$(( pairs % WORKERS ))
last_nonzero=-1
for (( w = 0; w < WORKERS; w++ )); do
  p=$base_pairs
  [ "$w" -lt "$extra_pairs" ] && p=$(( p + 1 ))
  CHUNK[$w]=$(( p * 2 ))
  [ "${CHUNK[$w]}" -gt 0 ] && last_nonzero=$w
done
if [ "$leftover" -eq 1 ]; then
  if [ "$last_nonzero" -ge 0 ]; then
    CHUNK[$last_nonzero]=$(( CHUNK[last_nonzero] + 1 ))
  else
    CHUNK[0]=1; last_nonzero=0                      # --games=1
  fi
fi
ACTIVE=$(( last_nonzero + 1 ))
if [ "$ACTIVE" -lt "$WORKERS" ]; then
  warn "--games=$GAMES over --workers=$WORKERS leaves some workers with nothing to do; running $ACTIVE."
fi

say "Plan: $GAMES games over $ACTIVE worker JVM(s), base seed $BASE_SEED"
offset=0
declare -a SEED_OF CHUNK_OF
for (( w = 0; w < ACTIVE; w++ )); do
  SEED_OF[$w]=$(( BASE_SEED + offset ))
  CHUNK_OF[$w]=${CHUNK[$w]}
  printf '    worker %02d  %5d games  seeds %d..%d\n' \
      "$(( w + 1 ))" "${CHUNK_OF[$w]}" "${SEED_OF[$w]}" "$(( SEED_OF[w] + CHUNK_OF[w] - 1 ))"
  offset=$(( offset + CHUNK[w] ))
done
[ "$offset" -eq "$GAMES" ] || die "internal error: split $offset games, expected $GAMES"
echo "    logs: $LOGS   heap: $HEAP   parts: $PARTS"

if [ "$DRY_RUN" -eq 1 ]; then
  say "--dryRun: plan validated, nothing launched"
  exit 0
fi

mkdir -p "$PARTS"

# ------------------------------------------------------------------ launch
declare -a PIDS
for (( w = 0; w < ACTIVE; w++ )); do
  n=$(printf '%02d' "$(( w + 1 ))")
  part="$PARTS/worker-$n.jsonl"
  log="$PARTS/worker-$n.log"
  cmd=(java)
  [ -n "$JVM_HEAP_FLAG" ] && cmd+=("$JVM_HEAP_FLAG")
  [ -n "$JVM_LOG_FLAG" ]  && cmd+=("$JVM_LOG_FLAG")
  cmd+=(-cp "$CP" mage.bench.BenchRunner
        "--games=${CHUNK_OF[$w]}" "--seed=${SEED_OF[$w]}" "--out=$part"
        "${PASSTHRU[@]+"${PASSTHRU[@]}"}")

  # A worker killed by this timeout KEEPS the games it already finished — ResultWriter
  # flushes every append — and the shortfall is reported loudly at the end rather than
  # quietly shrinking the sample.
  if [ "$TIMEOUT_PER_GAME" -gt 0 ]; then
    budget=$(( TIMEOUT_PER_GAME * CHUNK_OF[w] ))
    run=(timeout "$budget" "${cmd[@]}")
  else
    run=("${cmd[@]}")
  fi

  # Header the log with a copy-pasteable version of this worker's command, with the
  # expanded classpath folded back into $(cat cp.txt). Verbatim it is ~12KB on one line,
  # which makes `tail` of a failing worker's log unreadable at precisely the moment
  # someone is trying to read it.
  disp=()
  for token in "${run[@]}"; do
    if [ "$token" = "$CP" ]; then
      disp+=('target/classes:target/test-classes:$(cat cp.txt)')
    else
      disp+=("$token")
    fi
  done
  { printf '# worker %s: %s games from seed %s   (run from %s)\n' \
        "$n" "${CHUNK_OF[$w]}" "${SEED_OF[$w]}" "$WORK_DIR"
    printf '# %s\n\n' "${disp[*]}"; } > "$log"
  "${run[@]}" >> "$log" 2>&1 &
  PIDS[$w]=$!
  [ "$w" -lt "$(( ACTIVE - 1 ))" ] && sleep "$STAGGER"
done
say "Launched $ACTIVE worker(s); per-worker log/jsonl under $PARTS"

# ------------------------------------------------------------------ wait, with progress
progress_loop(){
  while :; do
    sleep "$PROGRESS"
    # `|| true`: the glob matches nothing until the first worker file appears, and under
    # `set -o pipefail` that failing cat would otherwise kill this background subshell.
    done_games=$(cat "$PARTS"/worker-*.jsonl 2>/dev/null | wc -l || true)
    printf '    [%s] %d/%d games done\n' "$(date +%H:%M:%S)" "$done_games" "$GAMES"
  done
}
PROGRESS_PID=""
if [ "$PROGRESS" -gt 0 ]; then
  progress_loop &
  PROGRESS_PID=$!
fi
cleanup(){ [ -n "$PROGRESS_PID" ] && kill "$PROGRESS_PID" 2>/dev/null; return 0; }
trap cleanup EXIT

declare -a RC
for (( w = 0; w < ACTIVE; w++ )); do
  rc=0
  wait "${PIDS[$w]}" || rc=$?
  RC[$w]=$rc
done
cleanup; PROGRESS_PID=""

# ------------------------------------------------------------------ verify every worker
FAILURES=()
for (( w = 0; w < ACTIVE; w++ )); do
  n=$(printf '%02d' "$(( w + 1 ))")
  part="$PARTS/worker-$n.jsonl"
  got=0
  [ -f "$part" ] && got=$(wc -l < "$part")
  if [ "${RC[$w]}" -eq 124 ]; then
    FAILURES+=("worker $n TIMED OUT after $(( TIMEOUT_PER_GAME * CHUNK_OF[w] ))s with $got/${CHUNK_OF[$w]} games")
  elif [ "${RC[$w]}" -ne 0 ]; then
    FAILURES+=("worker $n EXITED $((RC[w])) with $got/${CHUNK_OF[$w]} games")
  elif [ "$got" -ne "${CHUNK_OF[$w]}" ]; then
    FAILURES+=("worker $n exited 0 but wrote $got/${CHUNK_OF[$w]} games")
  fi
done

# ------------------------------------------------------------------ merge + pooled summary
# Only the files that actually exist: a worker that died before writing its first game
# leaves none, and handing MergeReporter an unmatched glob would bury that real failure
# under a confusing "part file missing: .../worker-*.jsonl". The per-worker check above
# has already recorded those workers, and --expected still catches the short sample.
PART_FILES=()
for f in "$PARTS"/worker-*.jsonl; do
  [ -f "$f" ] && PART_FILES+=("$f")
done

merge_rc=0
if [ "${#PART_FILES[@]}" -eq 0 ]; then
  merge_rc=1
  printf '\n\033[31mNo worker produced any results at all — nothing to merge.\033[0m\n' >&2
else
  say "Merging ${#PART_FILES[@]} part file(s)"
  java -cp "$CP" mage.bench.MergeReporter \
      "--playerA=$PLAYER_A" "--playerB=$PLAYER_B" "--expected=$GAMES" "--merged=$OUT" \
      "${PART_FILES[@]}" || merge_rc=$?
fi

if [ "${#FAILURES[@]}" -gt 0 ]; then
  printf '\n\033[31m=== %d WORKER FAILURE(S) — THE POOLED SAMPLE ABOVE IS INCOMPLETE ===\033[0m\n' \
      "${#FAILURES[@]}" >&2
  for f in "${FAILURES[@]}"; do printf '\033[31m  %s\033[0m\n' "$f" >&2; done
  printf '\033[31m  last lines of each failing worker log:\033[0m\n' >&2
  for (( w = 0; w < ACTIVE; w++ )); do
    n=$(printf '%02d' "$(( w + 1 ))")
    got=0; part="$PARTS/worker-$n.jsonl"; [ -f "$part" ] && got=$(wc -l < "$part")
    if [ "${RC[$w]}" -ne 0 ] || [ "$got" -ne "${CHUNK_OF[$w]}" ]; then
      printf '\033[31m  --- worker %s (%s) ---\033[0m\n' "$n" "$PARTS/worker-$n.log" >&2
      tail -n 5 "$PARTS/worker-$n.log" | cut -c1-200 | sed 's/^/      /' >&2
    fi
  done
  exit 1
fi

exit "$merge_rc"

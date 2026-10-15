#!/usr/bin/env bash
#
# Runs CompileLoop against every fixture with every model and writes one JSON
# per run under benchmarks/results/<model>/<fixture>.json. Model names are
# passed as arguments; without arguments, uses a default set of local Ollama
# models.
#
# Usage:
#   benchmarks/run.sh                              # default model set
#   benchmarks/run.sh qwen2.5-coder:7b             # one model
#   benchmarks/run.sh qwen2.5-coder:7b deepseek-coder:6.7b
#
set -u

cd "$(dirname "$0")/.."   # repo root

JAR="target/compileloop-0.1.0-SNAPSHOT.jar"
FIXTURES="benchmarks/fixtures"
RESULTS="benchmarks/results"
ATTEMPTS="${ATTEMPTS:-3}"
AI_TIMEOUT="${AI_TIMEOUT:-300}"

if [ ! -f "$JAR" ]; then
  echo "Building the jar first..."
  mvn -B -DskipTests package || exit 1
fi

if [ "$#" -eq 0 ]; then
  MODELS=("qwen2.5-coder:7b" "qwen2.5-coder:1.5b" "deepseek-coder:6.7b")
else
  MODELS=("$@")
fi

total_runs=$((${#MODELS[@]} * $(ls -d "$FIXTURES"/*/ | wc -l)))
run=0

echo "Running $total_runs configurations ($total_runs runs)"
echo "Models: ${MODELS[*]}"
echo

for model in "${MODELS[@]}"; do
  safe_model=$(echo "$model" | tr ':/' '__')
  outdir="$RESULTS/$safe_model"
  mkdir -p "$outdir"

  echo "===== $model ====="

  for fixture_dir in "$FIXTURES"/*/; do
    fixture=$(basename "$fixture_dir")
    run=$((run + 1))
    out="$outdir/$fixture.json"

    if [ -f "$out" ]; then
      echo "  [$run/$total_runs] $fixture (skipped, already done)"
      continue
    fi

    printf "  [%d/%d] %s ... " "$run" "$total_runs" "$fixture"
    start=$(date +%s)

    java -jar "$JAR" repair "$fixture_dir" \
      --model "$model" \
      --attempts "$ATTEMPTS" \
      --ai-timeout "$AI_TIMEOUT" \
      --json > "$out" 2>/dev/null

    exit_code=$?
    elapsed=$(( $(date +%s) - start ))

    # Extract verdict from the report for a quick line of feedback.
    verdict=$(grep -oE '"verdict": *"[A-Z_]+"' "$out" 2>/dev/null | head -1 | sed 's/.*"\([A-Z_]*\)"/\1/')
    if [ -z "$verdict" ]; then
      verdict="PARSE_ERROR"
    fi

    echo "$verdict (${elapsed}s, exit $exit_code)"
  done
  echo
done

echo "Done. Results in $RESULTS/"

#!/usr/bin/env bash
# Runs Dartagnan on every litmus test found under the given paths and prints one "path,result" line per test,
# sorted by path. The result is PASS, FAIL, UNKNOWN or ERROR (parse or other exception, see the log directory).
#
#   scripts/litmus-verdicts.sh <jar> <cat file> <log dir> <path>... [-- <extra Dartagnan options>]
#
# Environment: JOBS (parallel runs, default 8), TARGET (default ptx), PROPERTY (default program_spec).
# DAT3M_HOME must point to the Dat3M checkout.
set -euo pipefail

if [ $# -lt 4 ]; then
  sed -n '2,8p' "$0" >&2
  exit 2
fi
jar="$1"; cat="$2"; logs="$3"; shift 3
paths=()
while [ $# -gt 0 ] && [ "$1" != -- ]; do paths+=("$1"); shift; done
[ "${1:-}" = -- ] && shift
extra=("$@")
mkdir -p "$logs"

run_one() {
  local test="$1" log
  log="$logs/$(echo "$test" | tr '/' '_').log"
  DAT3M_OUTPUT="$logs/out" timeout 600 java -jar "$jar" "$cat" --target="${TARGET:-ptx}" --method=eager \
    --bound=1 --solver=z3 --property="${PROPERTY:-program_spec}" "${extra[@]}" "$test" > "$log" 2>&1 || true
  local result
  result="$(sed -n 's/^Result: \([A-Z]*\).*/\1/p' "$log" | tail -1)"
  echo "$test,${result:-ERROR}"
}
export -f run_one
export jar cat logs
export extra_str="${extra[*]:-}"

find "${paths[@]}" -name '*.litmus' | sort \
  | JOBS="${JOBS:-8}" xargs -P "${JOBS:-8}" -I{} bash -c 'extra=($extra_str); run_one "$1"' _ {} \
  | sort

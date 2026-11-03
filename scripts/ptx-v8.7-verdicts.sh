#!/usr/bin/env bash
# Decides every PTX litmus test found under the given paths with the PTX 8.7 model and prints one
# "path,verdict,program_spec,flags" line per test, sorted by path (see M16, M35 and M51 in cat/ptx-v8.7.md):
#   1. program_spec with ptx-v8.7.cat: PASS, FAIL, UNKNOWN or ERROR;
#   2. cat_spec with ptx-v8.7.cat: the model's flags in the witness, if any (separated by ';');
#   3. verdict: allowed if undefined-behaviour is flagged; otherwise, if mixed-size-data-race is flagged, the
#      program_spec result with ptx-v8.7-noaxioms.cat; otherwise the result of step 1.
#      PASS is allowed, FAIL forbidden, anything else unknown.
#
#   scripts/ptx-v8.7-verdicts.sh <jar> <log dir> <path>...
#
# Environment: JOBS (parallel runs, default 8). DAT3M_HOME must point to the Dat3M checkout.
set -euo pipefail

if [ $# -lt 3 ]; then
  sed -n '2,12p' "$0" >&2
  exit 2
fi
jar="$1"; logs="$2"; shift 2
mkdir -p "$logs"

run_dartagnan() {
  local cat="$1" property="$2" test="$3" log="$4"
  DAT3M_OUTPUT="$logs/out" timeout 600 java -jar "$jar" "$DAT3M_HOME/cat/$cat" --target=ptx --method=eager \
    --bound=1 --solver=z3 --property="$property" "$test" > "$log" 2>&1 || true
}

result_of() {
  local result
  result="$(sed -n 's/^Result: \([A-Z]*\).*/\1/p' "$1" | tail -1)"
  echo "${result:-ERROR}"
}

verdict_of() {
  case "$1" in
    PASS) echo allowed ;;
    FAIL) echo forbidden ;;
    *) echo unknown ;;
  esac
}

decide() {
  local test="$1" base program flags result
  base="$logs/$(echo "$test" | tr '/' '_')"
  run_dartagnan ptx-v8.7.cat program_spec "$test" "$base.program.log"
  program="$(result_of "$base.program.log")"
  run_dartagnan ptx-v8.7.cat cat_spec "$test" "$base.flags.log"
  # Only the flags of ptx-v8.7.cat (stdlib.cat's undefined-behavior flags a subset of undefined-behaviour)
  flags="$(sed -n 's/^[[:space:]]*Flag \(mixed-size-data-race\|undefined-behaviour\)$/\1/p' "$base.flags.log" \
    | sort -u | paste -sd ';' -)"
  if [ "$(result_of "$base.flags.log")" = ERROR ]; then
    result=ERROR
  elif [[ ";$flags;" == *";undefined-behaviour;"* ]]; then
    result=PASS
  elif [[ ";$flags;" == *";mixed-size-data-race;"* ]]; then
    run_dartagnan ptx-v8.7-noaxioms.cat program_spec "$test" "$base.noaxioms.log"
    result="$(result_of "$base.noaxioms.log")"
  else
    result="$program"
  fi
  echo "$test,$(verdict_of "$result"),$program,$flags"
}
export -f run_dartagnan result_of verdict_of decide
export jar logs

find "$@" -name '*.litmus' | sort | xargs -P "${JOBS:-8}" -I{} bash -c 'decide "$1"' _ {} | sort

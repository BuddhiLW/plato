#!/usr/bin/env bash
# Compile the plato CLI into one self-contained native binary with ClojureWasm.
#   scripts/build-cli.sh [output-path]
set -euo pipefail

out="${1:-dist/plato}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

command -v cljw >/dev/null 2>&1 || {
  echo "cljw (ClojureWasm) is not on PATH — see https://github.com/clojurewasm" >&2
  exit 1
}

mkdir -p "$(dirname "$root/$out")"
cd "$root"
cljw build -m plato.cli -o "$out" -cp src
echo "built $out ($(wc -c < "$out") bytes)"

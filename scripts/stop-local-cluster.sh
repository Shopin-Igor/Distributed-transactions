#!/usr/bin/env bash
set -euo pipefail

COORD_PORT=${COORD_PORT:-7000}
A_PORT=${A_PORT:-7101}
B_PORT=${B_PORT:-7102}
C_PORT=${C_PORT:-7103}

kill_pid() {
  local pid=$1
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    kill "$pid" || true
  fi
}

for pid_file in run/coordinator.pid run/shardA.pid run/shardB.pid run/shardC.pid; do
  if [[ -f "$pid_file" ]]; then
    kill_pid "$(cat "$pid_file")"
    rm -f "$pid_file"
  fi
done

if command -v lsof >/dev/null 2>&1; then
  for port in "$COORD_PORT" "$A_PORT" "$B_PORT" "$C_PORT"; do
    while IFS= read -r pid; do
      kill_pid "$pid"
    done < <(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)
  done
fi

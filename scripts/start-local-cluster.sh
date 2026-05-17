#!/usr/bin/env bash
set -euo pipefail

DATA_DIR=${DATA_DIR:-data}
COORD_PORT=${COORD_PORT:-7000}
A_PORT=${A_PORT:-7101}
B_PORT=${B_PORT:-7102}
C_PORT=${C_PORT:-7103}

mkdir -p "$DATA_DIR" run

port_busy() {
  local port=$1
  timeout 1 bash -c "</dev/tcp/127.0.0.1/$port" >/dev/null 2>&1
}

for port in "$COORD_PORT" "$A_PORT" "$B_PORT" "$C_PORT"; do
  if port_busy "$port"; then
    echo "port $port is already in use; stop the old cluster or choose another *_PORT" >&2
    echo "example: COORD_PORT=7200 A_PORT=7201 B_PORT=7202 C_PORT=7203 DATA_DIR=$DATA_DIR ./scripts/start-local-cluster.sh" >&2
    exit 1
  fi
done

make build

nohup java -cp out org.example.dtx.Main participant --id shardA --port "$A_PORT" --data-dir "$DATA_DIR" --coord localhost:"$COORD_PORT" > run/shardA.log 2>&1 & echo $! > run/shardA.pid
nohup java -cp out org.example.dtx.Main participant --id shardB --port "$B_PORT" --data-dir "$DATA_DIR" --coord localhost:"$COORD_PORT" > run/shardB.log 2>&1 & echo $! > run/shardB.pid
nohup java -cp out org.example.dtx.Main participant --id shardC --port "$C_PORT" --data-dir "$DATA_DIR" --coord localhost:"$COORD_PORT" > run/shardC.log 2>&1 & echo $! > run/shardC.pid
nohup java -cp out org.example.dtx.Main coordinator --port "$COORD_PORT" --data-dir "$DATA_DIR" --shards shardA=localhost:"$A_PORT",shardB=localhost:"$B_PORT",shardC=localhost:"$C_PORT" > run/coordinator.log 2>&1 & echo $! > run/coordinator.pid
sleep 1
for pid_file in run/shardA.pid run/shardB.pid run/shardC.pid run/coordinator.pid; do
  pid=$(cat "$pid_file")
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "process from $pid_file is not running; check run/*.log" >&2
    exit 1
  fi
done

echo "cluster started: coordinator localhost:$COORD_PORT, data-dir=$DATA_DIR"
echo "logs: run/*.log, pids: run/*.pid"

#!/usr/bin/env bash
set -euo pipefail

COORD=${COORD:-localhost:7000}
CSV=${CSV:-benchmarks/results.csv}
CLI=(java -cp out org.example.dtx.Main cli --coord "$COORD")

mkdir -p benchmarks
: > "$CSV"

"${CLI[@]}" benchTransfers --part A --scenario participants-2 --shards 2 --threads 8 --tx 2000 --amount 10 --initialBalance 100000000 --csv "$CSV"
"${CLI[@]}" benchTransfers --part A --scenario participants-3 --shards 3 --threads 8 --tx 2000 --amount 10 --initialBalance 100000000 --csv "$CSV"
"${CLI[@]}" benchTransfers --part A --scenario participants-5 --shards 5 --threads 8 --tx 2000 --amount 10 --initialBalance 100000000 --csv "$CSV"
"${CLI[@]}" benchTransfers --part A --scenario participants-15 --shards 15 --threads 8 --tx 2000 --amount 10 --initialBalance 100000000 --csv "$CSV"
"${CLI[@]}" benchTransfers --part B --scenario balance-1000 --shards 3 --threads 8 --tx 2000 --amount 100 --senderPool 200 --initialBalance 1000 --csv "$CSV"
"${CLI[@]}" benchTransfers --part B --scenario balance-100 --shards 3 --threads 8 --tx 2000 --amount 100 --senderPool 200 --initialBalance 100 --csv "$CSV"
"${CLI[@]}" benchTransfers --part B --scenario balance-50 --shards 3 --threads 8 --tx 2000 --amount 100 --senderPool 200 --initialBalance 50 --csv "$CSV"
"${CLI[@]}" benchTransfers --part C --scenario sender-pool-50 --shards 3 --threads 16 --tx 2000 --amount 10 --senderPool 50 --initialBalance 100000000 --debitDelayMs 20 --csv "$CSV"
"${CLI[@]}" benchTransfers --part C --scenario sender-pool-5 --shards 3 --threads 16 --tx 2000 --amount 10 --senderPool 5 --initialBalance 100000000 --debitDelayMs 20 --csv "$CSV"
"${CLI[@]}" benchTransfers --part C --scenario sender-pool-1 --shards 3 --threads 16 --tx 2000 --amount 10 --senderPool 1 --initialBalance 100000000 --debitDelayMs 20 --csv "$CSV"
"${CLI[@]}" benchLocalCredit --part D --scenario local-credit --threads 8 --tx 2000 --amount 10 --csv "$CSV"
"${CLI[@]}" benchTransfers --part E-load --scenario wal-load-100 --shards 2 --threads 4 --tx 100 --amount 10 --initialBalance 100000000
"${CLI[@]}" benchWalReplay --part E --scenario wal-replay-100 --shards 2 --tx 100 --lines 100 --csv "$CSV"
"${CLI[@]}" benchTransfers --part E-load --scenario wal-load-10000 --shards 2 --threads 8 --tx 10000 --amount 10 --initialBalance 100000000
"${CLI[@]}" benchWalReplay --part E --scenario wal-replay-10000 --shards 2 --tx 10000 --lines 10000 --csv "$CSV"

python3 scripts/render-benchmark-figures.py
echo "written $CSV and benchmarks/figures/*.svg"

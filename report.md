# Benchmark Report

Реализация содержит CLI-бенчмарки, которые пишут CSV в формате задания:

```bash
make build
./scripts/run-benchmarks.sh
```

CSV: `benchmarks/results.csv`.

Для финального прогона был поднят кластер из 15 participant-процессов (`shard01..shard15`) и одного coordinator. Это нужно, чтобы часть A действительно измеряла планы на 2/3/5/15 участников.

## Таблица прогонов

| part | scenario | shardCount | threads | totalTx | throughputTxPerSec | avgMs | p95Ms | commitRatio | abortRatio | extraMetric |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A | participants-2 | 2 | 8 | 2000 | 125.12 | 63.76 | 76.79 | 1.0000 | 0.0000 | participants=2;senderPool=8;initialBalance=100000000;debitDelayMs=0 |
| A | participants-3 | 3 | 8 | 2000 | 116.79 | 68.29 | 77.63 | 1.0000 | 0.0000 | participants=3;senderPool=8;initialBalance=100000000;debitDelayMs=0 |
| A | participants-5 | 5 | 8 | 2000 | 104.93 | 76.00 | 90.52 | 1.0000 | 0.0000 | participants=5;senderPool=8;initialBalance=100000000;debitDelayMs=0 |
| A | participants-15 | 15 | 8 | 2000 | 52.97 | 150.56 | 171.80 | 1.0000 | 0.0000 | participants=15;senderPool=8;initialBalance=100000000;debitDelayMs=0 |
| B | balance-1000 | 3 | 8 | 2000 | 114.96 | 69.42 | 77.74 | 1.0000 | 0.0000 | participants=3;senderPool=200;initialBalance=1000;debitDelayMs=0 |
| B | balance-100 | 3 | 8 | 2000 | 117.34 | 68.01 | 75.32 | 0.1000 | 0.9000 | participants=3;senderPool=200;initialBalance=100;debitDelayMs=0 |
| B | balance-50 | 3 | 8 | 2000 | 125.31 | 63.67 | 70.05 | 0.0000 | 1.0000 | participants=3;senderPool=200;initialBalance=50;debitDelayMs=0 |
| C | sender-pool-50 | 3 | 16 | 2000 | 106.59 | 149.63 | 180.42 | 1.0000 | 0.0000 | participants=3;senderPool=50;initialBalance=100000000;debitDelayMs=20 |
| C | sender-pool-5 | 3 | 16 | 2000 | 124.04 | 128.54 | 156.76 | 1.0000 | 0.0000 | participants=3;senderPool=5;initialBalance=100000000;debitDelayMs=20 |
| C | sender-pool-1 | 3 | 16 | 2000 | 34.80 | 459.34 | 466.22 | 1.0000 | 0.0000 | participants=3;senderPool=1;initialBalance=100000000;debitDelayMs=20 |
| D | local-credit | 1 | 8 | 2000 | 1082.28 | 7.23 | 14.44 | 1.0000 | 0.0000 | direct-participant-credit |
| E | wal-replay-100 | 2 | 1 | 100 | 0.00 | 5.77 | 5.77 | 0.0000 | 0.0000 | walReplayMs=5.77;walLines=1500;limitLines=100 |
| E | wal-replay-10000 | 2 | 1 | 10000 | 0.00 | 77.96 | 77.96 | 0.0000 | 0.0000 | walReplayMs=77.96;walLines=146506;limitLines=10000 |

## Графики

Графики построены из CSV без внешних зависимостей:

```bash
python3 scripts/render-benchmark-figures.py
```

- `benchmarks/figures/part-a-scaling.svg` — throughput и p95 vs participants.
- `benchmarks/figures/part-b-aborts.svg` — abort ratio vs initial balance.
- `benchmarks/figures/part-c-contention.svg` — throughput/abort ratio vs sender pool size.
- `benchmarks/figures/part-d-local-vs-2pc.svg` — local credit vs 2PC latency.
- `benchmarks/figures/part-e-wal-volume.svg` — WAL replay time vs replayed WAL prefix size.

## Observations

Part A показывает стоимость роста числа участников: p95 растет с `76.79 ms` при 2 участниках до `171.80 ms` при 15, throughput падает с `125.12 tx/s` до `52.97 tx/s`.

Part B теперь использует `senderPool=200`, `totalTx=2000`, amount `100`. Поэтому каждый sender делает примерно 10 попыток: при initial balance `1000` проходят все, при `100` проходит одна из десяти (`commitRatio=0.1000`), при `50` все abort'ятся.

Part C использует `debitDelayMs=20` на hook `after-prepared-debit`, чтобы hot account реально держал prepare/reservation окно. При `senderPool=1` throughput падает до `34.80 tx/s`, а p95 растет до `466.22 ms`; это демонстрирует contention. `senderPool=5` получился быстрее `senderPool=50` в этом конкретном прогоне из-за hash-раскладки счетов по шардам и планов, но крайний hot-account случай ясно отделяется.

Part D показывает цену 2PC против локального RPC: local credit дает `1082.28 tx/s` и p95 `14.44 ms`, тогда как 2PC при 2 участниках дает p95 `76.79 ms`.

Part E теперь измеряет именно replay WAL через `benchWalReplay`: prefix 100 строк replay'ится за `5.77 ms`, prefix 10000 строк — за `77.96 ms`. Это показывает ожидаемый рост startup/replay времени от объема WAL.

## Trade-offs

Latency 2PC растет с числом участников, потому что координатор должен получить голоса всех участников, а затем доставить финальное решение всем участникам. Самый медленный участник задает нижнюю границу времени фазы, а каждый участник добавляет WAL-записи и сетевые RTT.

Abort по голосу обычно дешевле commit: после `VOTE_ABORT` не нужно применять бизнес-изменение; участники только снимают reservation или подтверждают no-op abort. Commit требует durable финализации и применения операций.

Reservations создают логическую блокировку в prepare-фазе. Пока tx находится в `PREPARED`, деньги отправителя считаются занятыми. При недостатке средств это дает быстрые `VOTE_ABORT`, а при hot-account workload увеличивает очередь/latency на participant, который держит prepare окно.

2PC принципиально дороже локальной операции: минимум prepare RTT, decision RTT и durable WAL у координатора и участников. Локальный `CREDIT` в `benchLocalCredit` идет напрямую на один shard и пишет одну локальную WAL-запись.

Если участник долго блокируется в `PREPARED`, throughput падает: reservation держит часть баланса, новые транзакции чаще abort'ятся или ждут ретраев, а координатор тратит worker-время на фазу 2 и таймауты.

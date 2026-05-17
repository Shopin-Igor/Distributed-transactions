# Distributed Transactions HW6: 2PC + WAL + Recovery
## Сборка

```bash
make build
```

## Быстрый старт

В одном терминале можно поднять локальный кластер из координатора и трех шардов:

```bash
./scripts/start-local-cluster.sh
```

Остановить процессы:

```bash
./scripts/stop-local-cluster.sh
```

Ручной запуск теми же командами:

```bash
java -cp out org.example.dtx.Main participant --id shardA --port 7101 --data-dir data --coord localhost:7000
```

```bash
java -cp out org.example.dtx.Main participant --id shardB --port 7102 --data-dir data --coord localhost:7000
```

```bash
java -cp out org.example.dtx.Main participant --id shardC --port 7103 --data-dir data --coord localhost:7000
```

```bash
java -cp out org.example.dtx.Main coordinator --port 7000 --data-dir data --shards shardA=localhost:7101,shardB=localhost:7102,shardC=localhost:7103
```

CLI:

```bash
java -cp out org.example.dtx.Main cli --coord localhost:7000 setBalance alice 200
```

```bash
java -cp out org.example.dtx.Main cli --coord localhost:7000 setBalance bob 0
```

```bash
java -cp out org.example.dtx.Main cli --coord localhost:7000 transfer alice bob 100
```

```bash
java -cp out org.example.dtx.Main cli --coord localhost:7000 balance alice
```

```bash
java -cp out org.example.dtx.Main cli --coord localhost:7000 clusterDump
```

Если после `cli --coord ...` не указать команду, откроется REPL.

## Архитектура

- `CoordinatorServer` принимает клиентские команды, строит план транзакции, пишет `<data-dir>/coordinator/wal.log`, рассылает `PREPARE`, собирает голоса и после durable `DECISION` рассылает `COMMIT` или `ABORT`.
- `ParticipantServer` хранит in-memory `balances`, `reservations`, `records`, пишет `<data-dir>/<shardId>/wal.log` и восстанавливает все эти структуры replay'ем WAL.
- `ClientCli` отдельным процессом общается с координатором по TCP.
- `Rpc` реализует TCP JSON Lines: один JSON-запрос и один JSON-ответ на соединение.
- `FileWalLog` реализует `DurableLog`: одна append-запись через `Files.writeString(..., CREATE, WRITE, APPEND)` и последующий `FileChannel.force(true)`.

Маршрутизация счета детерминированная: `Math.floorMod(account.hashCode(), shardCount)`. Для стандартного порядка `shardA, shardB, shardC`: `alice -> shardA`, `bob -> shardB`.

## WAL

Координатор:

```json
{
  "txId": "...",
  "event": "BEGIN",
  "plan": [
    {
      "node": "shardA",
      "op": "DEBIT alice 100"
    },
    {
      "node": "shardB",
      "op": "CREDIT bob 100"
    }
  ]
}

{
  "txId": "...",
  "event": "DECISION",
  "decision": "COMMIT"
}
```

Участник:

```json
{
  "txId": "...",
  "event": "PREPARED",
  "operation": "DEBIT alice 100"
}

{
  "txId": "...",
  "event": "COMMITTED",
  "operation": "DEBIT alice 100"
}
```

`setBalance` также durable: он пишет synthetic `COMMITTED` с operation `SET_BALANCE <account> <amount>`, поэтому стартовые балансы переживают restart участника.

## Инварианты log-before-reply

- `VOTE_COMMIT` отправляется только после `PREPARED` в WAL участника.
- `COMMIT/ABORT` рассылается только после `DECISION` в WAL координатора.
- `ACK COMMITTED/ABORTED` отправляется только после `COMMITTED/ABORTED` в WAL участника.

Это удобно проверять через `tail -f data/*/wal.log` во время `transfer`.

## Протокол

CLI -> Coordinator:

- `CLIENT_TRANSFER`, `CLIENT_BALANCE`, `CLIENT_LIST_TX`, `CLIENT_TX_LOG`, `CLIENT_CLUSTER_DUMP`, `CLIENT_SET_BALANCE`, `CLIENT_SET_DELAY`, `CLIENT_ARM_CRASH`, `CLIENT_SET_RPC_TIMEOUT`, `CLIENT_SET_RPC_RETRIES`, `CLIENT_LOCAL_CREDIT`.

Coordinator -> Participant:

- `PREPARE {txId, operation}`, `COMMIT {txId}`, `ABORT {txId}`, а также debug RPC `GET_BALANCE`, `SET_BALANCE`, `DUMP`, `SET_DELAY`, `ARM_CRASH`, `TX_LOG`, `LOCAL_CREDIT`.

Participant -> Coordinator during recovery:

- `STATUS_QUERY {txId}` -> `STATUS_RESPONSE`-совместимый ответ `{decision: COMMIT|ABORT|UNKNOWN}`.

## Recovery

Recovery запускается автоматически при старте процесса с тем же `--data-dir`.

Координатор читает WAL, восстанавливает планы и решения. Если есть `DECISION`, он roll-forward рассылает решение участникам. Если есть `BEGIN` без `DECISION`, он применяет conservative safe abort: дописывает `DECISION=ABORT` и рассылает `ABORT`.

Участник читает WAL линейно. `PREPARED` восстанавливает запись и reservation для `DEBIT/TRANSFER`; `COMMITTED` применяет операцию к балансу; `ABORTED` снимает reservation. Если после replay остались `PREPARED`, участник делает `STATUS_QUERY` координатору. При `UNKNOWN` или недоступном координаторе участник остается в `PREPARED`: это и есть blocking problem 2PC.

## CLI-команды

```bash
transfer <from> <to> <amount>
```

```bash
balance <account>
```

```bash
clusterDump
```

```bash
setBalance <account> <amount>
```

```bash
listTransactions
```

```bash
txLog
```

```bash
txLog --participant <nodeId>
```

```bash
crashCoordinator <after-begin|before-decision|after-decision>
```

```bash
crashParticipant <nodeId> <after-prepared|after-committed>
```

```bash
setDelay <coord|nodeId> <hook> <ms>
```

```bash
setRpcTimeout <ms>
```

```bash
setRpcRetries <n>
```

```bash
benchTransfers --threads T --tx M [--shards N] [--amount A] [--senderPool P] [--initialBalance B] [--debitDelayMs MS] [--csv file]
```

```bash
benchLocalCredit --threads T --tx M [--csv file]
```

```bash
benchWalReplay --part E --scenario wal-replay-N --shards N --tx M --lines L [--csv file]
```

`benchTransfers --shards N` вовлекает до `N` участников в план. Если в кластере меньше шардов, используется доступное число.

## Бенчмарки

Запуск набора из 13 result-прогонов:

```bash
./scripts/run-benchmarks.sh
```

Результаты пишутся в `benchmarks/results.csv`. Для части A с 5/15 шардами нужно запустить координатор с соответствующим списком `--shards`; на трехшардовом локальном кластере команда честно ограничится тремя участниками.
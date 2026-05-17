# Demo Scenarios

Перед сценариями соберите проект и поднимите кластер:

```bash
make build
```

```bash
export CLI='java -cp out org.example.dtx.Main cli --coord localhost:7000'
```

Для чистого прогона поднимите кластер в директории `data-demo-1`:

```bash
DATA_DIR=data-demo-1 ./scripts/start-local-cluster.sh
```

Важно: при ручном перезапуске coordinator или participant всегда используйте тот же `--data-dir data-demo-1`. Иначе процесс прочитает другой WAL, и recovery/`txLog` будут выглядеть пустыми.

## 1. Happy Path

```bash
$CLI setBalance alice 200
```

```bash
$CLI setBalance bob 0
```

```bash
$CLI transfer alice bob 100
```

```bash
$CLI balance alice
```

```bash
$CLI balance bob
```

```bash
$CLI txLog
```

```bash
$CLI txLog --participant shardA
```

Ожидается `decision=COMMIT`, `alice=100`, `bob=100`. В WAL координатора есть `BEGIN` и `DECISION=COMMIT`; в WAL `shardA` запись `PREPARED` идет раньше `COMMITTED`.

## 2. Abort By Participant Vote

```bash
$CLI setBalance alice 50
```

```bash
$CLI setBalance bob 0
```

```bash
$CLI transfer alice bob 100
```

```bash
$CLI balance alice
```

```bash
$CLI balance bob
```

```bash
$CLI txLog --participant shardA
```

Ожидается `decision=ABORT`, баланс `alice` не списан, а для txId нет успешного `COMMITTED` debit.

## 3. Abort By Phase-1 Timeout

```bash
$CLI setBalance alice 200
```

```bash
$CLI setBalance bob 0
```

```bash
$CLI setRpcTimeout 1000
```

```bash
$CLI setRpcRetries 3
```

```bash
$CLI crashParticipant shardB after-prepared
```

```bash
$CLI transfer alice bob 100
```

`shardB` пишет `PREPARED` и завершает JVM до `VOTE`. Координатор после ретраев выбирает `ABORT`.

Перезапуск участника с той же директорией `data-demo-1`:

```bash
java -cp out org.example.dtx.Main participant --id shardB --port 7102 --data-dir data-demo-1 --coord localhost:7000
```

```bash
$CLI txLog --participant shardB
```

После replay участник делает `STATUS_QUERY`, получает `ABORT` и пишет `ABORTED`.

## 4. Lost COMMIT And Phase-2 Retry

Откройте окно задержкой после durable commit участника, но до `ACK`:

```bash
$CLI setBalance alice 200
```

```bash
$CLI setBalance bob 0
```

```bash
$CLI setRpcTimeout 1000
```

```bash
$CLI setRpcRetries 3
```

```bash
$CLI setDelay shardB after-committed 5000
```

```bash
$CLI transfer alice bob 100
```

```bash
$CLI setDelay shardB after-committed 0
```

```bash
$CLI balance alice
```

```bash
$CLI balance bob
```

`shardB` пишет `COMMITTED`, задерживается до `ACK`, а координатор видит timeout и ретраит `COMMIT`. Повторный `COMMIT` на `COMMITTED` участнике — no-op + ACK.

## 5. Coordinator Crash After DECISION

```bash
$CLI setBalance alice 200
```

```bash
$CLI setBalance bob 0
```

```bash
$CLI crashCoordinator after-decision
```

```bash
$CLI transfer alice bob 100
```

```bash
cat data-demo-1/coordinator/wal.log
```

Координатор записывает `DECISION=COMMIT` и падает до broadcast. Перезапустите координатор с той же директорией `data-demo-1`:

```bash
java -cp out org.example.dtx.Main coordinator --port 7000 --data-dir data-demo-1 --shards shardA=localhost:7101,shardB=localhost:7102,shardC=localhost:7103
```

```bash
$CLI balance alice
```

```bash
$CLI balance bob
```

Recovery дочитает WAL и досылает `COMMIT`.

## 6. Blocking Problem And Safe Abort

Наглядный вариант с окном для наблюдения:

```bash
$CLI setBalance alice 200
```

```bash
$CLI setBalance bob 0
```

```bash
$CLI setDelay coord before-decision 10000
```

terminal A:

```bash
export CLI='java -cp out org.example.dtx.Main cli --coord localhost:7000'
```

```bash
$CLI transfer alice bob 100
```

terminal B, пока terminal A ждет:

```bash
export CLI='java -cp out org.example.dtx.Main cli --coord localhost:7000'
```

```bash
$CLI clusterDump
```

```bash
jps -l
```

```bash
kill -9 <pid-of-coordinator>
```

Детерминированный вариант без ручного kill:

```bash
$CLI crashCoordinator before-decision
```

```bash
$CLI transfer alice bob 100
```

```bash
cat data-demo-1/shardA/wal.log
```

Координатор упал после голосов, но до `DECISION`. Участники остаются в `PREPARED`, reservation блокирует деньги. После падения координатора обычный CLI недоступен, поэтому состояние до kill удобнее смотреть через delay-вариант или WAL участника. Участник не выбирает исход самостоятельно.

Перезапуск координатора:

```bash
java -cp out org.example.dtx.Main coordinator --port 7000 --data-dir data-demo-1 --shards shardA=localhost:7101,shardB=localhost:7102,shardC=localhost:7103
```

```bash
$CLI clusterDump
```

```bash
$CLI txLog
```

Recovery видит `BEGIN` без `DECISION`, пишет `DECISION=ABORT` и снимает reservation через `ABORT`.

## 7. Participant Crash After PREPARED Or COMMITTED

Вариант `after-committed`:

```bash
$CLI setBalance alice 200
```

```bash
$CLI setBalance bob 0
```

```bash
$CLI crashParticipant shardA after-committed
```

```bash
$CLI transfer alice bob 100
```

```bash
java -cp out org.example.dtx.Main participant --id shardA --port 7101 --data-dir data-demo-1 --coord localhost:7000
```

```bash
$CLI balance alice
```

```bash
$CLI txLog --participant shardA
```

`shardA` записал `COMMITTED` до `ACK`; после restart replay восстановит списание, а повторный `COMMIT` будет no-op + ACK.

Вариант `after-prepared`:

```bash
$CLI crashParticipant shardA after-prepared
```

```bash
$CLI transfer alice bob 100
```

```bash
java -cp out org.example.dtx.Main participant --id shardA --port 7101 --data-dir data-demo-1 --coord localhost:7000
```

```bash
$CLI txLog --participant shardA
```

После replay участник видит `PREPARED`, спрашивает координатора через `STATUS_QUERY`, получает решение и дописывает финал.

## 8. Durability Check

```bash
$CLI setBalance alice 1000
```

```bash
$CLI setBalance bob 0
```

```bash
for i in $(seq 1 50); do $CLI transfer alice bob 10; done
```

```bash
jps -l
```

```bash
ps -fp ...
```


```bash
kill -9 <pid-of-shardA>
```

```bash
java -cp out org.example.dtx.Main participant --id shardA --port 7101 --data-dir data-demo-1 --coord localhost:7000
```

```bash
$CLI clusterDump
```

```bash
$CLI balance alice
```

```bash
$CLI balance bob
```

После restart баланс восстанавливается replay'ем WAL. Если во время kill осталась незавершенная `PREPARED`, reservation тоже восстановится и будет завершена через `STATUS_QUERY`.

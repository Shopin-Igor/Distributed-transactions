package org.example.dtx;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class ParticipantServer {
    private enum ParticipantState {
        PREPARED,
        COMMITTED,
        ABORTED
    }

    private record Reservation(String account, long amount) {
    }

    private record ParticipantRecord(ParticipantState state, String operation) {
    }

    private final String id;
    private final int port;
    private final NodeAddress coordinator;
    private final FileWalLog wal;
    private final Map<String, Long> balances = new HashMap<>();
    private final Map<String, Reservation> reservations = new HashMap<>();
    private final Map<String, ParticipantRecord> records = new HashMap<>();
    private final Map<String, Long> delays = new ConcurrentHashMap<>();
    private volatile String crashHook;

    ParticipantServer(String id, int port, Path dataDir, NodeAddress coordinator) throws IOException {
        this.id = id;
        this.port = port;
        this.coordinator = coordinator;
        this.wal = new FileWalLog(dataDir.resolve(id).resolve("wal.log"));
    }

    void start() throws Exception {
        long started = System.nanoTime();
        replayWal();
        long replayMs = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("%s replay complete: walReplayMs=%d, balances=%s, prepared=%d%n",
                id, replayMs, balances, preparedTxIds().size());
        recoverPreparedTransactions();
        Rpc.serve(port, this::handle);
    }

    private Map<String, Object> handle(Map<String, Object> request) throws Exception {
        String type = Json.str(request, "type");
        return switch (type) {
            case "PREPARE" -> handlePrepare(Json.str(request, "txId"), Json.str(request, "operation"));
            case "COMMIT" -> handleCommit(Json.str(request, "txId"));
            case "ABORT" -> handleAbort(Json.str(request, "txId"));
            case "GET_BALANCE" -> handleGetBalance(Json.str(request, "account"));
            case "SET_BALANCE" -> handleSetBalance(Json.str(request, "account"), Json.longValue(request, "amount", 0));
            case "DUMP" -> handleDump();
            case "SET_DELAY" -> handleSetDelay(Json.str(request, "hook"), Json.longValue(request, "ms", 0));
            case "ARM_CRASH" -> handleArmCrash(Json.str(request, "hook"));
            case "TX_LOG" -> Json.obj("ok", true, "node", id, "content", String.join("\n", wal.readAll()));
            case "LOCAL_CREDIT" -> handleLocalCredit(Json.str(request, "account"), Json.longValue(request, "amount", 0));
            case "BENCH_REPLAY" -> handleBenchReplay(Json.intValue(request, "limitLines", 0));
            default -> Json.obj("ok", false, "error", "Unknown participant request type: " + type);
        };
    }

    private synchronized Map<String, Object> handlePrepare(String txId, String operation) throws Exception {
        ParticipantRecord existing = records.get(txId);
        if (existing != null) {
            return switch (existing.state()) {
                case PREPARED, COMMITTED -> vote(txId, "COMMIT", "idempotent-" + existing.state());
                case ABORTED -> vote(txId, "ABORT", "idempotent-aborted");
            };
        }

        Operation parsed = Operation.parse(operation);
        if (!canPrepare(parsed)) {
            appendParticipantEvent(txId, "ABORTED", operation);
            records.put(txId, new ParticipantRecord(ParticipantState.ABORTED, operation));
            return vote(txId, "ABORT", "insufficient-funds");
        }

        appendParticipantEvent(txId, "PREPARED", operation);
        records.put(txId, new ParticipantRecord(ParticipantState.PREPARED, operation));
        reserveIfNeeded(txId, parsed);
        runHook("after-prepared");
        if (parsed.kind() == Operation.Kind.DEBIT || parsed.kind() == Operation.Kind.TRANSFER) {
            runHook("after-prepared-debit");
        }
        return vote(txId, "COMMIT", "prepared");
    }

    private synchronized Map<String, Object> handleCommit(String txId) throws Exception {
        ParticipantRecord existing = records.get(txId);
        if (existing == null) {
            return Json.obj("ok", false, "type", "ACK", "txId", txId, "kind", "IGNORED", "reason", "commit-before-prepare");
        }
        if (existing.state() == ParticipantState.COMMITTED) {
            return ack(txId, "COMMITTED", "idempotent");
        }
        if (existing.state() == ParticipantState.ABORTED) {
            return Json.obj("ok", false, "type", "ACK", "txId", txId, "kind", "IGNORED", "reason", "already-aborted");
        }

        appendParticipantEvent(txId, "COMMITTED", existing.operation());
        runHook("after-committed");
        applyCommit(Operation.parse(existing.operation()));
        reservations.remove(txId);
        records.put(txId, new ParticipantRecord(ParticipantState.COMMITTED, existing.operation()));
        return ack(txId, "COMMITTED", "committed");
    }

    private synchronized Map<String, Object> handleAbort(String txId) throws Exception {
        ParticipantRecord existing = records.get(txId);
        if (existing == null) {
            return ack(txId, "ABORTED", "abort-before-prepare");
        }
        if (existing.state() == ParticipantState.ABORTED) {
            return ack(txId, "ABORTED", "idempotent");
        }
        if (existing.state() == ParticipantState.COMMITTED) {
            return Json.obj("ok", false, "type", "ACK", "txId", txId, "kind", "IGNORED", "reason", "already-committed");
        }

        appendParticipantEvent(txId, "ABORTED", existing.operation());
        reservations.remove(txId);
        records.put(txId, new ParticipantRecord(ParticipantState.ABORTED, existing.operation()));
        return ack(txId, "ABORTED", "aborted");
    }

    private synchronized Map<String, Object> handleGetBalance(String account) {
        return Json.obj("ok", true, "node", id, "account", account, "balance", balances.getOrDefault(account, 0L));
    }

    private synchronized Map<String, Object> handleSetBalance(String account, long amount) throws IOException {
        String txId = "admin-" + UUID.randomUUID();
        String operation = Operation.setBalance(account, amount);
        appendParticipantEvent(txId, "COMMITTED", operation);
        applyCommit(Operation.parse(operation));
        records.put(txId, new ParticipantRecord(ParticipantState.COMMITTED, operation));
        return Json.obj("ok", true, "node", id, "account", account, "balance", amount, "txId", txId);
    }

    private synchronized Map<String, Object> handleLocalCredit(String account, long amount) throws IOException {
        String txId = "local-" + UUID.randomUUID();
        String operation = Operation.credit(account, amount);
        appendParticipantEvent(txId, "COMMITTED", operation);
        applyCommit(Operation.parse(operation));
        records.put(txId, new ParticipantRecord(ParticipantState.COMMITTED, operation));
        return Json.obj("ok", true, "node", id, "account", account, "balance", balances.getOrDefault(account, 0L), "txId", txId);
    }

    private synchronized Map<String, Object> handleBenchReplay(int limitLines) throws IOException {
        List<String> lines = wal.readAll();
        if (limitLines > 0 && lines.size() > limitLines) {
            lines = lines.subList(0, limitLines);
        }

        Map<String, Long> replayBalances = new HashMap<>();
        Map<String, Reservation> replayReservations = new HashMap<>();
        Map<String, ParticipantRecord> replayRecords = new HashMap<>();
        long started = System.nanoTime();
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> entry = Json.parseObject(line);
            String txId = Json.str(entry, "txId");
            String event = Json.str(entry, "event");
            String operation = Json.str(entry, "operation");
            if (txId == null || event == null || operation == null) {
                continue;
            }
            Operation parsed = Operation.parse(operation);
            switch (event) {
                case "PREPARED" -> {
                    replayRecords.put(txId, new ParticipantRecord(ParticipantState.PREPARED, operation));
                    reserveIfNeeded(replayReservations, txId, parsed);
                }
                case "COMMITTED" -> {
                    applyCommit(replayBalances, parsed);
                    replayReservations.remove(txId);
                    replayRecords.put(txId, new ParticipantRecord(ParticipantState.COMMITTED, operation));
                }
                case "ABORTED" -> {
                    replayReservations.remove(txId);
                    replayRecords.put(txId, new ParticipantRecord(ParticipantState.ABORTED, operation));
                }
                default -> {
                }
            }
        }
        double replayMs = (System.nanoTime() - started) / 1_000_000.0;
        return Json.obj(
                "ok", true,
                "node", id,
                "walLines", lines.size(),
                "replayMs", replayMs,
                "balances", replayBalances.size(),
                "prepared", replayReservations.size(),
                "records", replayRecords.size());
    }

    private synchronized Map<String, Object> handleDump() {
        List<Object> reservationDump = reservations.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> Json.obj(
                        "txId", entry.getKey(),
                        "account", entry.getValue().account(),
                        "amount", entry.getValue().amount()))
                .map(object -> (Object) object)
                .toList();
        Map<String, Object> recordDump = new TreeMap<>();
        records.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> recordDump.put(entry.getKey(), Json.obj(
                        "state", entry.getValue().state().name(),
                        "operation", entry.getValue().operation())));
        return Json.obj(
                "ok", true,
                "node", id,
                "balances", new TreeMap<>(balances),
                "reservations", reservationDump,
                "records", recordDump);
    }

    private Map<String, Object> handleSetDelay(String hook, long ms) {
        if (ms <= 0) {
            delays.remove(hook);
        } else {
            delays.put(hook, ms);
        }
        return Json.obj("ok", true, "node", id, "hook", hook, "ms", Math.max(ms, 0));
    }

    private Map<String, Object> handleArmCrash(String hook) {
        crashHook = hook;
        return Json.obj("ok", true, "node", id, "armed", hook);
    }

    private Map<String, Object> vote(String txId, String vote, String reason) {
        return Json.obj("ok", true, "type", "VOTE", "node", id, "txId", txId, "vote", vote, "reason", reason);
    }

    private Map<String, Object> ack(String txId, String kind, String reason) {
        return Json.obj("ok", true, "type", "ACK", "node", id, "txId", txId, "kind", kind, "reason", reason);
    }

    private void replayWal() throws IOException {
        for (String line : wal.readAll()) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> entry = Json.parseObject(line);
            String txId = Json.str(entry, "txId");
            String event = Json.str(entry, "event");
            String operation = Json.str(entry, "operation");
            if (txId == null || event == null || operation == null) {
                continue;
            }
            Operation parsed = Operation.parse(operation);
            switch (event) {
                case "PREPARED" -> {
                    records.put(txId, new ParticipantRecord(ParticipantState.PREPARED, operation));
                    reserveIfNeeded(txId, parsed);
                }
                case "COMMITTED" -> {
                    applyCommit(parsed);
                    reservations.remove(txId);
                    records.put(txId, new ParticipantRecord(ParticipantState.COMMITTED, operation));
                }
                case "ABORTED" -> {
                    reservations.remove(txId);
                    records.put(txId, new ParticipantRecord(ParticipantState.ABORTED, operation));
                }
                default -> System.err.printf("%s ignores unknown WAL event %s in %s%n", id, event, line);
            }
        }
    }

    private void recoverPreparedTransactions() {
        if (coordinator == null) {
            return;
        }
        for (String txId : preparedTxIds()) {
            try {
                Map<String, Object> response = Rpc.request(coordinator, Json.obj("type", "STATUS_QUERY", "txId", txId, "node", id), 1500);
                String decision = Json.str(response, "decision", "UNKNOWN");
                if ("COMMIT".equals(decision)) {
                    handleCommit(txId);
                } else if ("ABORT".equals(decision)) {
                    handleAbort(txId);
                } else {
                    System.out.printf("%s remains PREPARED for %s: coordinator decision is %s%n", id, txId, decision);
                }
            } catch (Exception e) {
                System.out.printf("%s remains PREPARED for %s: coordinator unavailable (%s)%n", id, txId, e.getMessage());
            }
        }
    }

    private List<String> preparedTxIds() {
        return records.entrySet().stream()
                .filter(entry -> entry.getValue().state() == ParticipantState.PREPARED)
                .map(Map.Entry::getKey)
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private boolean canPrepare(Operation operation) {
        return switch (operation.kind()) {
            case CREDIT, SET_BALANCE -> true;
            case DEBIT, TRANSFER -> available(operation.account()) >= operation.amount();
        };
    }

    private long available(String account) {
        long reserved = reservations.values().stream()
                .filter(reservation -> reservation.account().equals(account))
                .mapToLong(Reservation::amount)
                .sum();
        return balances.getOrDefault(account, 0L) - reserved;
    }

    private void reserveIfNeeded(String txId, Operation operation) {
        reserveIfNeeded(reservations, txId, operation);
    }

    private static void reserveIfNeeded(Map<String, Reservation> target, String txId, Operation operation) {
        if (operation.kind() == Operation.Kind.DEBIT || operation.kind() == Operation.Kind.TRANSFER) {
            target.put(txId, new Reservation(operation.account(), operation.amount()));
        }
    }

    private void applyCommit(Operation operation) {
        applyCommit(balances, operation);
    }

    private static void applyCommit(Map<String, Long> target, Operation operation) {
        switch (operation.kind()) {
            case DEBIT -> target.merge(operation.account(), -operation.amount(), Long::sum);
            case CREDIT -> target.merge(operation.account(), operation.amount(), Long::sum);
            case TRANSFER -> {
                target.merge(operation.account(), -operation.amount(), Long::sum);
                target.merge(operation.secondAccount(), operation.amount(), Long::sum);
            }
            case SET_BALANCE -> target.put(operation.account(), operation.amount());
        }
    }

    private void appendParticipantEvent(String txId, String event, String operation) throws IOException {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", Instant.now().toString());
        entry.put("txId", txId);
        entry.put("event", event);
        entry.put("operation", operation);
        wal.append(Json.stringify(entry));
        System.out.printf("%s WAL %s %s %s%n", id, txId, event, operation);
    }

    private void runHook(String hook) {
        long delayMs = delays.getOrDefault(hook, 0L);
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (hook.equals(crashHook)) {
            System.err.printf("%s crashes at hook %s%n", id, hook);
            System.exit(20);
        }
    }
}

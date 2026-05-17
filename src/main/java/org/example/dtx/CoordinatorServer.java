package org.example.dtx;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class CoordinatorServer {
    private record PlanStep(String node, String operation) {
        Map<String, Object> toJson() {
            return Json.obj("node", node, "op", operation);
        }
    }

    private final int port;
    private final FileWalLog wal;
    private final Map<String, NodeAddress> shards;
    private final List<String> shardOrder;
    private final Map<String, List<PlanStep>> plans = new ConcurrentHashMap<>();
    private final Map<String, String> decisions = new ConcurrentHashMap<>();
    private final Map<String, Long> delays = new ConcurrentHashMap<>();
    private volatile String crashHook;
    private volatile int rpcTimeoutMs = 1500;
    private volatile int rpcRetries = 3;

    CoordinatorServer(int port, Path dataDir, Map<String, NodeAddress> shards) throws IOException {
        this.port = port;
        this.shards = new LinkedHashMap<>(shards);
        this.shardOrder = List.copyOf(shards.keySet());
        this.wal = new FileWalLog(dataDir.resolve("coordinator").resolve("wal.log"));
    }

    void start() throws Exception {
        replayWal();
        recoverTransactions();
        Rpc.serve(port, this::handle);
    }

    private Map<String, Object> handle(Map<String, Object> request) throws Exception {
        String type = Json.str(request, "type");
        return switch (type) {
            case "CLIENT_TRANSFER" -> handleTransfer(
                    Json.str(request, "from"),
                    Json.str(request, "to"),
                    Json.longValue(request, "amount", 0),
                    Json.intValue(request, "participants", 2));
            case "CLIENT_BALANCE" -> handleBalance(Json.str(request, "account"));
            case "CLIENT_SET_BALANCE" -> handleSetBalance(Json.str(request, "account"), Json.longValue(request, "amount", 0));
            case "CLIENT_CLUSTER_DUMP" -> handleClusterDump();
            case "CLIENT_LIST_TX" -> handleListTransactions();
            case "CLIENT_TX_LOG" -> handleTxLog(Json.str(request, "participant"));
            case "CLIENT_SET_DELAY" -> handleSetDelay(Json.str(request, "component"), Json.str(request, "hook"), Json.longValue(request, "ms", 0));
            case "CLIENT_SET_DELAY_ALL" -> handleSetDelayAll(Json.str(request, "hook"), Json.longValue(request, "ms", 0));
            case "CLIENT_REPLAY_BENCH" -> handleReplayBench(Json.intValue(request, "limitLines", 0));
            case "CLIENT_ARM_CRASH" -> handleArmCrash(Json.str(request, "component"), Json.str(request, "hook"));
            case "CLIENT_SET_RPC_TIMEOUT" -> handleSetRpcTimeout(Json.intValue(request, "ms", 1500));
            case "CLIENT_SET_RPC_RETRIES" -> handleSetRpcRetries(Json.intValue(request, "retries", 3));
            case "CLIENT_LOCAL_CREDIT" -> handleLocalCredit(Json.str(request, "account"), Json.longValue(request, "amount", 0));
            case "STATUS_QUERY" -> handleStatusQuery(Json.str(request, "txId"));
            default -> Json.obj("ok", false, "error", "Unknown coordinator request type: " + type);
        };
    }

    private Map<String, Object> handleTransfer(String from, String to, long amount, int requestedParticipants) throws Exception {
        if (amount <= 0) {
            return Json.obj("ok", false, "error", "amount must be positive");
        }
        String txId = UUID.randomUUID().toString();
        List<PlanStep> plan = buildPlan(from, to, amount, requestedParticipants);
        appendBegin(txId, plan);
        plans.put(txId, plan);
        runHook("after-begin");

        List<Object> votes = new ArrayList<>();
        boolean allCommit = true;
        for (PlanStep step : plan) {
            try {
                Map<String, Object> response = sendWithRetries(step.node(), Json.obj(
                        "type", "PREPARE",
                        "txId", txId,
                        "operation", step.operation()));
                votes.add(Json.obj("node", step.node(), "vote", Json.str(response, "vote"), "reason", Json.str(response, "reason")));
                if (!"COMMIT".equals(Json.str(response, "vote"))) {
                    allCommit = false;
                }
            } catch (Exception e) {
                votes.add(Json.obj("node", step.node(), "vote", "TIMEOUT", "reason", e.getMessage()));
                allCommit = false;
            }
        }

        String decision = allCommit ? "COMMIT" : "ABORT";
        runHook("before-decision");
        appendDecision(txId, decision);
        decisions.put(txId, decision);
        runHook("after-decision");
        List<Object> phase2 = broadcastDecision(txId, plan, decision);
        return Json.obj(
                "ok", true,
                "txId", txId,
                "decision", decision,
                "votes", votes,
                "phase2", phase2);
    }

    private Map<String, Object> handleBalance(String account) throws IOException {
        String node = shardFor(account);
        Map<String, Object> response = sendOnce(node, Json.obj("type", "GET_BALANCE", "account", account));
        response.put("routedTo", node);
        return response;
    }

    private Map<String, Object> handleSetBalance(String account, long amount) throws IOException {
        String node = shardFor(account);
        Map<String, Object> response = sendOnce(node, Json.obj("type", "SET_BALANCE", "account", account, "amount", amount));
        response.put("routedTo", node);
        return response;
    }

    private Map<String, Object> handleLocalCredit(String account, long amount) throws IOException {
        String node = shardFor(account);
        Map<String, Object> response = sendOnce(node, Json.obj("type", "LOCAL_CREDIT", "account", account, "amount", amount));
        response.put("routedTo", node);
        return response;
    }

    private Map<String, Object> handleClusterDump() {
        Map<String, Object> dump = new LinkedHashMap<>();
        for (String node : shardOrder) {
            try {
                dump.put(node, sendOnce(node, Json.obj("type", "DUMP")));
            } catch (IOException e) {
                dump.put(node, Json.obj("ok", false, "error", e.getMessage()));
            }
        }
        return Json.obj("ok", true, "cluster", dump);
    }

    private Map<String, Object> handleListTransactions() {
        List<Object> list = plans.keySet().stream()
                .sorted(Comparator.naturalOrder())
                .map(txId -> Json.obj(
                        "txId", txId,
                        "state", decisions.containsKey(txId) ? "DECIDED_" + decisions.get(txId) : "BEGUN",
                        "plan", planToJson(plans.get(txId))))
                .map(object -> (Object) object)
                .toList();
        return Json.obj("ok", true, "transactions", list);
    }

    private Map<String, Object> handleTxLog(String participant) throws IOException {
        if (participant == null || participant.isBlank()) {
            return Json.obj("ok", true, "node", "coordinator", "content", String.join("\n", wal.readAll()));
        }
        return sendOnce(participant, Json.obj("type", "TX_LOG"));
    }

    private Map<String, Object> handleSetDelay(String component, String hook, long ms) throws IOException {
        if ("coord".equals(component) || "coordinator".equals(component)) {
            if (ms <= 0) {
                delays.remove(hook);
            } else {
                delays.put(hook, ms);
            }
            return Json.obj("ok", true, "component", "coord", "hook", hook, "ms", Math.max(ms, 0));
        }
        return sendOnce(component, Json.obj("type", "SET_DELAY", "hook", hook, "ms", ms));
    }

    private Map<String, Object> handleSetDelayAll(String hook, long ms) {
        List<Object> results = new ArrayList<>();
        for (String node : shardOrder) {
            try {
                results.add(sendOnce(node, Json.obj("type", "SET_DELAY", "hook", hook, "ms", ms)));
            } catch (IOException e) {
                results.add(Json.obj("ok", false, "node", node, "error", e.getMessage()));
            }
        }
        return Json.obj("ok", true, "hook", hook, "ms", Math.max(ms, 0), "nodes", results);
    }

    private Map<String, Object> handleReplayBench(int limitLines) {
        List<Object> results = new ArrayList<>();
        double maxReplayMs = 0.0;
        long totalWalLines = 0;
        for (String node : shardOrder) {
            try {
                Map<String, Object> response = sendOnce(node, Json.obj("type", "BENCH_REPLAY", "limitLines", limitLines));
                results.add(response);
                maxReplayMs = Math.max(maxReplayMs, number(response, "replayMs"));
                totalWalLines += Math.round(number(response, "walLines"));
            } catch (IOException e) {
                results.add(Json.obj("ok", false, "node", node, "error", e.getMessage()));
            }
        }
        return Json.obj(
                "ok", true,
                "maxReplayMs", maxReplayMs,
                "totalWalLines", totalWalLines,
                "limitLines", limitLines,
                "nodes", results);
    }

    private Map<String, Object> handleArmCrash(String component, String hook) throws IOException {
        if ("coord".equals(component) || "coordinator".equals(component)) {
            crashHook = hook;
            return Json.obj("ok", true, "component", "coord", "armed", hook);
        }
        return sendOnce(component, Json.obj("type", "ARM_CRASH", "hook", hook));
    }

    private Map<String, Object> handleSetRpcTimeout(int ms) {
        rpcTimeoutMs = Math.max(50, ms);
        return Json.obj("ok", true, "rpcTimeoutMs", rpcTimeoutMs);
    }

    private Map<String, Object> handleSetRpcRetries(int retries) {
        rpcRetries = Math.max(0, retries);
        return Json.obj("ok", true, "rpcRetries", rpcRetries);
    }

    private Map<String, Object> handleStatusQuery(String txId) {
        String decision = decisions.get(txId);
        return Json.obj("ok", true, "txId", txId, "decision", decision == null ? "UNKNOWN" : decision);
    }

    private void replayWal() throws IOException {
        for (String line : wal.readAll()) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> entry = Json.parseObject(line);
            String txId = Json.str(entry, "txId");
            String event = Json.str(entry, "event");
            if ("BEGIN".equals(event)) {
                plans.put(txId, parsePlan(Json.array(entry, "plan")));
            } else if ("DECISION".equals(event)) {
                decisions.put(txId, Json.str(entry, "decision"));
            }
        }
        System.out.printf("coordinator replay complete: plans=%d decisions=%d%n", plans.size(), decisions.size());
    }

    private void recoverTransactions() throws IOException {
        for (Map.Entry<String, List<PlanStep>> entry : new TreeMap<>(plans).entrySet()) {
            String txId = entry.getKey();
            List<PlanStep> plan = entry.getValue();
            String decision = decisions.get(txId);
            if (decision == null) {
                decision = "ABORT";
                appendDecision(txId, decision);
                decisions.put(txId, decision);
                System.out.printf("coordinator safe-aborts %s during recovery%n", txId);
            } else {
                System.out.printf("coordinator roll-forwards %s decision=%s%n", txId, decision);
            }
            broadcastDecision(txId, plan, decision);
        }
    }

    private List<PlanStep> buildPlan(String from, String to, long amount, int requestedParticipants) {
        String fromShard = shardFor(from);
        String toShard = shardFor(to);
        List<PlanStep> plan = new ArrayList<>();
        if (fromShard.equals(toShard)) {
            plan.add(new PlanStep(fromShard, Operation.transfer(from, to, amount)));
        } else {
            plan.add(new PlanStep(fromShard, Operation.debit(from, amount)));
            plan.add(new PlanStep(toShard, Operation.credit(to, amount)));
        }

        int targetParticipants = Math.max(1, Math.min(requestedParticipants, shardOrder.size()));
        for (String shard : shardOrder) {
            if (plan.stream().anyMatch(step -> step.node().equals(shard))) {
                continue;
            }
            if (plan.size() >= targetParticipants) {
                break;
            }
            plan.add(new PlanStep(shard, Operation.credit("__fee_" + shard, 0)));
        }
        return plan;
    }

    private String shardFor(String account) {
        int index = Math.floorMod(account.hashCode(), shardOrder.size());
        return shardOrder.get(index);
    }

    private Map<String, Object> sendOnce(String node, Map<String, Object> message) throws IOException {
        NodeAddress address = shards.get(node);
        if (address == null) {
            throw new IOException("Unknown shard: " + node);
        }
        return Rpc.request(address, message, rpcTimeoutMs);
    }

    private Map<String, Object> sendWithRetries(String node, Map<String, Object> message) throws IOException {
        IOException last = null;
        int attempts = Math.max(1, rpcRetries + 1);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return sendOnce(node, message);
            } catch (IOException e) {
                last = e;
                if (attempt < attempts) {
                    sleepQuietly(Math.min(250L * attempt, 1000L));
                }
            }
        }
        throw last == null ? new IOException("RPC failed") : last;
    }

    private List<Object> broadcastDecision(String txId, List<PlanStep> plan, String decision) {
        List<Object> results = new ArrayList<>();
        String type = "COMMIT".equals(decision) ? "COMMIT" : "ABORT";
        for (PlanStep step : plan) {
            try {
                Map<String, Object> response = sendWithRetries(step.node(), Json.obj("type", type, "txId", txId));
                results.add(Json.obj("node", step.node(), "delivered", true, "response", response));
            } catch (IOException e) {
                results.add(Json.obj("node", step.node(), "delivered", false, "error", e.getMessage()));
                System.err.printf("phase2 delivery failed tx=%s node=%s decision=%s: %s%n", txId, step.node(), decision, e.getMessage());
            }
        }
        return results;
    }

    private void appendBegin(String txId, List<PlanStep> plan) throws IOException {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", Instant.now().toString());
        entry.put("txId", txId);
        entry.put("event", "BEGIN");
        entry.put("plan", planToJson(plan));
        wal.append(Json.stringify(entry));
        System.out.printf("coordinator WAL %s BEGIN %s%n", txId, plan);
    }

    private void appendDecision(String txId, String decision) throws IOException {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", Instant.now().toString());
        entry.put("txId", txId);
        entry.put("event", "DECISION");
        entry.put("decision", decision);
        wal.append(Json.stringify(entry));
        System.out.printf("coordinator WAL %s DECISION %s%n", txId, decision);
    }

    private List<Object> planToJson(List<PlanStep> plan) {
        return plan.stream().map(step -> (Object) step.toJson()).toList();
    }

    @SuppressWarnings("unchecked")
    private List<PlanStep> parsePlan(List<Object> rawPlan) {
        List<PlanStep> plan = new ArrayList<>();
        for (Object item : rawPlan) {
            Map<String, Object> step = (Map<String, Object>) item;
            plan.add(new PlanStep(Json.str(step, "node"), Json.str(step, "op")));
        }
        return plan;
    }

    private void runHook(String hook) {
        long delayMs = delays.getOrDefault(hook, 0L);
        if (delayMs > 0) {
            sleepQuietly(delayMs);
        }
        if (hook.equals(crashHook)) {
            System.err.printf("coordinator crashes at hook %s%n", hook);
            System.exit(10);
        }
    }

    private static double number(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return 0.0;
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

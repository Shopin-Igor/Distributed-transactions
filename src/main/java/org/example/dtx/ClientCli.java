package org.example.dtx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.DoubleSummaryStatistics;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class ClientCli {
    private ClientCli() {
    }

    static void run(String[] args) throws Exception {
        String coord = "localhost:7000";
        int index = 0;
        while (index < args.length) {
            if ("--coord".equals(args[index])) {
                if (index + 1 >= args.length) {
                    throw new IllegalArgumentException("--coord requires host:port");
                }
                coord = args[index + 1];
                index += 2;
            } else {
                break;
            }
        }
        NodeAddress coordinator = NodeAddress.parseHostPort(coord);
        List<String> command = new ArrayList<>();
        for (int i = index; i < args.length; i++) {
            command.add(args[i]);
        }
        if (command.isEmpty()) {
            repl(coordinator);
            return;
        }
        execute(coordinator, command);
    }

    private static void repl(NodeAddress coordinator) throws Exception {
        System.out.println("2PC CLI connected to " + coordinator.host() + ":" + coordinator.port() + ". Type 'exit' to quit.");
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("> ");
                if (!scanner.hasNextLine()) {
                    return;
                }
                String line = scanner.nextLine().trim();
                if (line.isBlank()) {
                    continue;
                }
                if (line.equals("exit") || line.equals("quit")) {
                    return;
                }
                try {
                    execute(coordinator, split(line));
                } catch (Exception e) {
                    System.err.println("error: " + e.getMessage());
                }
            }
        }
    }

    private static void execute(NodeAddress coordinator, List<String> command) throws Exception {
        String name = command.get(0);
        if ("benchTransfers".equals(name)) {
            runTransferBenchmark(coordinator, command.subList(1, command.size()));
            return;
        }
        if ("benchLocalCredit".equals(name)) {
            runLocalCreditBenchmark(coordinator, command.subList(1, command.size()));
            return;
        }
        if ("benchWalReplay".equals(name)) {
            runWalReplayBenchmark(coordinator, command.subList(1, command.size()));
            return;
        }
        Map<String, Object> request = toRequest(command);
        Map<String, Object> response = Rpc.request(coordinator, request, 30_000);
        printResponse(response);
    }

    private static Map<String, Object> toRequest(List<String> command) {
        String name = command.get(0);
        return switch (name) {
            case "transfer" -> {
                requireSize(command, 4, "transfer <from> <to> <amount>");
                yield Json.obj(
                        "type", "CLIENT_TRANSFER",
                        "from", command.get(1),
                        "to", command.get(2),
                        "amount", Long.parseLong(command.get(3)),
                        "participants", 2);
            }
            case "balance" -> {
                requireSize(command, 2, "balance <account>");
                yield Json.obj("type", "CLIENT_BALANCE", "account", command.get(1));
            }
            case "clusterDump" -> Json.obj("type", "CLIENT_CLUSTER_DUMP");
            case "setBalance" -> {
                requireSize(command, 3, "setBalance <account> <amount>");
                yield Json.obj("type", "CLIENT_SET_BALANCE", "account", command.get(1), "amount", Long.parseLong(command.get(2)));
            }
            case "listTransactions" -> Json.obj("type", "CLIENT_LIST_TX");
            case "txLog" -> txLogRequest(command);
            case "crashCoordinator" -> {
                requireSize(command, 2, "crashCoordinator <hook>");
                yield Json.obj("type", "CLIENT_ARM_CRASH", "component", "coord", "hook", command.get(1));
            }
            case "crashParticipant" -> {
                requireSize(command, 3, "crashParticipant <nodeId> <hook>");
                yield Json.obj("type", "CLIENT_ARM_CRASH", "component", command.get(1), "hook", command.get(2));
            }
            case "setDelay" -> {
                requireSize(command, 4, "setDelay <coord|nodeId> <hook> <ms>");
                yield Json.obj("type", "CLIENT_SET_DELAY", "component", command.get(1), "hook", command.get(2), "ms", Long.parseLong(command.get(3)));
            }
            case "setRpcTimeout" -> {
                requireSize(command, 2, "setRpcTimeout <ms>");
                yield Json.obj("type", "CLIENT_SET_RPC_TIMEOUT", "ms", Integer.parseInt(command.get(1)));
            }
            case "setRpcRetries" -> {
                requireSize(command, 2, "setRpcRetries <n>");
                yield Json.obj("type", "CLIENT_SET_RPC_RETRIES", "retries", Integer.parseInt(command.get(1)));
            }
            case "localCredit" -> {
                requireSize(command, 3, "localCredit <account> <amount>");
                yield Json.obj("type", "CLIENT_LOCAL_CREDIT", "account", command.get(1), "amount", Long.parseLong(command.get(2)));
            }
            default -> throw new IllegalArgumentException("Unknown CLI command: " + name);
        };
    }

    private static Map<String, Object> txLogRequest(List<String> command) {
        if (command.size() == 1) {
            return Json.obj("type", "CLIENT_TX_LOG");
        }
        if (command.size() == 3 && "--participant".equals(command.get(1))) {
            return Json.obj("type", "CLIENT_TX_LOG", "participant", command.get(2));
        }
        throw new IllegalArgumentException("txLog [--participant <nodeId>]");
    }

    private static void runTransferBenchmark(NodeAddress coordinator, List<String> args) throws Exception {
        Map<String, String> flags = parseFlags(args);
        int threads = intFlag(flags, "threads", 8);
        int totalTx = intFlag(flags, "tx", 2000);
        int participants = intFlag(flags, "shards", 2);
        long amount = longFlag(flags, "amount", 100);
        int senderPool = intFlag(flags, "senderPool", Math.max(threads, 8));
        long initialBalance = longFlag(flags, "initialBalance", amount * totalTx * 2L);
        String scenario = flags.getOrDefault("scenario", "custom");
        String part = flags.getOrDefault("part", "manual");
        long debitDelayMs = longFlag(flags, "debitDelayMs", 0);

        initializeAccounts(coordinator, senderPool, initialBalance);
        BenchmarkResult result;
        try {
            if (debitDelayMs > 0) {
                Rpc.request(coordinator, Json.obj("type", "CLIENT_SET_DELAY_ALL", "hook", "after-prepared-debit", "ms", debitDelayMs), 30_000);
            }
            result = runConcurrent(totalTx, threads, index -> Json.obj(
                    "type", "CLIENT_TRANSFER",
                    "from", "sender" + (index % senderPool),
                    "to", "receiver" + index,
                    "amount", amount,
                    "participants", participants), coordinator);
        } finally {
            if (debitDelayMs > 0) {
                Rpc.request(coordinator, Json.obj("type", "CLIENT_SET_DELAY_ALL", "hook", "after-prepared-debit", "ms", 0), 30_000);
            }
        }

        String extra = "participants=%d;senderPool=%d;initialBalance=%d;debitDelayMs=%d"
                .formatted(participants, senderPool, initialBalance, debitDelayMs);
        String row = result.toCsv(part, scenario, participants, threads, totalTx, extra);
        System.out.println(BenchmarkResult.csvHeader());
        System.out.println(row);
        appendCsvIfRequested(flags, row);
    }

    private static void runLocalCreditBenchmark(NodeAddress coordinator, List<String> args) throws Exception {
        Map<String, String> flags = parseFlags(args);
        int threads = intFlag(flags, "threads", 8);
        int totalTx = intFlag(flags, "tx", 2000);
        long amount = longFlag(flags, "amount", 100);
        String scenario = flags.getOrDefault("scenario", "local-credit");
        String part = flags.getOrDefault("part", "D");

        BenchmarkResult result = runConcurrent(totalTx, threads, index -> Json.obj(
                "type", "CLIENT_LOCAL_CREDIT",
                "account", "localReceiver" + index,
                "amount", amount), coordinator);

        String row = result.toCsv(part, scenario, 1, threads, totalTx, "direct-participant-credit");
        System.out.println(BenchmarkResult.csvHeader());
        System.out.println(row);
        appendCsvIfRequested(flags, row);
    }

    private static void runWalReplayBenchmark(NodeAddress coordinator, List<String> args) throws Exception {
        Map<String, String> flags = parseFlags(args);
        String scenario = flags.getOrDefault("scenario", "wal-replay");
        String part = flags.getOrDefault("part", "E");
        int shardCount = intFlag(flags, "shards", 0);
        int totalTx = intFlag(flags, "tx", 0);
        int limitLines = intFlag(flags, "lines", 0);

        Map<String, Object> response = Rpc.request(coordinator, Json.obj(
                "type", "CLIENT_REPLAY_BENCH",
                "limitLines", limitLines), 60_000);
        double replayMs = number(response, "maxReplayMs");
        long walLines = Math.round(number(response, "totalWalLines"));
        BenchmarkResult result = new BenchmarkResult(0.0, replayMs, replayMs, replayMs, replayMs, 0.0, 0.0);
        String extra = "walReplayMs=%.2f;walLines=%d;limitLines=%d".formatted(replayMs, walLines, limitLines);
        String row = result.toCsv(part, scenario, shardCount, 1, totalTx, extra);
        System.out.println(BenchmarkResult.csvHeader());
        System.out.println(row);
        appendCsvIfRequested(flags, row);
    }

    private static void initializeAccounts(NodeAddress coordinator, int senderPool, long initialBalance) throws IOException {
        for (int i = 0; i < senderPool; i++) {
            Rpc.request(coordinator, Json.obj(
                    "type", "CLIENT_SET_BALANCE",
                    "account", "sender" + i,
                    "amount", initialBalance), 30_000);
        }
    }

    private interface RequestFactory {
        Map<String, Object> create(int index);
    }

    private static BenchmarkResult runConcurrent(int totalTx, int threads, RequestFactory factory, NodeAddress coordinator) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<OneTx>> futures = new ArrayList<>();
        long wallStart = System.nanoTime();
        for (int i = 0; i < totalTx; i++) {
            final int index = i;
            Callable<OneTx> task = () -> {
                start.await();
                long before = System.nanoTime();
                Map<String, Object> response;
                try {
                    response = Rpc.request(coordinator, factory.create(index), 60_000);
                } catch (IOException e) {
                    response = Json.obj("ok", false, "error", e.getMessage());
                }
                long elapsed = System.nanoTime() - before;
                String decision = Json.str(response, "decision", Json.str(response, "ok", "false").equals("true") ? "COMMIT" : "ABORT");
                return new OneTx(elapsed / 1_000_000.0, "COMMIT".equals(decision), "ABORT".equals(decision));
            };
            futures.add(pool.submit(task));
        }
        start.countDown();

        List<Double> latencies = new ArrayList<>();
        int commits = 0;
        int aborts = 0;
        for (Future<OneTx> future : futures) {
            OneTx tx = future.get();
            latencies.add(tx.latencyMs());
            if (tx.committed()) {
                commits++;
            }
            if (tx.aborted()) {
                aborts++;
            }
        }
        pool.shutdown();
        long wallElapsed = System.nanoTime() - wallStart;
        return BenchmarkResult.of(latencies, commits, aborts, wallElapsed / 1_000_000_000.0);
    }

    private record OneTx(double latencyMs, boolean committed, boolean aborted) {
    }

    private record BenchmarkResult(
            double throughput,
            double avg,
            double p50,
            double p95,
            double p99,
            double commitRatio,
            double abortRatio) {
        static BenchmarkResult of(List<Double> latencies, int commits, int aborts, double wallSeconds) {
            Collections.sort(latencies);
            DoubleSummaryStatistics stats = latencies.stream().mapToDouble(Double::doubleValue).summaryStatistics();
            int total = latencies.size();
            return new BenchmarkResult(
                    total / Math.max(wallSeconds, 0.001),
                    stats.getAverage(),
                    percentile(latencies, 0.50),
                    percentile(latencies, 0.95),
                    percentile(latencies, 0.99),
                    commits / (double) Math.max(total, 1),
                    aborts / (double) Math.max(total, 1));
        }

        static String csvHeader() {
            return "part,scenario,shardCount,threads,totalTx,throughputTxPerSec,avgMs,p50Ms,p95Ms,p99Ms,commitRatio,abortRatio,extraMetric";
        }

        String toCsv(String part, String scenario, int shardCount, int threads, int totalTx, String extra) {
            return "%s,%s,%d,%d,%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.4f,%.4f,%s".formatted(
                    part, scenario, shardCount, threads, totalTx, throughput, avg, p50, p95, p99, commitRatio, abortRatio, extra);
        }
    }

    private static double percentile(List<Double> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return 0.0;
        }
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
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

    private static Map<String, String> parseFlags(List<String> args) {
        Map<String, String> flags = new HashMap<>();
        for (int i = 0; i < args.size(); i++) {
            String token = args.get(i);
            if (!token.startsWith("--")) {
                throw new IllegalArgumentException("Expected flag, got " + token);
            }
            if (i + 1 >= args.size() || args.get(i + 1).startsWith("--")) {
                flags.put(token.substring(2), "true");
            } else {
                flags.put(token.substring(2), args.get(++i));
            }
        }
        return flags;
    }

    private static int intFlag(Map<String, String> flags, String key, int defaultValue) {
        return flags.containsKey(key) ? Integer.parseInt(flags.get(key)) : defaultValue;
    }

    private static long longFlag(Map<String, String> flags, String key, long defaultValue) {
        return flags.containsKey(key) ? Long.parseLong(flags.get(key)) : defaultValue;
    }

    private static void appendCsvIfRequested(Map<String, String> flags, String row) throws IOException {
        String csv = flags.get("csv");
        if (csv == null) {
            return;
        }
        Path path = Path.of(csv);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        boolean writeHeader = !Files.exists(path) || Files.size(path) == 0;
        String content = (writeHeader ? BenchmarkResult.csvHeader() + "\n" : "") + row + "\n";
        Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void printResponse(Map<String, Object> response) {
        Object content = response.get("content");
        if (content instanceof String text) {
            printJsonLines(text);
            return;
        }
        System.out.println(Json.pretty(response));
    }

    private static void printJsonLines(String text) {
        boolean printed = false;
        for (String line : text.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            if (printed) {
                System.out.println();
            }
            try {
                System.out.println(Json.pretty(Json.parse(line)));
            } catch (RuntimeException ignored) {
                System.out.println(line);
            }
            printed = true;
        }
    }

    private static void requireSize(List<String> command, int size, String usage) {
        if (command.size() != size) {
            throw new IllegalArgumentException(usage);
        }
    }

    private static List<String> split(String line) {
        String[] parts = line.trim().split("\\s+");
        List<String> result = new ArrayList<>();
        Collections.addAll(result, parts);
        return result;
    }
}

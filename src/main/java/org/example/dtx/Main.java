package org.example.dtx;

import java.nio.file.Path;
import java.util.Map;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help")) {
            usage();
            return;
        }
        String mode = args[0];
        Args parsed = Args.parse(args, 1);
        switch (mode) {
            case "coordinator" -> {
                int port = parsed.intOpt("port", 7000);
                Path dataDir = dataDir(parsed);
                Map<String, NodeAddress> shards = NodeAddress.parseShards(parsed.require("shards"));
                new CoordinatorServer(port, dataDir, shards).start();
            }
            case "participant" -> {
                String id = parsed.require("id");
                int port = parsed.intOpt("port", 7101);
                Path dataDir = dataDir(parsed);
                NodeAddress coordinator = parsed.has("coord") ? NodeAddress.parseHostPort(parsed.require("coord")) : null;
                new ParticipantServer(id, port, dataDir, coordinator).start();
            }
            case "cli" -> ClientCli.run(java.util.Arrays.copyOfRange(args, 1, args.length));
            default -> {
                System.err.println("Unknown mode: " + mode);
                usage();
                System.exit(2);
            }
        }
    }

    private static Path dataDir(Args parsed) {
        String raw = parsed.opt("data-dir", "data");
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("--data-dir must not be empty");
        }
        return Path.of(raw);
    }

    private static void usage() {
        System.out.println("""
                Usage:
                  java -cp out org.example.dtx.Main coordinator --port 7000 --data-dir data --shards shardA=localhost:7101,shardB=localhost:7102,shardC=localhost:7103
                  java -cp out org.example.dtx.Main participant --id shardA --port 7101 --data-dir data --coord localhost:7000
                  java -cp out org.example.dtx.Main cli --coord localhost:7000 transfer alice bob 100

                CLI commands:
                  transfer <from> <to> <amount>
                  balance <account>
                  clusterDump
                  setBalance <account> <amount>
                  listTransactions
                  txLog [--participant <nodeId>]
                  crashCoordinator <hook>
                  crashParticipant <nodeId> <hook>
                  setDelay <coord|nodeId> <hook> <ms>
                  setRpcTimeout <ms>
                  setRpcRetries <n>
                  benchTransfers --threads T --tx M [--shards N] [--amount A] [--senderPool P] [--initialBalance B] [--debitDelayMs MS] [--csv file]
                  benchWalReplay --part E --scenario wal-replay-N --shards N --tx M --lines L [--csv file]

                """);
    }
}

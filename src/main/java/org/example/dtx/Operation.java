package org.example.dtx;

import java.util.List;

record Operation(Kind kind, String account, String secondAccount, long amount) {
    enum Kind {
        DEBIT,
        CREDIT,
        TRANSFER,
        SET_BALANCE
    }

    static Operation parse(String raw) {
        List<String> parts = List.of(raw.trim().split("\\s+"));
        if (parts.size() < 3) {
            throw new IllegalArgumentException("Bad operation: " + raw);
        }
        Kind kind = Kind.valueOf(parts.get(0));
        return switch (kind) {
            case DEBIT, CREDIT, SET_BALANCE -> {
                if (parts.size() != 3) {
                    throw new IllegalArgumentException("Bad operation: " + raw);
                }
                yield new Operation(kind, parts.get(1), null, Long.parseLong(parts.get(2)));
            }
            case TRANSFER -> {
                if (parts.size() != 4) {
                    throw new IllegalArgumentException("Bad operation: " + raw);
                }
                yield new Operation(kind, parts.get(1), parts.get(2), Long.parseLong(parts.get(3)));
            }
        };
    }

    static String debit(String account, long amount) {
        return "DEBIT " + account + " " + amount;
    }

    static String credit(String account, long amount) {
        return "CREDIT " + account + " " + amount;
    }

    static String transfer(String from, String to, long amount) {
        return "TRANSFER " + from + " " + to + " " + amount;
    }

    static String setBalance(String account, long amount) {
        return "SET_BALANCE " + account + " " + amount;
    }
}

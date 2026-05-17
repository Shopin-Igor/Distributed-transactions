package org.example.dtx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class Args {
    private final Map<String, String> options = new HashMap<>();
    private final List<String> rest = new ArrayList<>();

    static Args parse(String[] args, int from) {
        Args parsed = new Args();
        for (int i = from; i < args.length; i++) {
            String token = args[i];
            if (token.startsWith("--")) {
                String key = token.substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    parsed.options.put(key, args[++i]);
                } else {
                    parsed.options.put(key, "true");
                }
            } else {
                parsed.rest.add(token);
            }
        }
        return parsed;
    }

    String require(String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing --" + key);
        }
        return value;
    }

    String opt(String key, String defaultValue) {
        return options.getOrDefault(key, defaultValue);
    }

    int intOpt(String key, int defaultValue) {
        String value = options.get(key);
        return value == null ? defaultValue : Integer.parseInt(value);
    }

    long longOpt(String key, long defaultValue) {
        String value = options.get(key);
        return value == null ? defaultValue : Long.parseLong(value);
    }

    boolean has(String key) {
        return options.containsKey(key);
    }

    List<String> rest() {
        return rest;
    }

    Map<String, String> options() {
        return options;
    }
}

package org.example.dtx;

import java.util.LinkedHashMap;
import java.util.Map;

record NodeAddress(String id, String host, int port) {
    static NodeAddress parse(String id, String hostPort) {
        String[] parts = hostPort.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Expected host:port, got " + hostPort);
        }
        return new NodeAddress(id, parts[0], Integer.parseInt(parts[1]));
    }

    static NodeAddress parseMapping(String mapping) {
        String[] parts = mapping.split("=", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Expected id=host:port, got " + mapping);
        }
        return parse(parts[0], parts[1]);
    }

    static Map<String, NodeAddress> parseShards(String raw) {
        Map<String, NodeAddress> shards = new LinkedHashMap<>();
        for (String item : raw.split(",")) {
            if (item.isBlank()) {
                continue;
            }
            NodeAddress address = parseMapping(item.trim());
            shards.put(address.id(), address);
        }
        if (shards.isEmpty()) {
            throw new IllegalArgumentException("At least one shard is required");
        }
        return shards;
    }

    static NodeAddress parseHostPort(String raw) {
        return parse("remote", raw);
    }
}

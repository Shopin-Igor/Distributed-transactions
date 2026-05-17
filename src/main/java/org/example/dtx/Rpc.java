package org.example.dtx;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Rpc {
    private Rpc() {
    }

    interface Handler {
        Map<String, Object> handle(Map<String, Object> request) throws Exception;
    }

    static Map<String, Object> request(NodeAddress address, Map<String, Object> message, int timeoutMs) throws IOException {
        return request(address.host(), address.port(), message, timeoutMs);
    }

    static Map<String, Object> request(String host, int port, Map<String, Object> message, int timeoutMs) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                writer.write(Json.stringify(message));
                writer.write('\n');
                writer.flush();
                String line = reader.readLine();
                if (line == null) {
                    throw new IOException("Connection closed without response");
                }
                return Json.parseObject(line);
            }
        }
    }

    static void serve(int port, Handler handler) throws IOException {
        ExecutorService workers = Executors.newCachedThreadPool();
        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("listening on port " + port);
            while (true) {
                Socket socket = server.accept();
                workers.submit(() -> handleSocket(socket, handler));
            }
        }
    }

    private static void handleSocket(Socket socket, Handler handler) {
        try (socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line == null || line.isBlank()) {
                return;
            }
            Map<String, Object> response;
            try {
                response = handler.handle(Json.parseObject(line));
            } catch (Throwable e) {
                response = Json.obj("ok", false, "error", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            writer.write(Json.stringify(response));
            writer.write('\n');
            writer.flush();
        } catch (IOException e) {
            System.err.println("rpc socket error: " + e.getMessage());
        }
    }
}

package wstool.test.components;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class LocalFileHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final Map<String, Path> files;
    private final Map<String, AtomicInteger> requestCounts = new ConcurrentHashMap<>();

    public LocalFileHttpServer(Map<String, Path> files) throws IOException {
        this.files = Map.copyOf(files);
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::serveFile);
        server.start();
    }

    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public int requestCount(String path) {
        return requestCounts.getOrDefault(path, new AtomicInteger()).get();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void serveFile(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requestCounts.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
        Path file = files.get(path);
        if (file == null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        byte[] content = Files.readAllBytes(file);
        exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
        exchange.sendResponseHeaders(200, content.length);
        try (var body = exchange.getResponseBody()) {
            body.write(content);
        }
    }
}

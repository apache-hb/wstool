package wstool.npm.importer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Persistent npm registry proxy for importer tests. */
public final class CachedNpmProxy implements AutoCloseable {
    private static final String UPSTREAM = "https://registry.npmjs.org";

    private final Path cache;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final HttpClient client =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
    private final AtomicInteger upstreamRequests = new AtomicInteger();
    private final AtomicInteger tarballRequests = new AtomicInteger();

    public CachedNpmProxy(Path cache) throws IOException {
        this.cache = cache;
        Files.createDirectories(cache);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    public int upstreamRequests() {
        return upstreamRequests.get();
    }

    public int tarballRequests() {
        return tarballRequests.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String requestPath = exchange.getRequestURI().getRawPath();
            if (requestPath.endsWith(".tgz")) {
                tarballRequests.incrementAndGet();
            }
            String query = exchange.getRequestURI().getRawQuery();
            String relative = requestPath.substring(1) + (query == null ? "" : "?" + query);
            Path bodyFile = cache.resolve(cacheKey(relative));
            Path statusFile = bodyFile.resolveSibling(bodyFile.getFileName() + ".http-status");
            CachedResponse response = load(bodyFile, statusFile, requestPath, query);
            byte[] body = response.body();
            if (response.contentType().contains("json")) {
                body =
                        new String(body, StandardCharsets.UTF_8)
                                .replace(UPSTREAM + "/", url())
                                .getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type", response.contentType());
            exchange.sendResponseHeaders(
                    response.status(),
                    "HEAD".equals(exchange.getRequestMethod()) ? -1 : body.length);
            if (!"HEAD".equals(exchange.getRequestMethod())) {
                exchange.getResponseBody().write(body);
            }
        } catch (Exception e) {
            e.printStackTrace();
            byte[] message = e.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(502, message.length);
            exchange.getResponseBody().write(message);
        } finally {
            exchange.close();
        }
    }

    private synchronized CachedResponse load(
            Path bodyFile, Path statusFile, String requestPath, String query) throws Exception {
        Path typeFile = bodyFile.resolveSibling(bodyFile.getFileName() + ".content-type");
        if (Files.isRegularFile(bodyFile)
                && Files.isRegularFile(statusFile)
                && Files.isRegularFile(typeFile)) {
            return new CachedResponse(
                    Integer.parseInt(Files.readString(statusFile)),
                    Files.readAllBytes(bodyFile),
                    Files.readString(typeFile));
        }

        Files.createDirectories(bodyFile.getParent());
        String upstreamUrl = UPSTREAM + requestPath + (query == null ? "" : "?" + query);
        upstreamRequests.incrementAndGet();
        HttpResponse<byte[]> upstream =
                client.send(
                        HttpRequest.newBuilder(URI.create(upstreamUrl))
                                .timeout(Duration.ofMinutes(3))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
        String contentType =
                upstream.headers().firstValue("Content-Type").orElse("application/octet-stream");
        Path bodyTemp = Files.createTempFile(bodyFile.getParent(), "npm-proxy-", ".part");
        Path statusTemp = Files.createTempFile(bodyFile.getParent(), "npm-status-", ".part");
        Path typeTemp = Files.createTempFile(bodyFile.getParent(), "npm-content-type-", ".part");
        try {
            Files.write(bodyTemp, upstream.body());
            Files.writeString(statusTemp, Integer.toString(upstream.statusCode()));
            Files.writeString(typeTemp, contentType);
            Files.move(bodyTemp, bodyFile, StandardCopyOption.REPLACE_EXISTING);
            Files.move(statusTemp, statusFile, StandardCopyOption.REPLACE_EXISTING);
            Files.move(typeTemp, typeFile, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(bodyTemp);
            Files.deleteIfExists(statusTemp);
            Files.deleteIfExists(typeTemp);
        }
        return new CachedResponse(upstream.statusCode(), upstream.body(), contentType);
    }

    private String cacheKey(String request) throws Exception {
        byte[] digest =
                MessageDigest.getInstance("SHA-256")
                        .digest(request.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private record CachedResponse(int status, byte[] body, String contentType) {}
}

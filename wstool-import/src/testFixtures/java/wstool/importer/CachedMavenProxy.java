package wstool.importer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Local Maven Central proxy that persists successful and missing responses between test runs. */
public final class CachedMavenProxy implements AutoCloseable {
    private static final String UPSTREAM = "https://repo.maven.apache.org/maven2";

    private final Path cache;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final HttpClient client =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
    private final AtomicInteger upstreamRequests = new AtomicInteger();

    public CachedMavenProxy(Path cache) throws IOException {
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

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getRawPath();
            Path bodyFile = cache.resolve(path.substring(1)).normalize();
            if (!bodyFile.startsWith(cache)) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            Path statusFile = bodyFile.resolveSibling(bodyFile.getFileName() + ".http-status");
            CachedResponse response = load(bodyFile, statusFile);
            byte[] body = response.body();
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(
                    response.status(),
                    "HEAD".equals(exchange.getRequestMethod()) ? -1 : body.length);
            if (!"HEAD".equals(exchange.getRequestMethod())) {
                exchange.getResponseBody().write(body);
            }
        } catch (Exception e) {
            byte[] message = e.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(502, message.length);
            exchange.getResponseBody().write(message);
        } finally {
            exchange.close();
        }
    }

    private synchronized CachedResponse load(Path bodyFile, Path statusFile) throws Exception {
        if (Files.isRegularFile(bodyFile) && Files.isRegularFile(statusFile)) {
            return new CachedResponse(
                    Integer.parseInt(Files.readString(statusFile)), Files.readAllBytes(bodyFile));
        }

        Files.createDirectories(bodyFile.getParent());
        upstreamRequests.incrementAndGet();
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(UPSTREAM + "/" + cacheRelative(bodyFile)))
                        .timeout(Duration.ofMinutes(3))
                        .GET()
                        .build();
        HttpResponse<byte[]> upstream =
                client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        Path bodyTemp = Files.createTempFile(bodyFile.getParent(), "maven-proxy-", ".part");
        Path statusTemp = Files.createTempFile(bodyFile.getParent(), "maven-status-", ".part");
        try {
            Files.write(bodyTemp, upstream.body());
            Files.writeString(statusTemp, Integer.toString(upstream.statusCode()));
            Files.move(bodyTemp, bodyFile, StandardCopyOption.REPLACE_EXISTING);
            Files.move(statusTemp, statusFile, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(bodyTemp);
            Files.deleteIfExists(statusTemp);
        }
        return new CachedResponse(upstream.statusCode(), upstream.body());
    }

    private String cacheRelative(Path file) {
        return cache.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private record CachedResponse(int status, byte[] body) {}
}

package example.helidon;

import io.helidon.webserver.WebServer;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        WebServer.builder()
                .port(port)
                .routing(
                        routes ->
                                routes.get(
                                                "/api/hello",
                                                (request, response) ->
                                                        response.send("{\"message\":\"hello\"}"))
                                        .get(
                                                "/api/status",
                                                (request, response) ->
                                                        response.send("{\"status\":\"ok\"}")))
                .build()
                .start();
    }
}

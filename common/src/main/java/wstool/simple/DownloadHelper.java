package wstool.simple;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import wstool.simple.download.DownloadInput;

public class DownloadHelper {
    public void download(DownloadInput input) throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder(input.remote()).build();
        client.send(request, HttpResponse.BodyHandlers.ofFile(input.path()));
    }
}

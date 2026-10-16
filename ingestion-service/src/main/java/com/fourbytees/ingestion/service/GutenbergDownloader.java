package com.fourbytees.ingestion.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class GutenbergDownloader {
    private final HttpClient client;

    public GutenbergDownloader() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String[] downloadAndSplit(int bookId) throws IOException, InterruptedException {
        String url = String.format("https://www.gutenberg.org/cache/epub/%d/pg%d.txt", bookId, bookId);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                throw new RuntimeException("FAILED_404");
            }

            if (response.statusCode() == 200) {
                return splitHeaderAndBody(response.body());
            }

            if (attempt == maxRetries) {
                throw new IOException("HTTP Error " + response.statusCode() + " tras " + maxRetries + " intentos.");
            }
            Thread.sleep(1000 * attempt);
        }
        throw new IOException("Error desconocido descargando libro " + bookId);
    }

    private String[] splitHeaderAndBody(String fullText) {
        String marker = "*** START OF";
        int markerIndex = fullText.indexOf(marker);

        if (markerIndex != -1) {
            int endOfLineIndex = fullText.indexOf("\n", markerIndex);
            String header = fullText.substring(0, markerIndex).trim();
            String body = (endOfLineIndex != -1) ? fullText.substring(endOfLineIndex).trim() : fullText.substring(markerIndex).trim();
            return new String[]{header, body};
        }
        return new String[]{"HEADER_UNKNOWN", fullText};
    }
}
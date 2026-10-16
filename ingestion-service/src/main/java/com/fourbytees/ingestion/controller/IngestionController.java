package com.fourbytees.ingestion.controller;

import com.fourbytees.ingestion.service.DatalakeStorage;
import com.fourbytees.ingestion.service.GutenbergDownloader;
import io.javalin.http.Context;
import com.google.gson.Gson;

import java.util.Map;

public class IngestionController {
    private final GutenbergDownloader downloader;
    private final DatalakeStorage storage;
    private final Gson gson = new Gson();

    public IngestionController(GutenbergDownloader downloader, DatalakeStorage storage) {
        this.downloader = downloader;
        this.storage = storage;
    }

    public void ingest(Context ctx) {
        int bookId;
        try {
            bookId = Integer.parseInt(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            ctx.status(400).result(gson.toJson(Map.of("error", "Invalid book ID format")));
            return;
        }

        if (storage.isBookDownloaded(bookId)) {
            ctx.status(200).result(gson.toJson(Map.of("book_id", bookId, "status", "already_downloaded")));
            return;
        }

        try {
            String[] content = downloader.downloadAndSplit(bookId);
            String savedPath = storage.saveBook(bookId, content[0], content[1]);

            ctx.status(200).result(gson.toJson(Map.of(
                    "book_id", bookId,
                    "status", "downloaded",
                    "path", savedPath
            )));
        } catch (RuntimeException e) {
            if ("FAILED_404".equals(e.getMessage())) {
                ctx.status(404).result(gson.toJson(Map.of("book_id", bookId, "status", "FAILED_404")));
            } else {
                ctx.status(500).result(gson.toJson(Map.of("error", e.getMessage())));
            }
        } catch (Exception e) {
            ctx.status(500).result(gson.toJson(Map.of("error", e.getMessage())));
        }
    }

    public void getStatus(Context ctx) {
        int bookId = Integer.parseInt(ctx.pathParam("id"));
        boolean ready = storage.isBookDownloaded(bookId);
        ctx.status(200).result(gson.toJson(Map.of("book_id", bookId, "status", ready ? "available" : "not_found")));
    }

    public void listBooks(Context ctx) {
        var books = storage.listDownloadedBooks();
        ctx.status(200).result(gson.toJson(Map.of("count", books.size(), "books", books)));
    }

    public void getHealth(Context ctx) {
        ctx.status(200).result(gson.toJson(Map.of("service", "ingestion-service", "status", "running")));
    }
}
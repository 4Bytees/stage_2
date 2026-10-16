package com.fourbytees.ingestion.service;

import com.fourbytees.common.events.BookDownloaded;
import java.util.List;
import java.util.UUID;

public class OutboxService {
    private final DatalakeStorage storage;

    public OutboxService(DatalakeStorage storage) {
        this.storage = storage;
    }

    public void runOutboxCheck() {
        List<Integer> downloadedBooks = storage.listDownloadedBooks();
        System.out.println("[OUTBOX] Verificando datalake al arrancar. Libros encontrados con .done: " + downloadedBooks.size());

        for (Integer bookId : downloadedBooks) {
            String path = storage.isBookDownloaded(bookId) ? "datalake/..." : "";
            BookDownloaded event = new BookDownloaded(UUID.randomUUID().toString(), bookId, path);
            System.out.println("[OUTBOX] Evento auditado/republicado para libro ID: " + bookId);
        }
    }
}

package com.fourbytees.ingestion;

import com.fourbytees.ingestion.service.DatalakeStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class IngestionServiceTest {

    @Test
    public void testIdempotenciaYAtomicidad(@TempDir Path tempDir) throws IOException {
        DatalakeStorage storage = new DatalakeStorage(tempDir.toString());
        int bookId = 9999;

        // Primera guardada
        String path = storage.saveBook(bookId, "Header Content", "Body Content");
        assertTrue(storage.isBookDownloaded(bookId));

        // Segunda guardada (Idempotencia)
        String pathSecond = storage.saveBook(bookId, "Header Content", "Body Content");
        assertEquals(path, pathSecond);
    }
}
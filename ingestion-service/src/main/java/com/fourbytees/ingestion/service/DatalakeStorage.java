package com.fourbytees.ingestion.service;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Datalake storage manager.
 *
 * <p><b>Directory layout:</b> {@code datalake/<YYYYMMDD>/<HH>/}
 * <p><b>Artifacts per book:</b>
 * <ul>
 *   <li>{@code <id>.header.txt}: Metadata extracted from the Gutenberg header.</li>
 *   <li>{@code <id>.tmp} &rarr; {@code <id>.body.txt}: Book body moved atomically.</li>
 *   <li>{@code <id>.done}: Download completion and integrity marker; a book is visible only if {@code .done} exists.</li>
 *   <li>{@code <id>.published}: Outbox confirmation marker; indicates that the {@code BookDownloaded} event
 *       was emitted to ActiveMQ and the {@code DOWNLOADED} state was recorded in Hazelcast.</li>
 * </ul>
 */
public class DatalakeStorage {
    private final Path baseDir;

    public DatalakeStorage(String basePath) {
        this.baseDir = Paths.get(basePath);
    }

    public synchronized String saveBook(int bookId, String header, String body) throws IOException {
        LocalDateTime now = LocalDateTime.now();
        String datePath = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String hourPath = now.format(DateTimeFormatter.ofPattern("HH"));

        Path targetDir = baseDir.resolve(datePath).resolve(hourPath);
        Files.createDirectories(targetDir);

        Path headerFile = targetDir.resolve(bookId + ".header.txt");
        Path bodyFile = targetDir.resolve(bookId + ".body.txt");
        Path tempFile = targetDir.resolve(bookId + ".tmp");
        Path doneFile = targetDir.resolve(bookId + ".done");

        if (Files.exists(doneFile)) {
            return targetDir.resolve(String.valueOf(bookId)).toString();
        }

        Files.writeString(headerFile, header, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(tempFile, body, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tempFile, bodyFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        Files.writeString(doneFile, "READY", StandardOpenOption.CREATE);

        return targetDir.resolve(String.valueOf(bookId)).toString();
    }

    public boolean isBookDownloaded(int bookId) {
        if (!Files.exists(baseDir)) return false;
        try (Stream<Path> stream = Files.walk(baseDir, 4)) {
            return stream.anyMatch(p -> p.getFileName().toString().equals(bookId + ".done"));
        } catch (IOException e) {
            return false;
        }
    }

    public Path findBookDirectory(int bookId) {
        if (!Files.exists(baseDir)) return null;
        try (Stream<Path> stream = Files.walk(baseDir, 4)) {
            return stream.filter(Files::isDirectory)
                    .filter(dir -> Files.exists(dir.resolve(bookId + ".done")))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    public boolean isBookPublished(int bookId) {
        Path dir = findBookDirectory(bookId);
        if (dir == null) return false;
        return Files.exists(dir.resolve(bookId + ".published"));
    }

    public void markBookPublished(int bookId) throws IOException {
        Path dir = findBookDirectory(bookId);
        if (dir != null) {
            Path publishedMarker = dir.resolve(bookId + ".published");
            Files.writeString(publishedMarker, "PUBLISHED", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    public String getBookDirectoryPath(int bookId) {
        Path dir = findBookDirectory(bookId);
        return dir != null ? dir.resolve(String.valueOf(bookId)).toString() : null;
    }

    public List<Integer> listDownloadedBooks() {
        List<Integer> books = new ArrayList<>();
        if (!Files.exists(baseDir)) return books;
        try (Stream<Path> stream = Files.walk(baseDir, 4)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".done"))
                  .forEach(p -> {
                      String fileName = p.getFileName().toString();
                      int id = Integer.parseInt(fileName.replace(".done", ""));
                      books.add(id);
                  });
        } catch (Exception ignored) {}
        return books;
    }
}

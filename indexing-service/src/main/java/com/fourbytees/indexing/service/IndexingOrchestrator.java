package com.fourbytees.indexing.service;

import com.fourbytees.common.constants.SystemConstants;
import com.fourbytees.common.events.BookIndexed;
import com.fourbytees.indexing.config.IndexingConfig;
import com.fourbytees.indexing.parser.BodyTokenizer;
import com.fourbytees.indexing.parser.HeaderParser;
import com.fourbytees.indexing.repository.SqliteRepository;
import com.google.gson.Gson;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.cp.lock.FencedLock;
import com.hazelcast.map.IMap;
import javax.jms.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

public class IndexingOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(IndexingOrchestrator.class);

    private final IndexingConfig config;
    private final SqliteRepository repository;
    private final HazelcastInstance hazelcast;
    private final Connection jmsConnection;
    private final Gson gson = new Gson();

    public IndexingOrchestrator(IndexingConfig config, SqliteRepository repository, 
                                HazelcastInstance hazelcast, Connection jmsConnection) {
        this.config = config;
        this.repository = repository;
        this.hazelcast = hazelcast;
        this.jmsConnection = jmsConnection;
    }

    public boolean indexBook(int bookId, boolean force) throws Exception {
        IMap<Integer, String> bookState = hazelcast.getMap(SystemConstants.MAP_BOOK_STATE);

        if (!force) {
            boolean claimed = bookState.replace(bookId, SystemConstants.STATE_DOWNLOADED, SystemConstants.STATE_INDEXING);
            if (!claimed) {
                log.info("Libro {} ya reclamado o indexado por otra réplica.", bookId);
                return false;
            }
        } else {
            bookState.put(bookId, SystemConstants.STATE_INDEXING);
        }

        Path bookDir = findBookDirectory(bookId);
        if (bookDir == null) {
            throw new NoSuchFileException("No se encontró directorio con .done para el libro " + bookId);
        }

        Path headerFile = bookDir.resolve(bookId + ".header.txt");
        Path bodyFile = bookDir.resolve(bookId + ".body.txt");

        if (!Files.exists(headerFile) || !Files.exists(bodyFile)) {
            throw new NoSuchFileException("Faltan archivos .header.txt o .body.txt del libro " + bookId);
        }

        HeaderParser.BookMetadata metadata = HeaderParser.parse(bookId, headerFile);
        BodyTokenizer.TokenizedResult tokenized = BodyTokenizer.tokenize(bodyFile);

        FencedLock lock = hazelcast.getCPSubsystem().getLock(SystemConstants.LOCK_INDEX_WRITER);
        long newVersion;
        lock.lock();
        try {
            repository.saveBookIndex(metadata, tokenized.postings());
            newVersion = hazelcast.getCPSubsystem()
                    .getAtomicLong(SystemConstants.ATOMIC_INDEX_VERSION)
                    .incrementAndGet();
            bookState.put(bookId, SystemConstants.STATE_INDEXED);
        } finally {
            lock.unlock();
        }

        publishBookIndexed(new BookIndexed(
                UUID.randomUUID().toString(),
                bookId,
                newVersion,
                Instant.now().toString()
        ));

        log.info("Libro {} indexado. Versión publicada: {}", bookId, newVersion);
        return true;
    }

    public Path findBookDirectory(int bookId) {
        if (!Files.exists(config.datalakePath)) return null;
        try (Stream<Path> stream = Files.walk(config.datalakePath, 4)) {
            return stream.filter(Files::isDirectory)
                    .filter(dir -> Files.exists(dir.resolve(bookId + ".done")))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            log.error("Error explorando datalake", e);
            return null;
        }
    }

    private void publishBookIndexed(BookIndexed event) {
        try (Session session = jmsConnection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
            Destination destination = session.createQueue(SystemConstants.QUEUE_BOOKS_INDEXED);
            try (MessageProducer producer = session.createProducer(destination)) {
                TextMessage msg = session.createTextMessage(gson.toJson(event));
                producer.send(msg);
            }
        } catch (JMSException e) {
            log.error("Error enviando evento BookIndexed para libro {}", event.getBook_id(), e);
        }
    }
}
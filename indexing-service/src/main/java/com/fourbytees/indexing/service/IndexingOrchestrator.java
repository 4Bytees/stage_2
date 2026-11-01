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

import javax.jms.Connection;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IndexingOrchestrator implements IndexRebuilder.BookSource {
    private static final Logger log =
            LoggerFactory.getLogger(IndexingOrchestrator.class);

    private final IndexingConfig config;
    private final SqliteRepository repository;
    private final HazelcastInstance hazelcast;
    private final Connection jmsConnection;
    private final Gson gson = new Gson();
    private final String claimantId;

    public IndexingOrchestrator(
            IndexingConfig config,
            SqliteRepository repository,
            HazelcastInstance hazelcast,
            Connection jmsConnection
    ) {
        this(
                config,
                repository,
                hazelcast,
                jmsConnection,
                UUID.randomUUID().toString()
        );
    }

    IndexingOrchestrator(
            IndexingConfig config,
            SqliteRepository repository,
            HazelcastInstance hazelcast,
            Connection jmsConnection,
            String claimantId
    ) {
        this.config = config;
        this.repository = repository;
        this.hazelcast = hazelcast;
        this.jmsConnection = jmsConnection;
        this.claimantId = claimantId;
    }

    /**
     * Indexes a book from an HTTP update or rebuild operation.
     *
     * <p>Forced operations bypass the distributed message claim because they
     * are explicitly requested by an operational endpoint.</p>
     */
    public boolean indexBook(int bookId, boolean force) throws Exception {
        if (!force) {
            return indexDownloadedBook(bookId) == IndexingResult.INDEXED;
        }

        IMap<Integer, String> bookState =
                hazelcast.getMap(SystemConstants.MAP_BOOK_STATE);

        bookState.put(bookId, SystemConstants.STATE_INDEXING);

        processBook(bookId, bookState, null);

        return true;
    }

    /**
     * Attempts to claim and index a downloaded book delivered through JMS.
     */
    public IndexingResult indexDownloadedBook(int bookId) throws Exception {
        IMap<Integer, String> bookState =
                hazelcast.getMap(SystemConstants.MAP_BOOK_STATE);

        IMap<Integer, BookClaim> bookClaims =
                hazelcast.getMap(SystemConstants.MAP_BOOK_CLAIMS);

        IndexingResult claimResult =
                claimBook(bookId, bookState, bookClaims);

        if (claimResult != IndexingResult.INDEXED) {
            return claimResult;
        }

        try {
            processBook(bookId, bookState, bookClaims);
            return IndexingResult.INDEXED;
        } catch (Exception exception) {
            removeOwnClaim(bookId, bookClaims);
            throw exception;
        }
    }

    /**
     * Atomically evaluates and updates the state and claim for one book.
     */
    private IndexingResult claimBook(
            int bookId,
            IMap<Integer, String> bookState,
            IMap<Integer, BookClaim> bookClaims
    ) {
        bookState.lock(bookId);

        try {
            String currentState = bookState.get(bookId);

            if (SystemConstants.STATE_INDEXED.equals(currentState)) {
                log.info(
                        "Book {} is already indexed. The duplicate delivery can be acknowledged.",
                        bookId
                );
                return IndexingResult.ALREADY_INDEXED;
            }

            long now = System.currentTimeMillis();

            if (SystemConstants.STATE_DOWNLOADED.equals(currentState)) {
                bookState.put(bookId, SystemConstants.STATE_INDEXING);
                bookClaims.put(bookId, new BookClaim(claimantId, now));

                log.info(
                        "Book {} claimed by indexing instance {}.",
                        bookId,
                        claimantId
                );

                return IndexingResult.INDEXED;
            }

            if (SystemConstants.STATE_INDEXING.equals(currentState)) {
                BookClaim currentClaim = bookClaims.get(bookId);

                if (isRecentClaim(currentClaim, now)) {
                    log.info(
                            "Book {} has a recent claim owned by {}. Redelivery is required.",
                            bookId,
                            currentClaim.claimantId()
                    );
                    return IndexingResult.RETRY_LATER;
                }

                bookClaims.put(bookId, new BookClaim(claimantId, now));

                log.warn(
                        "Taking over expired or missing claim for book {}. New claimant: {}.",
                        bookId,
                        claimantId
                );

                return IndexingResult.INDEXED;
            }

            log.warn(
                    "Book {} cannot be claimed from state {}. Redelivery is required.",
                    bookId,
                    currentState
            );

            return IndexingResult.RETRY_LATER;
        } finally {
            bookState.unlock(bookId);
        }
    }

    private boolean isRecentClaim(BookClaim claim, long now) {
        if (claim == null) {
            return false;
        }

        long ageMs = Math.max(0L, now - claim.claimedAtMs());
        return ageMs < config.indexingTimeoutMs;
    }

    private void processBook(
            int bookId,
            IMap<Integer, String> bookState,
            IMap<Integer, BookClaim> bookClaims
    ) throws Exception {
        ParsedBook parsed = parseBook(bookId);
        HeaderParser.BookMetadata metadata = parsed.metadata();

        FencedLock lock =
                hazelcast.getCPSubsystem()
                        .getLock(SystemConstants.LOCK_INDEX_WRITER);

        long newVersion;

        lock.lock();

        try {
            repository.saveBookIndex(
                    metadata,
                    parsed.postings()
            );

            newVersion = hazelcast.getCPSubsystem()
                    .getAtomicLong(SystemConstants.ATOMIC_INDEX_VERSION)
                    .incrementAndGet();

            bookState.put(
                    bookId,
                    SystemConstants.STATE_INDEXED
            );

            if (bookClaims != null) {
                removeOwnClaim(bookId, bookClaims);
            }
        } finally {
            lock.unlock();
        }

        publishBookIndexed(
                new BookIndexed(
                        UUID.randomUUID().toString(),
                        bookId,
                        newVersion,
                        Instant.now().toString()
                )
        );

        log.info(
                "Book {} indexed. Published version: {}.",
                bookId,
                newVersion
        );
    }

    /**
     * Reads and tokenizes one book from the datalake without touching any index or coordination state.
     */
    @Override
    public ParsedBook parseBook(int bookId) throws Exception {
        Path bookDirectory = findBookDirectory(bookId);

        if (bookDirectory == null) {
            throw new NoSuchFileException(
                    "No directory with a .done marker was found for book " + bookId
            );
        }

        Path headerFile =
                bookDirectory.resolve(bookId + ".header.txt");

        Path bodyFile =
                bookDirectory.resolve(bookId + ".body.txt");

        if (!Files.exists(headerFile) || !Files.exists(bodyFile)) {
            throw new NoSuchFileException(
                    "Missing .header.txt or .body.txt file for book " + bookId
            );
        }

        HeaderParser.BookMetadata metadata =
                HeaderParser.parse(bookId, headerFile);

        BodyTokenizer.TokenizedResult tokenized =
                BodyTokenizer.tokenize(bodyFile);

        return new ParsedBook(metadata, tokenized.postings());
    }

    /**
     * Lists the identifiers of all books that have a {@code .done} marker in the datalake.
     */
    @Override
    public List<Integer> listReadyBookIds() throws IOException {
        if (!Files.exists(config.datalakePath)) {
            return List.of();
        }

        try (Stream<Path> stream = Files.walk(config.datalakePath, 4)) {
            return stream
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".done"))
                    .map(name -> Integer.parseInt(name.substring(0, name.length() - ".done".length())))
                    .distinct()
                    .sorted()
                    .toList();
        }
    }

    private void removeOwnClaim(
            int bookId,
            IMap<Integer, BookClaim> bookClaims
    ) {
        BookClaim currentClaim = bookClaims.get(bookId);

        if (
                currentClaim != null
                        && claimantId.equals(currentClaim.claimantId())
        ) {
            bookClaims.remove(bookId, currentClaim);
        }
    }

    public Path findBookDirectory(int bookId) {
        if (!Files.exists(config.datalakePath)) {
            return null;
        }

        try (Stream<Path> stream =
                     Files.walk(config.datalakePath, 4)) {

            return stream
                    .filter(Files::isDirectory)
                    .filter(directory ->
                            Files.exists(
                                    directory.resolve(bookId + ".done")
                            )
                    )
                    .findFirst()
                    .orElse(null);
        } catch (IOException exception) {
            log.error(
                    "Failed to scan the datalake for book {}.",
                    bookId,
                    exception
            );
            return null;
        }
    }

    private void publishBookIndexed(BookIndexed event) {
        try (Session session =
                     jmsConnection.createSession(
                             false,
                             Session.AUTO_ACKNOWLEDGE
                     )) {

            Destination destination =
                    session.createQueue(
                            SystemConstants.QUEUE_BOOKS_INDEXED
                    );

            try (MessageProducer producer =
                         session.createProducer(destination)) {

                TextMessage message =
                        session.createTextMessage(
                                gson.toJson(event)
                        );

                producer.send(message);
            }
        } catch (JMSException exception) {
            log.error(
                    "Failed to publish BookIndexed for book {}.",
                    event.getBook_id(),
                    exception
            );
        }
    }
}

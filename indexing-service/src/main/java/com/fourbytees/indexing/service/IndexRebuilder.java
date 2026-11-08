package com.fourbytees.indexing.service;

import com.fourbytees.common.constants.SystemConstants;
import com.fourbytees.indexing.repository.SqliteRepository;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.cp.lock.FencedLock;
import com.hazelcast.map.IMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rebuilds the whole index without exposing partial state.
 *
 * <p>The complete replacement index is built in a separate temporary SQLite database located next
 * to the active one. The active database is not touched until every book has been processed
 * successfully. The replacement is then swapped in with one SQLite write transaction (see
 * {@link SqliteRepository#replaceContentsFrom(String)}), so the active database file, its WAL/SHM
 * files and the read-only connections of the search service are never replaced or invalidated.</p>
 *
 * <p>Only one rebuild can run at a time across all indexing replicas. Additional requests are
 * rejected with {@link RebuildInProgressException}.</p>
 */
public class IndexRebuilder {

    private static final Logger log = LoggerFactory.getLogger(IndexRebuilder.class);

    /** Name of the distributed lock that serializes rebuilds across replicas. */
    static final String LOCK_INDEX_REBUILD = "lock:index-rebuild";

    /**
     * Source of the books to index.
     */
    public interface BookSource {
        List<Integer> listReadyBookIds() throws IOException;

        ParsedBook parseBook(int bookId) throws Exception;
    }

    public record RebuildResult(int booksProcessed, double elapsedSeconds) {
    }

    /** Thrown when another rebuild is already running. */
    public static class RebuildInProgressException extends RuntimeException {
        public RebuildInProgressException() {
            super("An index rebuild is already in progress");
        }
    }

    /** Thrown when a rebuild fails. The previous index is still active. */
    public static class RebuildFailedException extends Exception {
        public RebuildFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final SqliteRepository repository;
    private final HazelcastInstance hazelcast;
    private final BookSource source;
    private final int workerThreads;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public IndexRebuilder(
            SqliteRepository repository,
            HazelcastInstance hazelcast,
            BookSource source,
            int workerThreads
    ) {
        this.repository = repository;
        this.hazelcast = hazelcast;
        this.source = source;
        this.workerThreads = Math.max(1, workerThreads);

        AtomicInteger threadCounter = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(this.workerThreads, runnable -> {
            Thread thread = new Thread(runnable, "index-rebuild-" + threadCounter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public RebuildResult rebuild() throws RebuildFailedException {
        if (!running.compareAndSet(false, true)) {
            throw new RebuildInProgressException();
        }

        FencedLock rebuildLock = hazelcast.getCPSubsystem().getLock(LOCK_INDEX_REBUILD);
        boolean rebuildLockHeld = false;
        String temporaryDb = null;

        try {
            rebuildLockHeld = rebuildLock.tryLock();
            if (!rebuildLockHeld) {
                throw new RebuildInProgressException();
            }

            long startNanos = System.nanoTime();
            temporaryDb = repository.getDbFilePath() + ".rebuild-" + UUID.randomUUID() + ".tmp";
            SqliteRepository replacement = new SqliteRepository(temporaryDb);

            Set<Integer> indexed = new HashSet<>();
            indexInto(replacement, source.listReadyBookIds(), indexed);

            long newVersion;
            FencedLock writerLock = hazelcast.getCPSubsystem().getLock(SystemConstants.LOCK_INDEX_WRITER);
            writerLock.lock();
            try {
                // Books that became ready while the replacement was being built must not be lost.
                List<Integer> late = new ArrayList<>();
                for (int bookId : source.listReadyBookIds()) {
                    if (!indexed.contains(bookId)) {
                        late.add(bookId);
                    }
                }
                indexInto(replacement, late, indexed);

                repository.replaceContentsFrom(temporaryDb);

                newVersion = hazelcast.getCPSubsystem()
                        .getAtomicLong(SystemConstants.ATOMIC_INDEX_VERSION)
                        .incrementAndGet();
            } finally {
                writerLock.unlock();
            }

            IMap<Integer, String> bookState = hazelcast.getMap(SystemConstants.MAP_BOOK_STATE);
            for (int bookId : indexed) {
                bookState.put(bookId, SystemConstants.STATE_INDEXED);
            }

            double elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            log.info("Index rebuilt with {} books. Published version: {}.", indexed.size(), newVersion);
            return new RebuildResult(indexed.size(), elapsedSeconds);
        } catch (RebuildInProgressException e) {
            throw e;
        } catch (Exception e) {
            log.error("Index rebuild failed. The previous index remains active.", e);
            throw new RebuildFailedException("Index rebuild failed", e);
        } finally {
            if (temporaryDb != null) {
                try {
                    SqliteRepository.deleteDatabaseFiles(temporaryDb);
                } catch (RuntimeException cleanupFailure) {
                    log.warn("Could not remove temporary rebuild database {}.", temporaryDb, cleanupFailure);
                }
            }
            if (rebuildLockHeld) {
                rebuildLock.unlock();
            }
            running.set(false);
        }
    }

    /**
     * Parses books in parallel batches and writes them to the target database in order.
     */
    private void indexInto(SqliteRepository target, List<Integer> bookIds, Set<Integer> indexed) throws Exception {
        int batchSize = workerThreads * 2;

        for (int from = 0; from < bookIds.size(); from += batchSize) {
            List<Integer> batch = bookIds.subList(from, Math.min(from + batchSize, bookIds.size()));
            List<Future<ParsedBook>> futures = new ArrayList<>();

            for (int bookId : batch) {
                futures.add(executor.submit(() -> source.parseBook(bookId)));
            }

            try {
                for (int i = 0; i < batch.size(); i++) {
                    ParsedBook parsed = futures.get(i).get();
                    target.saveBookIndex(parsed.metadata(), parsed.postings());
                    indexed.add(batch.get(i));
                }
            } catch (ExecutionException e) {
                futures.forEach(future -> future.cancel(true));
                Throwable cause = e.getCause();
                if (cause instanceof Exception exception) {
                    throw exception;
                }
                throw e;
            } catch (Exception e) {
                futures.forEach(future -> future.cancel(true));
                throw e;
            }
        }
    }
}

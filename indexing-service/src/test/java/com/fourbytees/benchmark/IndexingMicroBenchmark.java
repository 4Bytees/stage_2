package com.fourbytees.benchmark;

import com.fourbytees.common.constants.SystemConstants;
import com.fourbytees.indexing.parser.BodyTokenizer;
import com.fourbytees.indexing.parser.HeaderParser;
import com.fourbytees.indexing.repository.SqliteRepository;
import com.fourbytees.indexing.service.BookClaim;
import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.cp.IAtomicLong;
import com.hazelcast.cp.lock.FencedLock;
import com.hazelcast.map.IMap;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatFactory;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * JMH microbenchmarks for Stage 2 indexing operations:
 * header parsing, body tokenization, SQLite inverted-index insertion,
 * and Hazelcast distributed coordination overhead.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 400, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 400, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
@State(Scope.Benchmark)
public class IndexingMicroBenchmark {

    private Path tempDir;
    private Path headerFile;
    private Path bodyFile;
    private HeaderParser.BookMetadata parsedMetadata;
    private Map<String, List<Integer>> parsedPostings;
    private SqliteRepository sqliteRepository;
    private HazelcastInstance hazelcast;
    private IMap<Integer, BookClaim> bookClaims;
    private IMap<Integer, String> bookState;
    private FencedLock writerLock;
    private IAtomicLong publishedVersion;
    private String workerId;

    @Setup(Level.Trial)
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jmh-indexing-");
        headerFile = tempDir.resolve("1342.header.txt");
        bodyFile = tempDir.resolve("1342.body.txt");

        String headerText = """
                The Project Gutenberg eBook of Pride and Prejudice, by Jane Austen

                This eBook is for the use of anyone anywhere in the United States and
                most other parts of the world at no cost and with almost no restrictions
                whatsoever.

                Title: Pride and Prejudice
                       A Novel of Manners

                Author: Jane Austen

                Release date: June 1, 1998 [eBook #1342]
                Most recently updated: October 29, 2024

                Language: English

                Credits: Chuck Greif and the Online Distributed Proofreading Team
                """;
        Files.writeString(headerFile, headerText, StandardCharsets.UTF_8);

        StringBuilder bodyBuilder = new StringBuilder(64_000);
        String paragraph = """
                It is a truth universally acknowledged, that a single man in possession
                of a good fortune, must be in want of a wife. However little known the
                feelings or views of such a man may be on his first entering a
                neighbourhood, this truth is so well fixed in the minds of the
                surrounding families, that he is considered the rightful property of
                some one or other of their daughters. Adventure and discovery await
                every reader who explores the classic literature of Project Gutenberg.
                """;
        for (int i = 0; i < 80; i++) {
            bodyBuilder.append(paragraph).append(" chapter_section_").append(i % 20).append('\n');
        }
        Files.writeString(bodyFile, bodyBuilder.toString(), StandardCharsets.UTF_8);

        parsedMetadata = HeaderParser.parse(1342, headerFile);
        parsedPostings = BodyTokenizer.tokenize(bodyFile).postings();

        Path dbPath = tempDir.resolve("benchmark_index.db");
        sqliteRepository = new SqliteRepository(dbPath.toString());

        Config config = new Config();
        config.setClusterName("jmh-bench-" + UUID.randomUUID());
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        hazelcast = Hazelcast.newHazelcastInstance(config);
        bookClaims = hazelcast.getMap(SystemConstants.MAP_BOOK_CLAIMS);
        bookState = hazelcast.getMap(SystemConstants.MAP_BOOK_STATE);
        writerLock = hazelcast.getCPSubsystem().getLock(SystemConstants.LOCK_INDEX_WRITER);
        publishedVersion = hazelcast.getCPSubsystem().getAtomicLong(SystemConstants.ATOMIC_INDEX_VERSION);
        workerId = UUID.randomUUID().toString();
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        if (hazelcast != null) {
            hazelcast.shutdown();
        }
        if (tempDir != null && Files.exists(tempDir)) {
            try (Stream<Path> walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }

    @Benchmark
    public HeaderParser.BookMetadata headerParsing() throws IOException {
        return HeaderParser.parse(1342, headerFile);
    }

    @Benchmark
    public BodyTokenizer.TokenizedResult bodyTokenization() throws IOException {
        return BodyTokenizer.tokenize(bodyFile);
    }

    @Benchmark
    public int sqliteIndexInsertion() throws Exception {
        sqliteRepository.saveBookIndex(parsedMetadata, parsedPostings);
        return sqliteRepository.getIndexedBooksCount();
    }

    @Benchmark
    public long hazelcastCoordinationCost() {
        int bookId = 1342;
        BookClaim claim = new BookClaim(workerId, System.currentTimeMillis());
        bookClaims.put(bookId, claim);
        bookState.put(bookId, SystemConstants.STATE_INDEXING);
        long version;
        writerLock.lock();
        try {
            bookState.put(bookId, SystemConstants.STATE_INDEXED);
            version = publishedVersion.incrementAndGet();
        } finally {
            writerLock.unlock();
            bookClaims.remove(bookId, claim);
        }
        return version;
    }

    public static void main(String[] args) throws Exception {
        Path outDir = args.length > 0 ? Paths.get(args[0]) : Paths.get("results");
        Files.createDirectories(outDir);

        Options jsonOpts = new OptionsBuilder()
                .include(IndexingMicroBenchmark.class.getSimpleName())
                .resultFormat(ResultFormatType.JSON)
                .result(outDir.resolve("jmh-indexing.json").toString())
                .build();
        Collection<RunResult> results = new Runner(jsonOpts).run();

        try (PrintStream csvStream = new PrintStream(
                Files.newOutputStream(outDir.resolve("jmh-indexing.csv")),
                true,
                StandardCharsets.UTF_8)) {
            ResultFormatFactory.getInstance(ResultFormatType.CSV, csvStream).writeOut(results);
        }
    }
}

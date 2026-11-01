package com.fourbytees.indexing.repository;

import com.fourbytees.indexing.parser.HeaderParser.BookMetadata;
import com.google.gson.Gson;

import java.io.File;
import java.sql.*;
import java.util.List;
import java.util.Map;

public class SqliteRepository {

    private final String jdbcUrl;
    private final String dbFilePath;
    private final Gson gson = new Gson();

    public SqliteRepository(String dbFilePath) {
        this.dbFilePath = dbFilePath;
        this.jdbcUrl = "jdbc:sqlite:" + dbFilePath;
        initDatabase();
    }

    private void initDatabase() {
        File dbFile = new File(dbFilePath);
        if (dbFile.getParentFile() != null) {
            dbFile.getParentFile().mkdirs();
        }

        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement stmt = conn.createStatement()) {

            stmt.execute("PRAGMA journal_mode=WAL;");
            stmt.execute("PRAGMA synchronous=NORMAL;");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS books (
                    book_id INTEGER PRIMARY KEY,
                    title TEXT,
                    author TEXT,
                    language TEXT,
                    year INTEGER
                );
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS postings (
                    term TEXT,
                    book_id INTEGER,
                    freq INTEGER,
                    positions TEXT,
                    PRIMARY KEY (term, book_id)
                );
            """);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_postings_term ON postings(term);");

        } catch (SQLException e) {
            throw new RuntimeException("Error inicializando esquema SQLite", e);
        }
    }

    public void saveBookIndex(BookMetadata meta, Map<String, List<Integer>> postings) throws SQLException {
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement delPost = conn.prepareStatement("DELETE FROM postings WHERE book_id = ?");
                     PreparedStatement delBook = conn.prepareStatement("DELETE FROM books WHERE book_id = ?")) {
                    delPost.setInt(1, meta.bookId());
                    delPost.executeUpdate();
                    delBook.setInt(1, meta.bookId());
                    delBook.executeUpdate();
                }

                try (PreparedStatement insBook = conn.prepareStatement(
                        "INSERT INTO books (book_id, title, author, language, year) VALUES (?, ?, ?, ?, ?)")) {
                    insBook.setInt(1, meta.bookId());
                    insBook.setString(2, meta.title());
                    insBook.setString(3, meta.author());
                    insBook.setString(4, meta.language());
                    if (meta.year() != null) {
                        insBook.setInt(5, meta.year());
                    } else {
                        insBook.setNull(5, Types.INTEGER);
                    }
                    insBook.executeUpdate();
                }

                try (PreparedStatement insPost = conn.prepareStatement(
                        "INSERT INTO postings (term, book_id, freq, positions) VALUES (?, ?, ?, ?)")) {
                    for (Map.Entry<String, List<Integer>> entry : postings.entrySet()) {
                        insPost.setString(1, entry.getKey());
                        insPost.setInt(2, meta.bookId());
                        insPost.setInt(3, entry.getValue().size());
                        insPost.setString(4, gson.toJson(entry.getValue()));
                        insPost.addBatch();
                    }
                    insPost.executeBatch();
                }

                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            }
        }
    }

    public String getDbFilePath() {
        return dbFilePath;
    }

    /**
     * Atomically replaces the whole content of this database with the content of a complete
     * replacement database.
     *
     * <p>The database file itself is never replaced, so open connections, WAL and SHM side files
     * stay valid. All rows are swapped inside one write transaction: concurrent readers observe
     * either the previous content or the new content, never an empty or partial index. If anything
     * fails, the transaction is rolled back and the previous content remains active.</p>
     */
    public void replaceContentsFrom(String sourceDbFilePath) throws SQLException {
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA busy_timeout=30000;");

            try (PreparedStatement attach = conn.prepareStatement("ATTACH DATABASE ? AS replacement")) {
                attach.setString(1, sourceDbFilePath);
                attach.execute();
            }

            try {
                stmt.execute("BEGIN IMMEDIATE");
                try {
                    stmt.execute("DELETE FROM main.postings");
                    stmt.execute("DELETE FROM main.books");
                    stmt.execute("INSERT INTO main.books (book_id, title, author, language, year) "
                            + "SELECT book_id, title, author, language, year FROM replacement.books");
                    stmt.execute("INSERT INTO main.postings (term, book_id, freq, positions) "
                            + "SELECT term, book_id, freq, positions FROM replacement.postings");
                    stmt.execute("COMMIT");
                } catch (SQLException e) {
                    try {
                        stmt.execute("ROLLBACK");
                    } catch (SQLException rollbackFailure) {
                        e.addSuppressed(rollbackFailure);
                    }
                    throw e;
                }
            } finally {
                try {
                    stmt.execute("DETACH DATABASE replacement");
                } catch (SQLException ignored) {
                    // The connection is closed right after this block, which detaches anyway.
                }
            }

            // Best effort: keep the WAL file small. PASSIVE never blocks readers.
            stmt.execute("PRAGMA wal_checkpoint(PASSIVE);");
        }
    }

    /**
     * Deletes a SQLite database file together with its WAL, SHM and rollback journal side files.
     * All connections to the database must be closed.
     */
    public static void deleteDatabaseFiles(String dbFilePath) {
        for (String suffix : new String[] {"", "-wal", "-shm", "-journal"}) {
            try {
                java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(dbFilePath + suffix));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }

    public int getIndexedBooksCount() {
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM books")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    public double getDatabaseSizeMB() {
        File file = new File(dbFilePath);
        return file.exists() ? (double) file.length() / (1024 * 1024) : 0.0;
    }
}

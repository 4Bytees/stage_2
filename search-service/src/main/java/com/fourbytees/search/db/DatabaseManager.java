package com.fourbytees.search.db;

import com.fourbytees.search.model.Book;
import org.sqlite.SQLiteConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DatabaseManager {
    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);

    private static final Pattern WORD_PATTERN = Pattern.compile("\\b[a-z]{3,}\\b");

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "but", "by", "for",
            "if", "in", "into", "is", "it", "no", "not", "of", "on", "or",
            "such", "that", "the", "their", "then", "there", "these", "they",
            "this", "to"
    );

    public static final Set<String> SUPPORTED_LANGUAGES = Set.of(
            "en", "es", "fr", "de", "it", "pt", "la", "nl", "ru", "zh", "ja", "el"
    );

    private final String dbPath;

    public DatabaseManager(String dbPath) {
        this.dbPath = dbPath;
    }

    private Connection getConnection() throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath, config.toProperties());
    }

    public boolean isHealthy() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1")) {
            return rs.next();
        } catch (SQLException e) {
            log.warn("Health check de base de datos falló: {}", e.getMessage());
            return false;
        }
    }

    public record SearchResult(List<Book> books, int total) {}

    /**
     * Realiza búsqueda por término clave sobre la base de datos SQLite en modo solo lectura.
     *
     * <p><b>Semántica de TF-IDF para término único:</b>
     * Para una palabra clave dada {@code q}, el factor {@code idf = ln(N / df)} es idéntico y constante
     * para todos los libros que contienen dicho término (donde N es el total de libros y df los libros con el término).
     * Por lo tanto, ordenar por {@code p.freq DESC} con desempate por {@code b.book_id ASC} produce
     * exactamente el ordenamiento relativo por puntuación TF-IDF.
     *
     * @throws SQLException si ocurre un error de acceso a la base de datos (propagado para respuesta HTTP 500).
     */
    public SearchResult searchByTerm(String rawQuery, String authorFilter, String langFilter, Integer yearFilter, int offset, int limit) throws SQLException {
        if (rawQuery == null) {
            return new SearchResult(Collections.emptyList(), 0);
        }

        // 1. Normalización con las mismas reglas de tokenización de Stage 1:
        String lower = rawQuery.trim().toLowerCase();
        Matcher matcher = WORD_PATTERN.matcher(lower);
        if (!matcher.find()) {
            return new SearchResult(Collections.emptyList(), 0);
        }
        String term = matcher.group();
        if (STOP_WORDS.contains(term)) {
            return new SearchResult(Collections.emptyList(), 0);
        }

        // 2. Filtros
        String authorPattern = (authorFilter != null && !authorFilter.isBlank())
                ? "%" + authorFilter.trim().toLowerCase() + "%" : null;
        String langValue = (langFilter != null && !langFilter.isBlank())
                ? langFilter.trim().toLowerCase() : null;

        String sql = """
            SELECT
                b.book_id,
                b.title,
                b.author,
                b.language,
                b.year,
                p.freq,
                COUNT(*) OVER() AS total_matches
            FROM postings p
            JOIN books b ON p.book_id = b.book_id
            WHERE p.term = ?
              AND (? IS NULL OR LOWER(b.author) LIKE ?)
              AND (? IS NULL OR LOWER(b.language) = ?)
              AND (? IS NULL OR b.year = ?)
            ORDER BY p.freq DESC, b.book_id ASC
            LIMIT ? OFFSET ?
            """;

        List<Book> books = new ArrayList<>();
        int totalMatches = 0;

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            conn.setReadOnly(true);

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, term);
                pstmt.setString(2, authorPattern);
                pstmt.setString(3, authorPattern);
                pstmt.setString(4, langValue);
                pstmt.setString(5, langValue);
                if (yearFilter != null) {
                    pstmt.setInt(6, yearFilter);
                    pstmt.setInt(7, yearFilter);
                } else {
                    pstmt.setNull(6, Types.INTEGER);
                    pstmt.setNull(7, Types.INTEGER);
                }
                pstmt.setInt(8, limit);
                pstmt.setInt(9, offset);

                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        if (totalMatches == 0) {
                            totalMatches = rs.getInt("total_matches");
                        }
                        books.add(new Book(
                                rs.getInt("book_id"),
                                rs.getString("title"),
                                rs.getString("author"),
                                rs.getString("language"),
                                rs.getInt("year")
                        ));
                    }
                }
            }
            conn.commit();
        }

        return new SearchResult(books, totalMatches);
    }

    public Book getBookById(int bookId) throws SQLException {
        String sql = "SELECT book_id, title, author, language, year FROM books WHERE book_id = ?";
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            conn.setReadOnly(true);
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, bookId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return new Book(
                                rs.getInt("book_id"),
                                rs.getString("title"),
                                rs.getString("author"),
                                rs.getString("language"),
                                rs.getInt("year")
                        );
                    }
                }
            }
            conn.commit();
        }
        return null;
    }

    public List<String> getAvailableLanguages() throws SQLException {
        List<String> languages = new ArrayList<>();
        String sql = "SELECT DISTINCT language FROM books WHERE language IS NOT NULL AND language != '' ORDER BY language ASC";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                languages.add(rs.getString("language"));
            }
        }
        return languages;
    }

    public List<Integer> getAvailableYears() throws SQLException {
        List<Integer> years = new ArrayList<>();
        String sql = "SELECT DISTINCT year FROM books WHERE year IS NOT NULL AND year > 0 ORDER BY year ASC";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                years.add(rs.getInt("year"));
            }
        }
        return years;
    }
}

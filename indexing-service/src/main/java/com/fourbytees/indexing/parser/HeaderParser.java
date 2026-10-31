package com.fourbytees.indexing.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HeaderParser {
    private static final Logger log = LoggerFactory.getLogger(HeaderParser.class);

    private static final Pattern TITLE_PATTERN = Pattern.compile("^Title:\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTHOR_PATTERN = Pattern.compile("^Author:\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LANG_PATTERN = Pattern.compile("^Language:\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RELEASE_PATTERN = Pattern.compile("^(?:Release date|Posting date):\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(1\\d{3}|20\\d{2})\\b");

    private static final Map<String, String> LANGUAGE_MAP = Map.ofEntries(
            Map.entry("english", "en"),
            Map.entry("spanish", "es"),
            Map.entry("french", "fr"),
            Map.entry("german", "de"),
            Map.entry("italian", "it"),
            Map.entry("portuguese", "pt"),
            Map.entry("latin", "la"),
            Map.entry("dutch", "nl"),
            Map.entry("russian", "ru"),
            Map.entry("chinese", "zh"),
            Map.entry("japanese", "ja"),
            Map.entry("greek", "el")
    );

    public record BookMetadata(int bookId, String title, String author, String language, Integer year) {}

    private enum ActiveField {
        NONE, TITLE, AUTHOR, OTHER
    }

    public static BookMetadata parse(int bookId, Path headerFile) throws IOException {
        StringBuilder title = new StringBuilder();
        StringBuilder author = new StringBuilder();
        String language = null;
        Integer year = null;

        ActiveField activeField = ActiveField.NONE;

        try (BufferedReader reader = Files.newBufferedReader(headerFile, StandardCharsets.UTF_8)) {
            String rawLine;
            while ((rawLine = reader.readLine()) != null) {
                if (rawLine.isBlank()) {
                    activeField = ActiveField.NONE;
                    continue;
                }

                boolean isIndented = rawLine.startsWith(" ") || rawLine.startsWith("\t");
                String line = rawLine.trim();

                // 1. Líneas de continuación con sangría para Title y Author:
                if (isIndented && activeField == ActiveField.TITLE) {
                    title.append(" ").append(line);
                    continue;
                } else if (isIndented && activeField == ActiveField.AUTHOR) {
                    author.append(" ").append(line);
                    continue;
                }

                // 2. Coincidencias de cabeceras principales:
                Matcher mTitle = TITLE_PATTERN.matcher(line);
                if (mTitle.matches()) {
                    title = new StringBuilder(mTitle.group(1).trim());
                    activeField = ActiveField.TITLE;
                    continue;
                }

                Matcher mAuth = AUTHOR_PATTERN.matcher(line);
                if (mAuth.matches()) {
                    author = new StringBuilder(mAuth.group(1).trim());
                    activeField = ActiveField.AUTHOR;
                    continue;
                }

                Matcher mLang = LANG_PATTERN.matcher(line);
                if (mLang.matches()) {
                    activeField = ActiveField.OTHER;
                    String rawLang = mLang.group(1).trim().toLowerCase();
                    language = mapLanguageToIso(rawLang, bookId);
                    continue;
                }

                Matcher mRel = RELEASE_PATTERN.matcher(line);
                if (mRel.matches()) {
                    activeField = ActiveField.OTHER;
                    Matcher mYear = YEAR_PATTERN.matcher(mRel.group(1));
                    if (mYear.find()) {
                        year = Integer.parseInt(mYear.group(1));
                    }
                    continue;
                }

                // Cualquier otra cabecera resetea el campo activo
                if (!isIndented) {
                    activeField = ActiveField.OTHER;
                }
            }
        }

        String finalTitle = title.length() > 0 ? title.toString().trim() : "Unknown";
        String finalAuthor = author.length() > 0 ? author.toString().trim() : "Unknown";

        return new BookMetadata(bookId, finalTitle, finalAuthor, language, year);
    }

    private static String mapLanguageToIso(String rawLang, int bookId) {
        if (LANGUAGE_MAP.containsKey(rawLang)) {
            return LANGUAGE_MAP.get(rawLang);
        }
        if (LANGUAGE_MAP.containsValue(rawLang)) {
            return rawLang; // Ya viene como código ISO soportado
        }
        log.warn("Libro ID {}: Idioma no reconocido o no soportado '{}', asignando null.", bookId, rawLang);
        return null;
    }
}

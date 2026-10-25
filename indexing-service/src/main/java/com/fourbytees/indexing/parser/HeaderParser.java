package com.fourbytees.indexing.parser;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HeaderParser {

    private static final Pattern TITLE_PATTERN = Pattern.compile("^Title:\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTHOR_PATTERN = Pattern.compile("^Author:\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LANG_PATTERN = Pattern.compile("^Language:\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RELEASE_PATTERN = Pattern.compile("^(?:Release date|Posting date):\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(1\\d{3}|20\\d{2})\\b");

    public record BookMetadata(int bookId, String title, String author, String language, int year) {}

    public static BookMetadata parse(int bookId, Path headerFile) throws IOException {
        String title = "Unknown";
        String author = "Unknown";
        String language = "Unknown";
        int year = 0;

        try (BufferedReader reader = Files.newBufferedReader(headerFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                Matcher mTitle = TITLE_PATTERN.matcher(line);
                if (mTitle.matches()) {
                    title = mTitle.group(1).trim();
                    continue;
                }
                Matcher mAuth = AUTHOR_PATTERN.matcher(line);
                if (mAuth.matches()) {
                    author = mAuth.group(1).trim();
                    continue;
                }
                Matcher mLang = LANG_PATTERN.matcher(line);
                if (mLang.matches()) {
                    language = mLang.group(1).trim().toLowerCase();
                    continue;
                }
                Matcher mRel = RELEASE_PATTERN.matcher(line);
                if (mRel.matches()) {
                    Matcher mYear = YEAR_PATTERN.matcher(mRel.group(1));
                    if (mYear.find()) {
                        year = Integer.parseInt(mYear.group(1));
                    }
                }
            }
        }
        return new BookMetadata(bookId, title, author, language, year);
    }
}
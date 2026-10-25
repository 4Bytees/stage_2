package com.fourbytees.indexing.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BodyTokenizer {

    private static final Pattern WORD_PATTERN = Pattern.compile("\\b[a-z]{3,}\\b");

    private static final Set<String> STOP_WORDS = Set.of(
        "a", "an", "and", "are", "as", "at", "be", "but", "by", "for",
        "if", "in", "into", "is", "it", "no", "not", "of", "on", "or",
        "such", "that", "the", "their", "then", "there", "these", "they",
        "this", "to"
    );

    public record TokenizedResult(Map<String, List<Integer>> postings) {}

    public static TokenizedResult tokenize(Path bodyFile) throws IOException {
        String content = Files.readString(bodyFile, StandardCharsets.UTF_8).toLowerCase();
        Matcher matcher = WORD_PATTERN.matcher(content);

        Map<String, List<Integer>> postings = new HashMap<>();
        int wordIndex = 0;

        while (matcher.find()) {
            String word = matcher.group();
            if (!STOP_WORDS.contains(word)) {
                postings.computeIfAbsent(word, k -> new ArrayList<>()).add(wordIndex);
            }
            wordIndex++;
        }
        return new TokenizedResult(postings);
    }
}
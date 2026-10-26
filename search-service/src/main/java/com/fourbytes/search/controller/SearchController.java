package com.fourbytes.search.controller;

import com.fourbytes.search.config.HazelcastConfig;
import com.fourbytes.search.db.DatabaseManager;
import com.fourbytes.search.model.Book;
import com.fourbytes.search.model.ErrorResponse;
import com.fourbytes.search.model.SearchResponse;
import com.google.gson.Gson;
import io.javalin.http.Context;

import java.util.*;
import java.util.stream.Collectors;

public class SearchController {
    private final HazelcastConfig hazelcastConfig;
    private final DatabaseManager databaseManager;
    private final Gson gson;

    public SearchController(HazelcastConfig hazelcastConfig, DatabaseManager databaseManager, Gson gson) {
        this.hazelcastConfig = hazelcastConfig;
        this.databaseManager = databaseManager;
        this.gson = gson;
    }

    public void handleSearch(Context ctx) {
        String q = ctx.queryParam("q");
        if (q == null || q.trim().isEmpty()) {
            ctx.status(400).result(gson.toJson(new ErrorResponse("Bad Request", "Parameter 'q' is mandatory.")));
            return;
        }

        int offset = 0;
        int limit = 20;
        try {
            String offsetStr = ctx.queryParam("offset");
            if (offsetStr != null) offset = Integer.parseInt(offsetStr);
            
            String limitStr = ctx.queryParam("limit");
            if (limitStr != null) limit = Integer.parseInt(limitStr);
        } catch (NumberFormatException e) {
            ctx.status(400).result(gson.toJson(new ErrorResponse("Bad Request", "Parameters 'offset' and 'limit' must be valid integers.")));
            return;
        }

        if (offset < 0 || limit < 1 || limit > 100) {
            ctx.status(400).result(gson.toJson(new ErrorResponse("Bad Request", "Invalid pagination: offset must be >= 0 and limit between 1 and 100.")));
            return;
        }

        String authorFilter = ctx.queryParam("author");
        String langFilter = ctx.queryParam("language");
        Integer yearFilter = null;
        try {
            String yearStr = ctx.queryParam("year");
            if (yearStr != null) yearFilter = Integer.parseInt(yearStr);
        } catch (NumberFormatException e) {
            ctx.status(400).result(gson.toJson(new ErrorResponse("Bad Request", "Parameter 'year' must be a valid integer.")));
            return;
        }

        Map<Integer, Double> postings = hazelcastConfig.getPostingsForTerm(q.trim().toLowerCase());

        List<SearchResultItem> matchedItems = new ArrayList<>();
        for (Map.Entry<Integer, Double> entry : postings.entrySet()) {
            int bookId = entry.getKey();
            double tfIdfScore = entry.getValue();

            Book book = databaseManager.getBookById(bookId);
            if (book == null) continue;

            if (langFilter != null && !langFilter.equalsIgnoreCase(book.getLanguage())) continue;
            if (yearFilter != null && yearFilter != book.getYear()) continue;
            if (authorFilter != null && (book.getAuthor() == null || !book.getAuthor().toLowerCase().contains(authorFilter.toLowerCase()))) continue;

            matchedItems.add(new SearchResultItem(book, tfIdfScore));
        }

        matchedItems.sort((a, b) -> {
            int scoreComparison = Double.compare(b.tfIdfScore, a.tfIdfScore);
            if (scoreComparison != 0) {
                return scoreComparison;
            }
            return Integer.compare(a.book.getBook_id(), b.book.getBook_id());
        });

        int totalMatches = matchedItems.size();

        int start = Math.min(offset, totalMatches);
        int end = Math.min(start + limit, totalMatches);
        List<Book> paginatedBooks = matchedItems.subList(start, end).stream()
                .map(item -> item.book)
                .collect(Collectors.toList());

        Map<String, String> appliedFilters = new HashMap<>();
        if (authorFilter != null) appliedFilters.put("author", authorFilter);
        if (langFilter != null) appliedFilters.put("language", langFilter);
        if (yearFilter != null) appliedFilters.put("year", String.valueOf(yearFilter));

        SearchResponse response = new SearchResponse(q, appliedFilters, totalMatches, offset, limit, paginatedBooks);
        ctx.status(200).result(gson.toJson(response));
    }

    private static class SearchResultItem {
        Book book;
        double tfIdfScore;

        public SearchResultItem(Book book, double tfIdfScore) {
            this.book = book;
            this.tfIdfScore = tfIdfScore;
        }
    }
}
package com.fourbytees.search.controller;

import com.fourbytees.search.config.HazelcastConfig;
import com.fourbytees.search.db.DatabaseManager;
import com.fourbytees.search.model.ErrorResponse;
import com.fourbytees.search.model.SearchResponse;
import com.google.gson.Gson;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.*;

public class SearchController {
    private static final Logger log = LoggerFactory.getLogger(SearchController.class);

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

        // Defaults: offset=0, limit=20 (máximo 100)
        int offset = 0;
        int limit = 20;

        String offsetStr = ctx.queryParam("offset");
        if (offsetStr != null && !offsetStr.isBlank()) {
            try {
                offset = Integer.parseInt(offsetStr.trim());
            } catch (NumberFormatException e) {
                ctx.status(400).result(gson.toJson(new ErrorResponse("Bad Request", "Parameter 'offset' must be a valid integer.")));
                return;
            }
            if (offset < 0) {
                ctx.status(400).result(gson.toJson(new ErrorResponse("Bad Request", "Parameter 'offset' must be greater than or equal to 0.")));
                return;
            }
        }

        String limitStr = ctx.queryParam("limit");
        if (limitStr != null && !limitStr.isBlank()) {
            try {
                limit = Integer.parseInt(limitStr.trim());
            } catch (NumberFormatException e) {
                ctx.status(400).result(gson.toJson(new ErrorResponse("Bad Request", "Parameter 'limit' must be a valid integer.")));
                return;
            }
            if (limit < 1 || limit > 100) {
                ctx.status(400).result(gson.toJson(new ErrorResponse("Bad Request", "Parameter 'limit' must be between 1 and 100.")));
                return;
            }
        }

        String authorFilter = ctx.queryParam("author");

        String langFilter = ctx.queryParam("language");
        if (langFilter != null && !langFilter.isBlank()) {
            langFilter = langFilter.trim().toLowerCase();
            if (!DatabaseManager.SUPPORTED_LANGUAGES.contains(langFilter)) {
                ctx.status(400).result(gson.toJson(new ErrorResponse("Bad Request",
                        "Language filter '" + langFilter + "' is not supported. Supported ISO codes: " + DatabaseManager.SUPPORTED_LANGUAGES)));
                return;
            }
        }

        Integer yearFilter = null;
        String yearStr = ctx.queryParam("year");
        if (yearStr != null && !yearStr.isBlank()) {
            try {
                yearFilter = Integer.parseInt(yearStr.trim());
            } catch (NumberFormatException e) {
                ctx.status(400).result(gson.toJson(new ErrorResponse("Bad Request", "Parameter 'year' must be a valid 4-digit integer.")));
                return;
            }
            if (yearFilter < 1000 || yearFilter > 9999) {
                ctx.status(400).result(gson.toJson(new ErrorResponse("Bad Request", "Parameter 'year' must be a 4-digit integer (e.g. 1813).")));
                return;
            }
        }

        DatabaseManager.SearchResult searchResult;
        try {
            searchResult = databaseManager.searchByTerm(
                    q, authorFilter, langFilter, yearFilter, offset, limit
            );
        } catch (SQLException | RuntimeException e) {
            log.error("Unexpected failure while executing /search", e);
            ApiErrors.internalServerError(ctx, gson);
            return;
        }

        Map<String, String> appliedFilters = new HashMap<>();
        if (authorFilter != null && !authorFilter.isBlank()) appliedFilters.put("author", authorFilter);
        if (langFilter != null && !langFilter.isBlank()) appliedFilters.put("language", langFilter);
        if (yearFilter != null) appliedFilters.put("year", String.valueOf(yearFilter));

        SearchResponse response = new SearchResponse(q, appliedFilters, searchResult.total(), offset, limit, searchResult.books());
        ctx.status(200).result(gson.toJson(response));
    }
}

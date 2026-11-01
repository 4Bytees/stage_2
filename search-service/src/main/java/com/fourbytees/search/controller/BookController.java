package com.fourbytees.search.controller;

import com.fourbytees.search.db.DatabaseManager;
import com.fourbytees.search.model.Book;
import com.fourbytees.search.model.ErrorResponse;
import com.google.gson.Gson;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class BookController {
    private static final Logger log = LoggerFactory.getLogger(BookController.class);

    private final DatabaseManager databaseManager;
    private final Gson gson;

    public BookController(DatabaseManager databaseManager, Gson gson) {
        this.databaseManager = databaseManager;
        this.gson = gson;
    }

    public void handleGetBookById(Context ctx) {
        int bookId;
        try {
            bookId = Integer.parseInt(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            ctx.status(400).result(gson.toJson(new ErrorResponse("Bad Request", "Invalid book ID format.")));
            return;
        }

        try {
            Book book = databaseManager.getBookById(bookId);
            if (book != null) {
                ctx.status(200).result(gson.toJson(book));
            } else {
                ctx.status(404).result(gson.toJson(new ErrorResponse("Not Found", "Book with ID " + bookId + " was not found.")));
            }
        } catch (SQLException | RuntimeException e) {
            log.error("Unexpected failure while executing /books/{}", bookId, e);
            ApiErrors.internalServerError(ctx, gson);
        }
    }

    public void handleGetFilters(Context ctx) {
        try {
            Map<String, Object> filters = new HashMap<>();
            filters.put("languages", databaseManager.getAvailableLanguages());
            filters.put("years", databaseManager.getAvailableYears());
            ctx.status(200).result(gson.toJson(filters));
        } catch (SQLException | RuntimeException e) {
            log.error("Unexpected failure while executing /search/filters", e);
            ApiErrors.internalServerError(ctx, gson);
        }
    }
}

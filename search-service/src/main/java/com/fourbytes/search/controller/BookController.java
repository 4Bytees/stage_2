package com.fourbytes.search.controller;

import com.fourbytes.search.db.DatabaseManager;
import com.fourbytes.search.model.Book;
import com.fourbytes.search.model.ErrorResponse;
import com.google.gson.Gson;
import io.javalin.http.Context;

import java.util.HashMap;
import java.util.Map;

public class BookController {
    private final DatabaseManager databaseManager;
    private final Gson gson;

    public BookController(DatabaseManager databaseManager, Gson gson) {
        this.databaseManager = databaseManager;
        this.gson = gson;
    }

    public void handleGetBookById(Context ctx) {
        try {
            int bookId = Integer.parseInt(ctx.pathParam("id"));
            Book book = databaseManager.getBookById(bookId);
            if (book != null) {
                ctx.status(200).result(gson.toJson(book));
            } else {
                ctx.status(404).result(gson.toJson(new ErrorResponse("Not Found", "Book with ID " + bookId + " was not found.")));
            }
        } catch (NumberFormatException e) {
            ctx.status(400).result(gson.toJson(new ErrorResponse("Bad Request", "Invalid book ID format.")));
        }
    }

    public void handleGetFilters(Context ctx) {
        Map<String, Object> filters = new HashMap<>();
        filters.put("languages", databaseManager.getAvailableLanguages());
        filters.put("years", databaseManager.getAvailableYears());
        ctx.status(200).result(gson.toJson(filters));
    }
}
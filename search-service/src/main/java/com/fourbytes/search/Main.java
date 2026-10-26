package com.fourbytes.search;

import com.fourbytes.search.config.HazelcastConfig;
import com.fourbytes.search.controller.BookController;
import com.fourbytes.search.controller.SearchController;
import com.fourbytes.search.controller.StatusController;
import com.fourbytes.search.db.DatabaseManager;
import com.google.gson.Gson;
import io.javalin.Javalin;
import io.javalin.plugin.bundled.CorsPluginConfig;

public class Main {
    public static void main(String[] args) {
        String dbPath = System.getenv().getOrDefault("METADATA_DB_PATH", "datamarts/metadata.db");
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "7000"));

        DatabaseManager dbManager = new DatabaseManager(dbPath);
        HazelcastConfig hazelcastConfig = new HazelcastConfig();
        Gson gson = new Gson();

        SearchController searchController = new SearchController(hazelcastConfig, dbManager, gson);
        BookController bookController = new BookController(dbManager, gson);
        StatusController statusController = new StatusController(hazelcastConfig, gson);

        Javalin app = Javalin.create(config -> {
            config.bundledPlugins.enableCors(cors -> {
                cors.addRule(CorsPluginConfig.CorsRule::anyHost);
            });
            config.http.defaultContentType = "application/json";
        }).start(port);

        app.get("/search", searchController::handleSearch);
        app.get("/books/{id}", bookController::handleGetBookById);
        app.get("/search/filters", bookController::handleGetFilters);
        app.get("/status", statusController::handleStatus);

        System.out.println("[SearchService] Started successfully on port " + port);
    }
}
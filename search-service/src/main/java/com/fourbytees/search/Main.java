package com.fourbytees.search;

import com.fourbytees.search.config.HazelcastConfig;
import com.fourbytees.search.controller.BookController;
import com.fourbytees.search.controller.SearchController;
import com.fourbytees.search.controller.StatusController;
import com.fourbytees.search.db.DatabaseManager;
import com.google.gson.Gson;
import io.javalin.Javalin;
import io.javalin.plugin.bundled.CorsPluginConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        String dbPath = getEnvOrDefault("METADATA_DB_PATH", getEnvOrDefault("SQLITE_DB_PATH", "/data/datamarts/metadata.db"));
        int port = Integer.parseInt(getEnvOrDefault("PORT", "7000"));

        DatabaseManager dbManager = new DatabaseManager(dbPath);
        HazelcastConfig hazelcastConfig = new HazelcastConfig();
        Gson gson = new Gson();

        SearchController searchController = new SearchController(hazelcastConfig, dbManager, gson);
        BookController bookController = new BookController(dbManager, gson);
        StatusController statusController = new StatusController(hazelcastConfig, dbManager, gson);

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

        log.info("[SearchService] Started successfully on port {}", port);
    }

    private static String getEnvOrDefault(String key, String defaultValue) {
        String prop = System.getProperty(key);
        if (prop != null && !prop.isBlank()) return prop;
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }
}

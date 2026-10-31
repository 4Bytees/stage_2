package com.fourbytees.search.controller;

import com.fourbytees.search.config.HazelcastConfig;
import com.fourbytees.search.db.DatabaseManager;
import com.google.gson.Gson;
import io.javalin.http.Context;

import java.util.HashMap;
import java.util.Map;

public class StatusController {
    private final HazelcastConfig hazelcastConfig;
    private final DatabaseManager databaseManager;
    private final Gson gson;

    public StatusController(HazelcastConfig hazelcastConfig, DatabaseManager databaseManager, Gson gson) {
        this.hazelcastConfig = hazelcastConfig;
        this.databaseManager = databaseManager;
        this.gson = gson;
    }

    public StatusController(HazelcastConfig hazelcastConfig, Gson gson) {
        this(hazelcastConfig, null, gson);
    }

    public void handleStatus(Context ctx) {
        boolean hzConnected = hazelcastConfig != null && hazelcastConfig.isConnected();
        boolean dbHealthy = (databaseManager == null) || databaseManager.isHealthy();
        String serviceStatus = (hzConnected && dbHealthy) ? "running" : "degraded";

        Map<String, Object> status = new HashMap<>();
        status.put("service", "search-service");
        status.put("status", serviceStatus);
        status.put("hazelcast_status", hzConnected ? "connected" : "disconnected");
        status.put("sqlite_status", dbHealthy ? "read-only-connected" : "error");
        status.put("active_index_version", hazelcastConfig != null ? hazelcastConfig.getPublishedIndexVersion() : "unknown");

        ctx.status(200).result(gson.toJson(status));
    }
}

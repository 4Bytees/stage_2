package com.fourbytes.search.controller;

import com.fourbytes.search.config.HazelcastConfig;
import com.google.gson.Gson;
import io.javalin.http.Context;

import java.util.HashMap;
import java.util.Map;

public class StatusController {
    private final HazelcastConfig hazelcastConfig;
    private final Gson gson;

    public StatusController(HazelcastConfig hazelcastConfig, Gson gson) {
        this.hazelcastConfig = hazelcastConfig;
        this.gson = gson;
    }

    public void handleStatus(Context ctx) {
        Map<String, Object> status = new HashMap<>();
        status.put("service", "search-service");
        status.put("status", "running");
        status.put("hazelcast_status", hazelcastConfig.isConnected() ? "connected" : "disconnected");
        status.put("sqlite_status", "read-only-connected");
        status.put("active_index_version", hazelcastConfig.getPublishedIndexVersion());
        
        ctx.status(200).result(gson.toJson(status));
    }
}
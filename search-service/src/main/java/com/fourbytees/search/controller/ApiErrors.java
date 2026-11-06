package com.fourbytees.search.controller;

import com.google.gson.Gson;
import io.javalin.http.Context;

import java.util.Map;

/**
 * Builds client-facing error responses that never expose internal details.
 */
final class ApiErrors {

    static final String INTERNAL_SERVER_ERROR = "Internal server error";

    private ApiErrors() {
    }

    /**
     * Sends a generic HTTP 500 response. The cause must be logged by the caller.
     */
    static void internalServerError(Context ctx, Gson gson) {
        ctx.status(500).result(gson.toJson(Map.of("error", INTERNAL_SERVER_ERROR)));
    }
}

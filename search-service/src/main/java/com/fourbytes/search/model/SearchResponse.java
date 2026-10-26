package com.fourbytes.search.model;

import java.util.List;
import java.util.Map;

public class SearchResponse {
    private String query;
    private Map<String, String> filters;
    private int total;
    private int offset;
    private int limit;
    private List<Book> results;

    public SearchResponse(String query, Map<String, String> filters, int total, int offset, int limit, List<Book> results) {
        this.query = query;
        this.filters = filters;
        this.total = total;
        this.offset = offset;
        this.limit = limit;
        this.results = results;
    }

    public String getQuery() { return query; }
    public Map<String, String> getFilters() { return filters; }
    public int getTotal() { return total; }
    public int getOffset() { return offset; }
    public int getLimit() { return limit; }
    public List<Book> getResults() { return results; }
}
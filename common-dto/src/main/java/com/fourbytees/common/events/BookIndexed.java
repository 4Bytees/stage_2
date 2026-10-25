package com.fourbytees.common.events;

import java.time.Instant;

public class BookIndexed {
    private String event_id;
    private int book_id;
    private long index_version;
    private String indexed_at;

    public BookIndexed() {}

    public BookIndexed(String event_id, int book_id, long index_version, String indexed_at) {
        this.event_id = event_id;
        this.book_id = book_id;
        this.index_version = index_version;
        this.indexed_at = indexed_at;
    }

    public BookIndexed(String event_id, int book_id, long index_version) {
        this(event_id, book_id, index_version, Instant.now().toString());
    }

    public String getEvent_id() { return event_id; }
    public int getBook_id() { return book_id; }
    public long getIndex_version() { return index_version; }
    public String getIndexed_at() { return indexed_at; }
}
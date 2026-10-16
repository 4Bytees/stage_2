package com.fourbytees.common.events;

import java.time.Instant;

public class BookDownloaded {
    private String event_id;
    private int book_id;
    private String path;
    private String downloaded_at;

    public BookDownloaded(String event_id, int book_id, String path) {
        this.event_id = event_id;
        this.book_id = book_id;
        this.path = path;
        this.downloaded_at = Instant.now().toString();
    }

    public String getEvent_id() { return event_id; }
    public int getBook_id() { return book_id; }
    public String getPath() { return path; }
    public String getDownloaded_at() { return downloaded_at; }
}
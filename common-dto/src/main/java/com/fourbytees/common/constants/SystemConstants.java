package com.fourbytees.common.constants;

public final class SystemConstants {
    private SystemConstants() {}

    public static final String STATE_DOWNLOADED = "DOWNLOADED";
    public static final String STATE_INDEXING = "INDEXING";
    public static final String STATE_INDEXED = "INDEXED";
    public static final String STATE_FAILED = "FAILED";

    public static final String MAP_BOOK_STATE = "book-state";
    public static final String LOCK_INDEX_WRITER = "index-writer";
    public static final String ATOMIC_INDEX_VERSION = "index-published-version";

    public static final String QUEUE_BOOKS_DOWNLOADED = "books.downloaded";
    public static final String QUEUE_BOOKS_INDEXED = "books.indexed";
    public static final String QUEUE_BOOKS_DLQ = "books.dlq";
}
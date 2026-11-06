package com.fourbytees.indexing.service;

import com.fourbytees.indexing.parser.HeaderParser.BookMetadata;

import java.util.List;
import java.util.Map;

/**
 * A book that has been read and tokenized but not yet written to any index.
 */
public record ParsedBook(BookMetadata metadata, Map<String, List<Integer>> postings) {
}

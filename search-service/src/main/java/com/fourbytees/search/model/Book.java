package com.fourbytees.search.model;

public class Book {
    private int book_id;
    private String title;
    private String author;
    private String language;
    private Integer year;

    public Book() {}

    public Book(int book_id, String title, String author, String language, Integer year) {
        this.book_id = book_id;
        this.title = title;
        this.author = author;
        this.language = language;
        this.year = year;
    }

    public int getBook_id() { return book_id; }
    public void setBook_id(int book_id) { this.book_id = book_id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public Integer getYear() { return year; }
    public void setYear(Integer year) { this.year = year; }
}

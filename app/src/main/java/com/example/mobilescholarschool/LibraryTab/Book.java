package com.example.mobilescholarschool.LibraryTab;

/**
 * One document in the "books" collection (the library catalogue).
 *
 * Firestore can turn a document into this class automatically (doc.toObject(Book.class)),
 * but only because it has public fields AND an empty public constructor.
 * Students can only READ books. The librarian/admin side will add them later.
 */
public class Book {
    public String title;
    public String author;
    public String isbn;
    public String category;
    public String shelfLocation;
    public long copiesAvailable;

    public Book() { }   // required by Firestore
}

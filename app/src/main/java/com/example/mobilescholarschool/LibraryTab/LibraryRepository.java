package com.example.mobilescholarschool.LibraryTab;

import com.google.android.gms.tasks.Task;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;

/**
 * The ONE place that knows the Firestore collection names for the Library tab.
 * The Activity asks this class for data and never types "books" itself, so a
 * rename only ever touches this file.
 */
public class LibraryRepository {

    private static final String BOOKS = "books";

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    /**
     * The whole catalogue, A to Z by title.
     * Sorting on ONE field needs no manual index: Firestore builds that one automatically.
     */
    public Task<QuerySnapshot> loadCatalogue() {
        return db.collection(BOOKS).orderBy("title").get();
    }

    // REMOVED: submitRequest(). Requests are now saved by BookRequestRepository (SQLite) and
    // delivered to "bookRequests" by SyncWorker, so this class only READS the catalogue.
}
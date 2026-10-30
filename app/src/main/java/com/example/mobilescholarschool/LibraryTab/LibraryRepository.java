package com.example.mobilescholarschool.LibraryTab;

import com.google.android.gms.tasks.Task;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.Map;

/**
 * The ONE place that knows the Firestore collection names for the Library tab.
 * The Activity asks this class for data and never types "books" itself, so a
 * rename only ever touches this file.
 */
public class LibraryRepository {

    private static final String BOOKS = "books";
    private static final String REQUESTS = "bookRequests";

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    /**
     * The whole catalogue, A to Z by title.
     * Sorting on ONE field needs no manual index: Firestore builds that one automatically.
     */
    public Task<QuerySnapshot> loadCatalogue() {
        return db.collection(BOOKS).orderBy("title").get();
    }

    /**
     * NEW: saves a new book request with an automatic document ID.
     * The security rules decide whether it is accepted (right fields, own UID, status "pending"...).
     */
    public Task<DocumentReference> submitRequest(Map<String, Object> request) {
        return db.collection(REQUESTS).add(request);
    }
}
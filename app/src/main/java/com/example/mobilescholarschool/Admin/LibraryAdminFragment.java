package com.example.mobilescholarschool.Admin;

import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.mobilescholarschool.R;
import com.google.firebase.firestore.AggregateSource;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

/** Tab 1: Library Management. */
public class LibraryAdminFragment extends AdminScreenFragment {

    private TextView[] stats;   // Books, Requests, Issued, Overdue

    @Override protected String screenTitle() { return getString(R.string.admin_title_library); }

    @Override protected String screenSubtitle() { return "Catalogue, requests, loans and overdue books"; }

    @Override protected void build(LinearLayout content) {
        AdminUi.sectionTitle(requireContext(), content, "Library overview");
        stats = AdminUi.statRow(requireContext(), content, "Books", "Pending", "Issued", "Overdue");
        AdminUi.sectionTitle(requireContext(), content, "Library services");
        AdminUi.serviceRow(requireContext(), content, R.drawable.ic_library, "Manage Books",
                "Add, edit and remove books, and manage inventory.", v -> open(new BookListDialog(), "books"));
        AdminUi.serviceRow(requireContext(), content, R.drawable.ic_requestedbooks, "Book Requests",
                "View, approve or reject student requests.", v -> open(new BookRequestListDialog(), "requests"));
        AdminUi.serviceRow(requireContext(), content, R.drawable.ic_borrowedbooks, "Issued & Returned Books",
                "Track every book that is out or back.", v -> open(LoanListDialog.newInstance("issued-all"), "loans"));
        AdminUi.serviceRow(requireContext(), content, R.drawable.ic_notifications, "Overdue Books",
                "Monitor late returns and fines.", v -> open(LoanListDialog.newInstance("overdue"), "overdue"));
    }

    private void open(androidx.fragment.app.DialogFragment d, String tag) {
        if (getChildFragmentManager().findFragmentByTag(tag) == null) d.show(getChildFragmentManager(), tag);
    }

    @Override protected void refresh() {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("books").count().get(AggregateSource.SERVER)
                .addOnSuccessListener(s -> { if (isAdded()) stats[0].setText(String.valueOf(s.getCount())); });
        db.collection("bookRequests").whereEqualTo("status", "pending").count().get(AggregateSource.SERVER)
                .addOnSuccessListener(s -> { if (isAdded()) stats[1].setText(String.valueOf(s.getCount())); });
        // Issued = not returned. "Overdue" needs a date comparison, so we count it here on the phone.
        db.collection("loans").whereEqualTo("returnedAt", null).limit(AdminRepo.MAX_DOCS).get().addOnSuccessListener(s -> {
            if (!isAdded()) return;
            int overdue = 0;
            for (DocumentSnapshot d : s.getDocuments()) if (AdminRepo.isOverdue(AdminRepo.millis(d, "dueDate"))) overdue++;
            stats[2].setText(String.valueOf(s.size()));
            stats[3].setText(String.valueOf(overdue));
        });
    }
}

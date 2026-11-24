package com.example.mobilescholarschool.Admin;

import android.os.Bundle;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Student management list: every registered student, searchable by registration number or student
 * (ID) number, filterable by Registered / Unregistered and by account status. Tapping a student
 * opens the full profile. Optionally limited to one department (and course).
 */
public class StudentListDialog extends BaseListDialog {

    private static final String ARG_DEPT = "dept", ARG_COURSE = "course", ARG_FILTER = "filter";

    public static StudentListDialog newInstance(String department, String course, String filter) {
        StudentListDialog d = new StudentListDialog();
        Bundle b = new Bundle();
        b.putString(ARG_DEPT, department);
        b.putString(ARG_COURSE, course);
        b.putString(ARG_FILTER, filter == null ? "" : filter);
        d.setArguments(b);
        return d;
    }

    private String dept() { return requireArguments().getString(ARG_DEPT); }

    private String course() { return requireArguments().getString(ARG_COURSE); }

    @Override protected String title() {
        if (dept() == null) return "Students";
        return course() == null ? dept() : dept() + " · " + course();
    }

    @Override protected String searchHint() { return "Search registration no. or student ID"; }

    @Override protected String[][] filters() {
        return new String[][]{{"", "All"}, {"registered", "Registered"}, {"unregistered", "Unregistered"},
                {"active", "Active"}, {"suspended", "Suspended"}, {"deactivated", "Deactivated"}};
    }

    @Override protected String initialFilter() { return requireArguments().getString(ARG_FILTER, ""); }

    @Override protected String addLabel() { return "Report"; }

    @Override protected void onAdd() { ReportHelper.showRegistrationReport(requireActivity()); }

    @Override protected String emptyText() { return "No students found."; }

    @Override protected void load(Loader loader) {
        Query q = FirebaseFirestore.getInstance().collection("users");
        if (dept() != null) q = q.whereEqualTo("department", dept());
        if (course() != null) q = q.whereEqualTo("course", course());
        q.limit(AdminRepo.MAX_DOCS).get()
                .addOnSuccessListener(users -> PaymentIndex.load(new PaymentIndex.Callback() {
                    @Override public void onLoaded(PaymentIndex index) {
                        List<DocumentSnapshot> docs = new ArrayList<>(users.getDocuments());
                        java.util.Collections.sort(docs, (a, b) -> AdminRepo.str(a, "registrationNo")
                                .compareToIgnoreCase(AdminRepo.str(b, "registrationNo")));
                        List<Row> rows = new ArrayList<>();
                        for (DocumentSnapshot d : docs) rows.add(toRow(d, index));
                        loader.done(rows);
                    }
                    @Override public void onError(Exception e) { loader.fail(e); }
                }))
                .addOnFailureListener(loader::fail);
    }

    private Row toRow(DocumentSnapshot d, PaymentIndex index) {
        Row r = new Row();
        r.id = d.getId();
        r.data = d;
        String reg = AdminRepo.str(d, "registrationNo");
        String idNo = AdminRepo.str(d, "idNumber");
        String account = AdminSession.normalizeStatus(AdminRepo.str(d, "accountStatus"));
        boolean registered = index.isRegistered(d.getId());
        r.title = AdminRepo.str(d, "fullName");
        r.subtitle = reg + "  ·  ID " + idNo;
        r.extra = AdminRepo.str(d, "department") + " · " + AdminRepo.str(d, "course")
                + "  ·  " + (registered ? "Registered" : "Unregistered");
        r.status = AdminUi.cap(account);
        r.statusColor = AdminUi.colorOfStatus(account);
        r.tags.add(account);
        r.tags.add(registered ? "registered" : "unregistered");
        r.search = (reg + " " + idNo + " " + r.title + " " + AdminRepo.str(d, "email")).toLowerCase(Locale.ROOT);
        return r;
    }

    @Override protected void onRowClick(Row row) {
        showChild(StudentDetailDialog.newInstance(row.id), "student");
    }
}

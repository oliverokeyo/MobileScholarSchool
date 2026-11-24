package com.example.mobilescholarschool.Admin;

import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.mobilescholarschool.R;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

/** Tab 4: Departments &amp; Courses, which is also where Student Management lives. */
public class DepartmentsAdminFragment extends AdminScreenFragment {

    private TextView[] stats;   // Students, Registered, Unregistered

    @Override protected String screenTitle() { return getString(R.string.admin_title_departments); }

    @Override protected String screenSubtitle() { return "Students, registration and their departments"; }

    @Override protected void build(LinearLayout content) {
        AdminUi.sectionTitle(requireContext(), content, "Students overview");
        stats = AdminUi.statRow(requireContext(), content, "Students", "Registered", "Unregistered");
        AdminUi.sectionTitle(requireContext(), content, "Student management");
        AdminUi.serviceRow(requireContext(), content, R.drawable.ic_student, "All Students",
                "Search, view, update and manage accounts.", v -> open(StudentListDialog.newInstance(null, null, ""), "students"));
        AdminUi.serviceRow(requireContext(), content, R.drawable.ic_payments, "Registered Students",
                "Payment confirmed.", v -> open(StudentListDialog.newInstance(null, null, "registered"), "registered"));
        AdminUi.serviceRow(requireContext(), content, R.drawable.ic_notifications, "Unregistered Students",
                "No payment made yet.", v -> open(StudentListDialog.newInstance(null, null, "unregistered"), "unregistered"));
        AdminUi.serviceRow(requireContext(), content, R.drawable.ic_requestedbooks, "Registration List",
                "Generate the registered / unregistered list.", v -> ReportHelper.showRegistrationReport(requireActivity()));
        AdminUi.sectionTitle(requireContext(), content, "Departments & courses");
        AdminUi.serviceRow(requireContext(), content, R.drawable.ic_units, "Browse Departments",
                "See courses and students in each department.", v -> open(new DepartmentListDialog(), "departments"));
    }

    private void open(androidx.fragment.app.DialogFragment d, String tag) {
        if (getChildFragmentManager().findFragmentByTag(tag) == null) d.show(getChildFragmentManager(), tag);
    }

    @Override protected void refresh() {
        FirebaseFirestore.getInstance().collection("users").limit(AdminRepo.MAX_DOCS).get().addOnSuccessListener(users ->
                PaymentIndex.load(new PaymentIndex.Callback() {
                    @Override public void onLoaded(PaymentIndex index) {
                        if (!isAdded()) return;
                        int registered = 0;
                        for (DocumentSnapshot d : users.getDocuments()) if (index.isRegistered(d.getId())) registered++;
                        stats[0].setText(String.valueOf(users.size()));
                        stats[1].setText(String.valueOf(registered));
                        stats[2].setText(String.valueOf(users.size() - registered));
                    }
                    @Override public void onError(Exception e) {}
                }));
    }
}

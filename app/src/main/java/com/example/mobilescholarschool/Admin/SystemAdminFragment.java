package com.example.mobilescholarschool.Admin;

import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.mobilescholarschool.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;

/** Tab 5: System. Shows who is signed in and lets the administrator log out. */
public class SystemAdminFragment extends AdminScreenFragment {

    private TextView txtAccount;

    @Override protected String screenTitle() { return getString(R.string.admin_title_system); }

    @Override protected String screenSubtitle() { return "Your administrator account"; }

    @Override protected void build(LinearLayout content) {
        AdminUi.sectionTitle(requireContext(), content, "Signed in as");
        txtAccount = AdminUi.body(requireContext(), content, "Loading…");
        AdminUi.sectionTitle(requireContext(), content, "Session");
        AdminUi.serviceRow(requireContext(), content, R.drawable.ic_student, "Logout",
                "Sign out of the administrator account.", v -> new MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Log out?")
                        .setMessage("You will need to sign in again to manage the school.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Log out", (d, w) -> AdminSession.signOutToSignIn(requireActivity()))
                        .show());
    }

    @Override protected void refresh() {
        String uid = FirebaseAuth.getInstance().getUid();
        String email = FirebaseAuth.getInstance().getCurrentUser() == null ? "" : FirebaseAuth.getInstance().getCurrentUser().getEmail();
        if (uid == null) return;
        FirebaseFirestore.getInstance().collection("admins").document(uid).get().addOnSuccessListener(doc -> {
            if (!isAdded()) return;
            txtAccount.setText(AdminRepo.str(doc, "fullName") + "\n" + email + "\nRole: Administrator");
        }).addOnFailureListener(e -> {
            if (isAdded()) txtAccount.setText(email + "\nRole: Administrator");
        });
    }
}

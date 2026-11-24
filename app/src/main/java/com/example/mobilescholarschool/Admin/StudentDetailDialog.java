package com.example.mobilescholarschool.Admin;

import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;

import com.example.mobilescholarschool.R;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One student's profile: details, edit, activate / suspend / deactivate, and their hostel, library
 * and payment history. Five reads run in parallel (Tasks.whenAllComplete) and the screen is drawn
 * when all of them are finished.
 */
public class StudentDetailDialog extends DialogFragment {

    private static final String ARG_UID = "uid";

    public static StudentDetailDialog newInstance(String uid) {
        StudentDetailDialog d = new StudentDetailDialog();
        Bundle b = new Bundle();
        b.putString(ARG_UID, uid);
        d.setArguments(b);
        return d;
    }

    private LinearLayout content;
    private String uid;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(DialogFragment.STYLE_NORMAL, R.style.Theme_MobileScholarSchool);
        uid = requireArguments().getString(ARG_UID);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        ScrollView scroll = new ScrollView(requireContext());
        scroll.setBackgroundResource(R.color.bg_main);
        scroll.setClipToPadding(false);
        content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = AdminUi.dp(requireContext(), 16);
        content.setPadding(pad, pad, pad, pad * 2);
        scroll.addView(content);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        return scroll;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        load();
    }

    private void load() {
        content.removeAllViews();
        AdminUi.body(requireContext(), content, "Loading…");
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        Task<DocumentSnapshot> userT = db.collection("users").document(uid).get();
        Task<QuerySnapshot> payT = db.collection("payments").whereEqualTo("studentUid", uid).get();
        Task<QuerySnapshot> loanT = db.collection("loans").whereEqualTo("studentUid", uid).get();
        Task<QuerySnapshot> reqT = db.collection("bookRequests").whereEqualTo("studentUid", uid).get();
        Task<QuerySnapshot> hostT = db.collection("hostelBookings").whereEqualTo("studentUid", uid).get();
        Tasks.whenAllComplete(userT, payT, loanT, reqT, hostT).addOnSuccessListener(done -> {
            if (!isAdded()) return;
            if (!userT.isSuccessful() || !userT.getResult().exists()) {
                content.removeAllViews();
                header("Student profile");
                AdminUi.body(requireContext(), content, "Could not load this student.");
                return;
            }
            draw(userT.getResult(), docs(payT), docs(loanT), docs(reqT), docs(hostT));
        });
    }

    private static List<DocumentSnapshot> docs(Task<QuerySnapshot> t) {
        return t.isSuccessful() ? new ArrayList<>(t.getResult().getDocuments()) : new ArrayList<>();
    }

    private void header(String text) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(requireContext());
        back.setImageResource(R.drawable.ic_forward);
        back.setRotation(180);
        back.setBackground(null);
        back.setContentDescription("Back");
        back.setOnClickListener(v -> dismiss());
        row.addView(back, new LinearLayout.LayoutParams(AdminUi.dp(requireContext(), 44), AdminUi.dp(requireContext(), 44)));
        TextView title = new TextView(requireContext());
        title.setText(text);
        title.setTextAppearance(R.style.TextAppearance_App_Section);
        row.addView(title);
        content.addView(row);
    }

    private void draw(DocumentSnapshot user, List<DocumentSnapshot> payments, List<DocumentSnapshot> loans,
                      List<DocumentSnapshot> requests, List<DocumentSnapshot> bookings) {
        content.removeAllViews();
        header("Student profile");

        long paid = 0;
        for (DocumentSnapshot p : payments) if (AdminRepo.status(p).equals("success")) paid += AdminRepo.num(p, "amount");
        boolean registered = paid > 0;
        String account = AdminSession.normalizeStatus(AdminRepo.str(user, "accountStatus"));

        AdminUi.sectionTitle(requireContext(), content, AdminRepo.str(user, "fullName"));
        AdminUi.body(requireContext(), content,
                "Registration no.: " + AdminRepo.str(user, "registrationNo")
                + "\nStudent ID number: " + AdminRepo.str(user, "idNumber")
                + "\nEmail: " + AdminRepo.str(user, "email")
                + "\nGender: " + AdminRepo.str(user, "gender")
                + "\nDepartment: " + AdminRepo.str(user, "department")
                + "\nCourse: " + AdminRepo.str(user, "course")
                + "\nAccount: " + AdminUi.cap(account)
                + "\nRegistration: " + (registered ? "Registered" : "Unregistered")
                + "\nTotal paid: " + AdminUi.ksh(paid));

        LinearLayout actions = new LinearLayout(requireContext());
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.addView(AdminUi.button(requireContext(), "Update student information", true, v -> edit(user)));
        if (!account.equals(AdminSession.STATUS_ACTIVE)) {
            actions.addView(AdminUi.button(requireContext(), "Activate account", false,
                    v -> setStatus("Activate", AdminSession.STATUS_ACTIVE)));
        }
        if (!account.equals(AdminSession.STATUS_SUSPENDED)) {
            actions.addView(AdminUi.button(requireContext(), "Suspend account", false,
                    v -> setStatus("Suspend", AdminSession.STATUS_SUSPENDED)));
        }
        if (!account.equals(AdminSession.STATUS_DEACTIVATED)) {
            actions.addView(AdminUi.button(requireContext(), "Deactivate account", false,
                    v -> setStatus("Deactivate", AdminSession.STATUS_DEACTIVATED)));
        }
        content.addView(actions);

        // ---- history: hostel ----
        AdminRepo.sortNewest(bookings, "createdAt");
        List<String> hostel = new ArrayList<>();
        for (DocumentSnapshot b : bookings) {
            hostel.add(AdminRepo.str(b, "hostelName") + " · "
                    + (AdminRepo.str(b, "roomNumber").isEmpty() ? "no room yet" : "Room " + AdminRepo.str(b, "roomNumber"))
                    + " · " + AdminUi.ksh(AdminRepo.num(b, "rentPerMonth")) + "/month"
                    + "\n" + AdminUi.cap(AdminRepo.status(b)) + " · applied " + AdminUi.date(AdminRepo.millis(b, "createdAt"))
                    + (AdminRepo.millis(b, "checkInDate") > 0 ? " · check-in " + AdminUi.date(AdminRepo.millis(b, "checkInDate")) : ""));
        }
        history("Hostel history", hostel);

        // ---- history: library ----
        AdminRepo.sortNewest(loans, "issuedAt");
        List<String> library = new ArrayList<>();
        for (DocumentSnapshot l : loans) {
            boolean returned = l.get("returnedAt") != null;
            long due = AdminRepo.millis(l, "dueDate");
            String state = returned ? "Returned " + AdminUi.date(AdminRepo.millis(l, "returnedAt"))
                    : AdminRepo.isOverdue(due) ? "OVERDUE" : "Issued";
            library.add(AdminRepo.str(l, "bookTitle") + "\n" + state + " · issued "
                    + AdminUi.date(AdminRepo.millis(l, "issuedAt")) + " · due " + AdminUi.date(due)
                    + (AdminRepo.num(l, "fineAmount") > 0 ? " · fine " + AdminUi.ksh(AdminRepo.num(l, "fineAmount")) : ""));
        }
        AdminRepo.sortNewest(requests, "createdAt");
        for (DocumentSnapshot q : requests) {
            String t = AdminRepo.str(q, "bookTitle").isEmpty() ? AdminRepo.str(q, "isbn") : AdminRepo.str(q, "bookTitle");
            library.add("Request: " + t + "\n" + AdminUi.cap(AdminRepo.status(q)) + " · "
                    + AdminUi.date(AdminRepo.millis(q, "createdAt")));
        }
        history("Library history", library);

        // ---- history: payments ----
        AdminRepo.sortNewest(payments, "createdAt");
        List<String> pay = new ArrayList<>();
        for (DocumentSnapshot p : payments) {
            String code = AdminRepo.str(p, "mpesaReceipt").isEmpty() ? AdminRepo.str(p, "checkoutRequestId")
                    : AdminRepo.str(p, "mpesaReceipt");
            pay.add(AdminUi.ksh(AdminRepo.num(p, "amount")) + " · " + AdminUi.cap(AdminRepo.status(p))
                    + "\n" + AdminUi.dateTime(AdminRepo.millis(p, "createdAt")) + " · " + code);
        }
        history("Payment history", pay);
    }

    private void history(String heading, List<String> lines) {
        AdminUi.sectionTitle(requireContext(), content, heading);
        if (lines.isEmpty()) {
            AdminUi.body(requireContext(), content, "Nothing yet.");
            return;
        }
        for (String line : lines) {
            TextView tv = AdminUi.body(requireContext(), content, line);
            tv.setBackgroundResource(R.color.card_white);
            int p = AdminUi.dp(requireContext(), 12);
            tv.setPadding(p, p, p, p);
            ((LinearLayout.LayoutParams) tv.getLayoutParams()).bottomMargin = AdminUi.dp(requireContext(), 6);
        }
    }

    private void setStatus(String verb, String status) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(verb + " this account?")
                .setMessage(status.equals(AdminSession.STATUS_ACTIVE)
                        ? "The student will be able to use the app again."
                        : "The student is signed out the next time they open the app and cannot make new requests, bookings or payments.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton(verb, (d, w) -> AdminRepo.setAccountStatus(uid, status, new AdminRepo.Result() {
                    @Override public void ok() { AdminUi.toast(getContext(), "Account " + status + "."); if (isAdded()) load(); }
                    @Override public void fail(String m) { AdminUi.toast(getContext(), m); }
                }))
                .show();
    }

    private void edit(DocumentSnapshot user) {
        new FormBuilder(requireContext(), "Update student information")
                .text("fullName", "Full name", AdminRepo.str(user, "fullName"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS)
                .text("idNumber", "Student ID number", AdminRepo.str(user, "idNumber"), InputType.TYPE_CLASS_TEXT)
                .text("registrationNo", "Registration number", AdminRepo.str(user, "registrationNo"), InputType.TYPE_CLASS_TEXT)
                .choice("gender", "Gender", new String[]{"Male", "Female"}, AdminRepo.str(user, "gender"))
                // Same lists as the Sign Up screen (arrays.xml), so an edit can never invent a department
                .choice("department", "Department",
                        AdminUi.listFromArray(requireContext(), R.array.department_list, AdminRepo.str(user, "department")),
                        AdminRepo.str(user, "department"))
                .choice("course", "Course",
                        AdminUi.listFromArray(requireContext(), R.array.course_list, AdminRepo.str(user, "course")),
                        AdminRepo.str(user, "course"))
                .show("Save", values -> {
                    // The SAME limits the Firestore rules enforce, so the admin sees a friendly message first.
                    String name = values.get("fullName"), idNo = values.get("idNumber"), reg = values.get("registrationNo");
                    if (name.isEmpty() || name.length() > 100) return "Enter a name (up to 100 characters).";
                    if (idNo.isEmpty() || idNo.length() > 20) return "Enter an ID number (up to 20 characters).";
                    if (reg.isEmpty() || reg.length() > 30) return "Enter a registration number (up to 30 characters).";
                    if (values.get("department").isEmpty()) return "Enter the department.";
                    Map<String, Object> m = new HashMap<>();
                    m.put("fullName", name);
                    m.put("idNumber", idNo);
                    m.put("registrationNo", reg);
                    m.put("gender", values.get("gender"));
                    m.put("department", values.get("department"));
                    if (!values.get("course").isEmpty()) m.put("course", values.get("course"));
                    AdminRepo.updateStudent(uid, m, new AdminRepo.Result() {
                        @Override public void ok() { AdminUi.toast(getContext(), "Student updated."); if (isAdded()) load(); }
                        @Override public void fail(String msg) { AdminUi.toast(getContext(), msg); }
                    });
                    return null;
                });
    }

    /** When this screen closes, the student list underneath refreshes (status or details may have changed). */
    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        Fragment parent = getParentFragment();
        if (parent instanceof BaseListDialog && parent.isAdded()) {
            ((BaseListDialog) parent).reload();
        }
    }
}

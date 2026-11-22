package com.example.mobilescholarschool.ProfileTab;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.mobilescholarschool.Components.BottomNavHelper;
import com.example.mobilescholarschool.Components.SessionUser;                  // NEW
import com.example.mobilescholarschool.Components.SwipeNavigator;
import com.example.mobilescholarschool.Data.AppDatabase;                 // NEW
import com.example.mobilescholarschool.Data.DataChangeNotifier;          // NEW
import com.example.mobilescholarschool.Data.DbExecutor;                  // NEW
import com.example.mobilescholarschool.Data.Outbox;                      // NEW
import com.example.mobilescholarschool.Data.ProfileRepository;           // NEW
import com.example.mobilescholarschool.R;
import com.example.mobilescholarschool.SignIn.SignInActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;


public class ProfileActivity extends AppCompatActivity {

    // Drag-to-switch-tab with spring animation. Null until the screen is set up.
    private SwipeNavigator swipeNavigator;

    FirebaseAuth auth;
    FirebaseUser user;          // CHANGED: null for a student whose offline sign-up is still waiting

    // NEW: the id to save under (real uid OR provisional id), the email to show, and the mode
    String uid;
    String email;
    boolean provisional;
    FirebaseFirestore db;
    ListenerRegistration profileListener;   // lets us stop listening when we sign out

    // NEW: when the local copy of the profile changes (server update, or our own edit), redraw
    private final DataChangeNotifier.Listener dataListener = table -> {
        if (AppDatabase.T_PROFILE.equals(table)) {
            loadLocalProfile();
        }
    };

    // Kept in a FIELD (not a local variable) so it can't be garbage collected
    // while the invisible WebView is still rendering the print page.
    WebView printWebView;

    Button btnSignOut, btnChange, btnPrintSummary;
    TextView txtViewProfileName,
            txtViewProfileEmail,
            txtViewProfileRegNo,
            txtViewProfileIdNumber,
            txtViewProfileGender,
            txtViewProfileCourse,
            txtViewProfileDepartment;

    // The latest saved values. They pre-fill the edit dialog and feed the screen.
    String currentFullName, currentIdNumber, currentRegNo, currentGender,
            currentCourse, currentDepartment;

    /**
     * Every touch on this screen passes through here BEFORE any view sees it. We ask the swipe
     * navigator first: if the user is dragging sideways it takes over (returns true) and the
     * views underneath never see that gesture. Otherwise everything works exactly as before.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (swipeNavigator != null && swipeNavigator.onTouchEvent(ev)) {
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_profile);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        auth = FirebaseAuth.getInstance();
        user = auth.getCurrentUser();
        uid = SessionUser.uid(this);
        provisional = user == null;

        // Nobody signed in AND no waiting sign-up -> go to Sign In and STOP
        if (uid == null) {
            goToSignIn();
            return;
        }

        // Signed in: highlight the Profile tab and make the other tabs navigate.
        // After the null check so a signed-out user is never shown the bar.
        BottomNavHelper.setup(this, R.id.nav_profile);
        // Must come AFTER BottomNavHelper.setup (it reads what setup leaves behind).
        swipeNavigator = new SwipeNavigator(this, R.id.nav_profile);

        db = FirebaseFirestore.getInstance();

        btnSignOut = findViewById(R.id.btnSignOut);
        btnChange = findViewById(R.id.btnChange);
        btnPrintSummary = findViewById(R.id.btnPrintSummary);
        txtViewProfileName = findViewById(R.id.txtViewProfileName);
        txtViewProfileEmail = findViewById(R.id.txtViewProfileEmail);
        txtViewProfileRegNo = findViewById(R.id.txtViewProfileRegNo);
        txtViewProfileIdNumber = findViewById(R.id.txtViewProfileIdNumber);
        txtViewProfileGender = findViewById(R.id.txtViewProfileGender);
        txtViewProfileCourse = findViewById(R.id.txtViewProfileCourse);
        txtViewProfileDepartment = findViewById(R.id.txtViewProfileDepartment);

        // The email comes from Firebase Auth, so it can be shown immediately.
        // The layout now holds the label ("Email"), so we set ONLY the value here.
        // (A waiting sign-up has no Firebase email yet: loadLocalProfile fills it in.)
        email = user != null ? user.getEmail() : null;
        txtViewProfileEmail.setText(orDash(email));

        // Nothing to change or print until the profile has loaded
        btnChange.setEnabled(false);
        btnPrintSummary.setEnabled(false);

        loadLocalProfile();   // NEW: show the saved copy instantly, even with no internet
        if (!provisional) {
            listenToProfile();    // keeps that copy in step with the server (needs a real account)
        }

        btnChange.setOnClickListener(v -> showEditDialog());
        btnPrintSummary.setOnClickListener(v -> printSummary());
        if (provisional) {
            btnSignOut.setText("Cancel registration");   // there is no account to sign out of yet
        }
        btnSignOut.setOnClickListener(v -> signOut());
    }

    @Override
    protected void onStart() {
        super.onStart();
        DataChangeNotifier.register(dataListener);   // NEW
    }

    @Override
    protected void onStop() {
        super.onStop();
        DataChangeNotifier.unregister(dataListener); // NEW
    }

    /**
     * NEW: reads the profile from the LOCAL database and paints it. This is now the ONLY thing
     * that fills the screen. Server -> local database -> screen.
     */
    private void loadLocalProfile() {
        DbExecutor.async(() -> ProfileRepository.get(getApplicationContext(), uid), p -> {
            if (p == null || isFinishing() || isDestroyed()) {
                return;
            }
            currentFullName = p.fullName;
            currentIdNumber = p.idNumber;
            currentRegNo = p.registrationNo;
            currentGender = p.gender;
            currentCourse = p.course;
            currentDepartment = p.department;
            if (provisional && p.email != null && !p.email.isEmpty()) {
                email = p.email;
                txtViewProfileEmail.setText(orDash(email));
            }

            displayProfile();
            btnChange.setEnabled(true);         // safe to edit now
            btnPrintSummary.setEnabled(true);   // and safe to print
        });
    }

    /**
     * CHANGED: live connection to users/{uid}. It no longer paints the screen; it COPIES each
     * snapshot into the local database, and the notifier then makes loadLocalProfile() repaint.
     * While the student has an unsent edit, saveFromServer() leaves it alone (local edits win
     * until delivered).
     */
    private void listenToProfile() {
        profileListener = db.collection("users").document(uid)
                .addSnapshotListener(this, (doc, error) -> {
                    if (error != null) {
                        // Log the real reason (e.g. PERMISSION_DENIED) for Logcat
                        Log.e("ProfileActivity", "Profile listener failed", error);
                        // Offline is normal now. Only complain if there is nothing to show.
                        if (currentFullName == null && currentRegNo == null) {
                            txtViewProfileName.setText(R.string.profile_load_failed);
                            Toast.makeText(ProfileActivity.this,
                                    "Could not load your profile. Connect to the internet once "
                                            + "so it can be saved on this phone.",
                                    Toast.LENGTH_LONG).show();
                        }
                        return;
                    }

                    if (doc == null || !doc.exists()) {
                        // Offline with nothing cached also looks like "missing": only claim
                        // "not found" when the SERVER said so.
                        if (doc != null && !doc.getMetadata().isFromCache() && currentFullName == null) {
                            txtViewProfileName.setText(R.string.profile_not_found);
                        }
                        return;
                    }

                    try {
                        ProfileRepository.LocalProfile copy =
                                ProfileRepository.fromSnapshot(uid, doc);
                        DbExecutor.run(() ->
                                ProfileRepository.saveFromServer(getApplicationContext(), copy));
                    } catch (RuntimeException e) {
                        Log.e("ProfileActivity", "Profile document has a wrongly typed field", e);
                    }
                });
    }

    /**
     * One place that paints the profile; used after loading AND after saving an edit.
     * (The registration status pill is not here: its text is fixed in activity_profile.xml.)
     */
    private void displayProfile() {
        txtViewProfileName.setText(orDash(currentFullName));
        txtViewProfileRegNo.setText(orDash(currentRegNo));
        txtViewProfileIdNumber.setText(orDash(currentIdNumber));
        txtViewProfileGender.setText(orDash(currentGender));
        txtViewProfileCourse.setText(orDash(currentCourse));
        txtViewProfileDepartment.setText(orDash(currentDepartment));
    }

    // =====================================================================
    //  PRINTING
    // =====================================================================

    /**
     * Step 1: build the summary as HTML and load it into an invisible WebView.
     * We must wait for onPageFinished before printing, otherwise the page is blank.
     */
    private void printSummary() {
        WebView webView = new WebView(this);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                createPrintJob(view);   // Step 2: the page is ready
                printWebView = null;    // we no longer need to hold on to it
            }
        });

        webView.loadDataWithBaseURL(null, buildSummaryHtml(), "text/HTML", "UTF-8", null);

        // Hold a reference until the page has finished loading
        printWebView = webView;
    }

    /** Step 2: hand the rendered page to Android's print system (shows the print dialog). */
    private void createPrintJob(WebView webView) {
        PrintManager printManager = (PrintManager) getSystemService(Context.PRINT_SERVICE);
        String jobName = getString(R.string.print_summary_title);

        PrintDocumentAdapter adapter = webView.createPrintDocumentAdapter(jobName);
        printManager.print(jobName, adapter, new PrintAttributes.Builder().build());
    }

    /** Builds the printable page. Every user value is HTML-encoded for safety. */
    private String buildSummaryHtml() {
        StringBuilder html = new StringBuilder();
        html.append("<html><head><meta charset='utf-8'>")
                .append("<style>")
                .append("body{font-family:sans-serif;padding:24px;}")
                .append("h1{font-size:22px;margin:0 0 4px 0;}")
                .append("p{margin:0;font-size:14px;}")
                .append("table{width:100%;border-collapse:collapse;margin-top:20px;}")
                .append("td{padding:10px 4px;border-bottom:1px solid #cccccc;font-size:14px;}")
                .append("td.value{text-align:right;font-weight:bold;}")
                .append("</style></head><body>");

        html.append("<h1>").append(TextUtils.htmlEncode(orDash(currentFullName))).append("</h1>");
        // Same placeholder text as the on-screen status pill
        html.append("<p>")
                .append(TextUtils.htmlEncode(getString(R.string.registration_status_placeholder)))
                .append("</p>");

        html.append("<table>");
        appendRow(html, R.string.profile_label_email, email);
        appendRow(html, R.string.profile_label_reg_no, currentRegNo);
        appendRow(html, R.string.profile_label_id_number, currentIdNumber);
        appendRow(html, R.string.profile_label_gender, currentGender);
        appendRow(html, R.string.profile_label_course, currentCourse);
        appendRow(html, R.string.profile_label_department, currentDepartment);
        html.append("</table></body></html>");

        return html.toString();
    }

    /** Adds one "label ........ value" table row to the printable page. */
    private void appendRow(StringBuilder html, int labelRes, String value) {
        html.append("<tr><td>")
                .append(TextUtils.htmlEncode(getString(labelRes)))
                .append("</td><td class='value'>")
                .append(TextUtils.htmlEncode(orDash(value)))
                .append("</td></tr>");
    }

    // =====================================================================
    //  EDITING (opened by the "Change" button)
    // =====================================================================

    private void showEditDialog() {
        // Inflate = turn dialog_edit_details.xml into real View objects
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_edit_details, null);

        EditText dlgFullName = dialogView.findViewById(R.id.dlgFullName);
        EditText dlgIdNumber = dialogView.findViewById(R.id.dlgIdNumber);
        EditText dlgRegNo = dialogView.findViewById(R.id.dlgRegNo);
        RadioGroup dlgGenderGroup = dialogView.findViewById(R.id.dlgGenderGroup);
        Spinner dlgCourse = dialogView.findViewById(R.id.dlgCourse);          // NEW
        Spinner dlgDepartment = dialogView.findViewById(R.id.dlgDepartment);

        // Pre-fill with what is saved now
        dlgFullName.setText(currentFullName);
        dlgIdNumber.setText(currentIdNumber);
        dlgRegNo.setText(currentRegNo);
        if ("Male".equals(currentGender)) {
            dlgGenderGroup.check(R.id.dlgMale);
        } else if ("Female".equals(currentGender)) {
            dlgGenderGroup.check(R.id.dlgFemale);
        }
        dlgCourse.setSelection(indexOfCourse(currentCourse));                 // NEW
        dlgDepartment.setSelection(indexOfDepartment(currentDepartment));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.title_edit_details)
                .setView(dialogView)
                .setNegativeButton(R.string.btn_cancel, null)
                // null here on purpose: we attach our own click listener below
                .setPositiveButton(R.string.btn_save, null)
                .create();
        dialog.show();

        // Must be attached AFTER show(). A listener passed to setPositiveButton would
        // close the dialog on every tap, even when the input is invalid.
        Button saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        saveButton.setOnClickListener(v -> {
            String fullName = dlgFullName.getText().toString().trim();
            String idNumber = dlgIdNumber.getText().toString().trim();
            String regNo = dlgRegNo.getText().toString().trim();

            if (!isEditFormValid(dlgFullName, dlgIdNumber, dlgRegNo,
                    dlgGenderGroup, dlgCourse, dlgDepartment, fullName, idNumber, regNo)) {
                return;   // dialog stays open so the user can fix the problem
            }

            // CHANGED: the online check is gone. Offline edits are now saved on the phone.

            String gender = dlgGenderGroup.getCheckedRadioButtonId() == R.id.dlgMale
                    ? "Male" : "Female";
            String course = dlgCourse.getSelectedItem().toString();           // NEW
            String department = dlgDepartment.getSelectedItem().toString();

            saveChanges(dialog, fullName, idNumber, regNo, gender, course, department);
        });
    }

    private boolean isEditFormValid(EditText dlgFullName, EditText dlgIdNumber, EditText dlgRegNo,
                                    RadioGroup dlgGenderGroup, Spinner dlgCourse,
                                    Spinner dlgDepartment,
                                    String fullName, String idNumber, String regNo) {
        if (fullName.isEmpty()) {
            dlgFullName.setError("Full name is required");
            dlgFullName.requestFocus();
            return false;
        }
        if (idNumber.isEmpty()) {
            dlgIdNumber.setError("ID number is required");
            dlgIdNumber.requestFocus();
            return false;
        }
        if (regNo.isEmpty()) {
            dlgRegNo.setError("Registration number is required");
            dlgRegNo.requestFocus();
            return false;
        }
        if (!regNo.matches("[A-Za-z0-9/\\-]+")) {
            dlgRegNo.setError("Use letters and numbers only");
            dlgRegNo.requestFocus();
            return false;
        }
        if (dlgGenderGroup.getCheckedRadioButtonId() == -1) {
            Toast.makeText(this, "Please select a gender", Toast.LENGTH_SHORT).show();
            return false;
        }
        // NEW: position 0 is the "Select Course" prompt, so it is not a real choice
        if (dlgCourse.getSelectedItemPosition() == 0) {
            Toast.makeText(this, "Please select a course", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (dlgDepartment.getSelectedItemPosition() == 0) {
            Toast.makeText(this, "Please select a department", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void saveChanges(AlertDialog dialog, String fullName, String idNumber,
                             String regNo, String gender, String course, String department) {

        // The email is NOT here: it belongs to Firebase Auth, not to this profile document.
        // "registrationStatus" is not here either: the rules only let the admin side change it.
        ProfileRepository.LocalProfile edit = new ProfileRepository.LocalProfile();
        edit.uid = uid;
        edit.email = email;
        edit.fullName = fullName;
        edit.idNumber = idNumber;
        edit.registrationNo = regNo;
        edit.gender = gender;
        edit.course = course;
        edit.department = department;

        // CHANGED: save on the phone first. SyncWorker sends it to Firestore (a merge, so email
        // and createdAt stay untouched) as soon as there is internet.
        DbExecutor.async(() -> {
            ProfileRepository.saveLocalEdit(getApplicationContext(), edit);
            return Boolean.TRUE;
        }, saved -> Toast.makeText(getApplicationContext(),
                saved == null
                        ? "Could not save changes. Your details were not updated."
                        : "Details saved. They are sent to the server automatically.",
                saved == null ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show());

        // Closing right away is fine: the notifier repaints the screen from the local copy
        dialog.dismiss();
    }

    /** Finds the saved course in the dropdown list; 0 ("Select Course") if not found. */
    private int indexOfCourse(String course) {
        // ASSUMPTION: the array is named course_list. Change it if yours differs.
        String[] courses = getResources().getStringArray(R.array.course_list);
        for (int i = 0; i < courses.length; i++) {
            if (courses[i].equals(course)) {
                return i;
            }
        }
        return 0;
    }

    /** Finds the saved department in the dropdown list; 0 ("Select Department") if not found. */
    private int indexOfDepartment(String department) {
        String[] departments = getResources().getStringArray(R.array.department_list);
        for (int i = 0; i < departments.length; i++) {
            if (departments[i].equals(department)) {
                return i;
            }
        }
        return 0;
    }

    // =====================================================================
    //  SIGN OUT / HELPERS
    // =====================================================================

    /**
     * CHANGED: warns first if something saved on this phone has not reached the server.
     * Unsent items are KEPT and are delivered the next time this student signs in while online.
     *
     * For a student whose sign-up is still waiting there is no account to sign out of, so the
     * same button becomes "Cancel registration": it throws the waiting sign-up away.
     */
    private void signOut() {
        if (provisional) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Cancel registration?")
                    .setMessage("Your account has not been created yet. If you cancel, your saved "
                            + "registration and everything you saved offline will be discarded.")
                    .setNegativeButton("Keep waiting", null)
                    .setPositiveButton("Cancel registration", (d, which) -> cancelRegistration())
                    .show();
            return;
        }
        DbExecutor.async(() -> Outbox.countFor(getApplicationContext(), uid), count -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (count != null && count > 0) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Unsent changes")
                        .setMessage(count + " item(s) saved on this phone have not reached the "
                                + "server yet. If you sign out now they will be sent the next time "
                                + "you sign in while online. Sign out anyway?")
                        .setNegativeButton(R.string.btn_cancel, null)
                        .setPositiveButton("Sign out", (d, which) -> performSignOut())
                        .show();
            } else {
                performSignOut();
            }
        });
    }

    private void performSignOut() {
        // Stop listening FIRST: once signed out, the rules would reject this read
        if (profileListener != null) {
            profileListener.remove();
        }
        // Privacy mop-up: forget this student's data that the server already has.
        // (Unsent rows stay, so no work is lost.)
        DbExecutor.run(() -> AppDatabase.get(getApplicationContext()).clearSyncedFor(uid));
        SessionUser.clearProvisional(this);   // a leftover marker would bounce Sign In straight back in
        auth.signOut();
        goToSignIn();
    }

    /** Waits until the saved sign-up is really gone, THEN opens Sign In (which would otherwise bounce back). */
    private void cancelRegistration() {
        DbExecutor.async(() -> {
            SessionUser.discardPendingRegistration(getApplicationContext());
            return Boolean.TRUE;
        }, done -> goToSignIn());
    }

    private void goToSignIn() {
        Intent intent = new Intent(ProfileActivity.this, SignInActivity.class);
        // Clear the back stack so Back can't return to a signed-out profile screen
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    /** Shows "-" instead of the word "null" when a field is missing. */
    private String orDash(String value) {
        return (value == null || value.isEmpty()) ? "-" : value;
    }
}
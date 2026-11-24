package com.example.mobilescholarschool.Admin;

import com.example.mobilescholarschool.R;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Departments and the courses inside them. They are DISCOVERED from the students' own profiles (the
 * "department" and "course" fields), so the admin sees exactly what students chose at sign up and
 * no separate list has to be kept in step.
 */
public class DepartmentListDialog extends BaseListDialog {

    @Override protected String title() { return "Departments & Courses"; }

    @Override protected String searchHint() { return "Search department or course"; }

    @Override protected String emptyText() { return "No students have signed up yet."; }

    @Override protected void load(Loader loader) {
        // Every department from the Sign Up drop down is listed, even before anyone has joined it.
        final String[] knownDepartments = AdminUi.listFromArray(requireContext(), R.array.department_list, "");
        FirebaseFirestore.getInstance().collection("users").limit(AdminRepo.MAX_DOCS).get()
                .addOnSuccessListener(users -> PaymentIndex.load(new PaymentIndex.Callback() {
                    @Override public void onLoaded(PaymentIndex index) {
                        // department -> course -> {students, registered}
                        Map<String, Map<String, int[]>> tree = new TreeMap<>();
                        for (String known : knownDepartments) tree.put(known, new TreeMap<>());
                        for (DocumentSnapshot d : users.getDocuments()) {
                            String dept = AdminRepo.str(d, "department").trim();
                            String course = AdminRepo.str(d, "course").trim();
                            if (dept.isEmpty()) dept = "(No department)";
                            if (course.isEmpty()) course = "(No course)";
                            Map<String, int[]> courses = tree.get(dept);
                            if (courses == null) { courses = new TreeMap<>(); tree.put(dept, courses); }
                            int[] counts = courses.get(course);
                            if (counts == null) { counts = new int[2]; courses.put(course, counts); }
                            counts[0]++;
                            if (index.isRegistered(d.getId())) counts[1]++;
                        }
                        List<Row> rows = new ArrayList<>();
                        for (Map.Entry<String, Map<String, int[]>> e : tree.entrySet()) {
                            int students = 0, registered = 0;
                            for (int[] c : e.getValue().values()) { students += c[0]; registered += c[1]; }
                            Row r = new Row();
                            r.id = e.getKey();
                            r.title = e.getKey();
                            r.subtitle = students + " student(s) · " + registered + " registered";
                            r.extra = e.getValue().isEmpty() ? "No students yet"
                                    : e.getValue().size() + " course(s): " + String.join(", ", e.getValue().keySet());
                            r.search = (e.getKey() + " " + String.join(" ", e.getValue().keySet())).toLowerCase();
                            r.data = e.getValue();
                            rows.add(r);
                        }
                        loader.done(rows);
                    }
                    @Override public void onError(Exception e) { loader.fail(e); }
                }))
                .addOnFailureListener(loader::fail);
    }

    @SuppressWarnings("unchecked")
    @Override protected void onRowClick(Row row) {
        final String dept = row.id.equals("(No department)") ? null : row.id;
        Map<String, int[]> courses = (Map<String, int[]>) row.data;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("All students in " + row.id);
        actions.add(() -> showChild(StudentListDialog.newInstance(dept, null, ""), "students"));
        for (Map.Entry<String, int[]> c : courses.entrySet()) {
            if (dept == null || c.getKey().startsWith("(")) continue;
            labels.add(c.getKey() + "  (" + c.getValue()[0] + " students, " + c.getValue()[1] + " registered)");
            final String course = c.getKey();
            actions.add(() -> showChild(StudentListDialog.newInstance(dept, course, ""), "students"));
        }
        choose(row.id, labels.toArray(new String[0]), actions.toArray(new Runnable[0]));
    }
}

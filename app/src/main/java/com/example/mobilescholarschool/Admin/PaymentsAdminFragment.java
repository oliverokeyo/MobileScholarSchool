package com.example.mobilescholarschool.Admin;

import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.mobilescholarschool.R;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.Map;

/** Tab 3: Payments &amp; Transactions. */
public class PaymentsAdminFragment extends AdminScreenFragment {

    private TextView[] top, bottom;   // Collected / Successful / Pending ; Failed / Unverified / Outstanding

    @Override protected String screenTitle() { return getString(R.string.admin_title_payments); }

    @Override protected String screenSubtitle() { return "M-PESA transactions, verification, fees and reports"; }

    @Override protected void build(LinearLayout content) {
        AdminUi.sectionTitle(requireContext(), content, "Payments overview");
        top = AdminUi.statRow(requireContext(), content, "Collected", "Successful", "Pending");
        bottom = AdminUi.statRow(requireContext(), content, "Failed", "Unverified", "Outstanding");
        AdminUi.sectionTitle(requireContext(), content, "Payment services");
        AdminUi.serviceRow(requireContext(), content, R.drawable.ic_payments, "All Transactions",
                "Search by registration number and view transaction codes.", v -> open(TransactionListDialog.newInstance(""), "tx"));
        AdminUi.serviceRow(requireContext(), content, R.drawable.ic_requestbook, "Verify M-PESA Payments",
                "Confirm successful payments that are not yet verified.", v -> open(TransactionListDialog.newInstance("unverified"), "verify"));
        AdminUi.serviceRow(requireContext(), content, R.drawable.ic_notifications, "Pending & Failed",
                "Identify payments that did not go through.", v -> open(TransactionListDialog.newInstance("failed"), "failed"));
        AdminUi.serviceRow(requireContext(), content, R.drawable.ic_student, "Outstanding Fees",
                "See what each student still owes and set fee statements.", v -> open(new FeeListDialog(), "fees"));
        AdminUi.serviceRow(requireContext(), content, R.drawable.ic_requestedbooks, "Payment Reports",
                "Generate a report you can share.", v -> ReportHelper.showPaymentReport(requireActivity()));
    }

    private void open(androidx.fragment.app.DialogFragment d, String tag) {
        if (getChildFragmentManager().findFragmentByTag(tag) == null) d.show(getChildFragmentManager(), tag);
    }

    @Override protected void refresh() {
        PaymentIndex.load(new PaymentIndex.Callback() {
            @Override public void onLoaded(PaymentIndex index) {
                if (!isAdded()) return;
                top[0].setText(AdminUi.ksh(index.collected));
                top[1].setText(String.valueOf(index.success));
                top[2].setText(String.valueOf(index.pending));
                bottom[0].setText(String.valueOf(index.failed + index.cancelled));
                bottom[1].setText(String.valueOf(index.unverified));
                FirebaseFirestore.getInstance().collection("feeAccounts").limit(AdminRepo.MAX_DOCS).get().addOnSuccessListener(fees -> {
                    if (!isAdded()) return;
                    long outstanding = 0;
                    for (DocumentSnapshot f : fees.getDocuments()) {
                        outstanding += Math.max(0, AdminRepo.num(f, "totalBilled") - index.paidBy(f.getId()));
                    }
                    bottom[2].setText(AdminUi.ksh(outstanding));
                });
            }
            @Override public void onError(Exception e) {
                if (isAdded()) AdminUi.toast(getContext(), AdminRepo.message(e));
            }
        });
    }
}

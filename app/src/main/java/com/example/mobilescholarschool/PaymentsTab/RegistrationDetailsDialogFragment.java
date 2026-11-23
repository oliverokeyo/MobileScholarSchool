package com.example.mobilescholarschool.PaymentsTab;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.mobilescholarschool.R;

import java.util.Date;

/**
 * Shows the student's registration details (name, number, date registered, amount paid) and
 * lets them print them or save them as a PDF.
 *
 * HOW PRINTING WORKS: Android has no "print this screen" call. The standard trick is to build
 * the page as HTML, load it into an invisible WebView, and ask that WebView for a "print
 * adapter". Android's print dialog then offers real printers AND "Save as PDF".
 */
public class RegistrationDetailsDialogFragment extends DialogFragment
        implements PaymentsViewModel.Listener {

    public static final String TAG = "RegistrationDetailsDialog";

    private PaymentsViewModel viewModel;

    private TextView txtMessage;
    private TextView txtName;
    private TextView txtRegNo;
    private TextView txtStatus;
    private TextView txtDate;
    private TextView txtAmount;
    private Button printButton;

    // Kept in a field so the WebView is not garbage-collected while it is still loading the page.
    private WebView printWebView;

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        viewModel = new ViewModelProvider(requireActivity()).get(PaymentsViewModel.class);

        View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_registration_details, null);
        txtMessage = content.findViewById(R.id.txtRegMessage);
        txtName = content.findViewById(R.id.txtRegName);
        txtRegNo = content.findViewById(R.id.txtRegNo);
        txtStatus = content.findViewById(R.id.txtRegStatus);
        txtDate = content.findViewById(R.id.txtRegDate);
        txtAmount = content.findViewById(R.id.txtRegAmount);

        // The click listener is null on purpose: we attach the real one in onStart (see there).
        return new AlertDialog.Builder(requireContext())
                .setTitle(R.string.reg_title)
                .setView(content)
                .setPositiveButton(R.string.btn_print, null)
                .setNegativeButton(R.string.btn_close, null)
                .create();
    }

    @Override
    public void onStart() {
        super.onStart();
        viewModel.addListener(this);

        // A dialog button normally CLOSES the dialog when tapped. Printing must not close it
        // (the print job needs this screen alive), so we replace the default click behaviour.
        // The buttons only exist once the dialog is showing, which is why this is in onStart.
        AlertDialog dialog = (AlertDialog) getDialog();
        if (dialog != null) {
            printButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            printButton.setOnClickListener(v -> printDetails());
        }
        render();
    }

    @Override
    public void onStop() {
        viewModel.removeListener(this);
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        printWebView = null;
        super.onDestroyView();
    }

    @Override
    public void onPaymentsChanged() {
        render();
    }

    private void render() {
        boolean registered = viewModel.isRegistered();
        boolean known = viewModel.isRegistrationKnown();

        txtName.setText(nameText());
        txtRegNo.setText(regNoText());
        txtStatus.setText(statusText());
        txtDate.setText(dateText());
        txtAmount.setText(amountText());

        // Tell an unregistered student WHY there is nothing to print.
        txtMessage.setVisibility(known && !registered ? View.VISIBLE : View.GONE);

        // Only a registered student has registration details worth printing.
        if (printButton != null) {
            printButton.setEnabled(registered);
        }
    }

    // ------------------------------------------------------------------
    // The text for each field (used by BOTH the dialog and the printed page)
    // ------------------------------------------------------------------

    private String nameText() {
        return orNotAvailable(viewModel.getFullName());
    }

    private String regNoText() {
        return orNotAvailable(viewModel.getRegistrationNo());
    }

    private String statusText() {
        if (viewModel.isRegistered()) {
            return getString(R.string.registration_status_registered);
        }
        return getString(viewModel.isRegistrationKnown()
                ? R.string.registration_status_unregistered
                : R.string.registration_status_checking);
    }

    private String dateText() {
        Date registeredOn = viewModel.getRegisteredDate();
        return registeredOn == null
                ? getString(R.string.reg_not_available)
                : PaymentFormat.dateTime(registeredOn);
    }

    private String amountText() {
        return getString(R.string.tx_amount_format, PaymentFormat.money(viewModel.getTotalPaid()));
    }

    private String orNotAvailable(String value) {
        return value == null || value.trim().isEmpty() ? getString(R.string.reg_not_available) : value;
    }

    // ------------------------------------------------------------------
    // Printing
    // ------------------------------------------------------------------

    private void printDetails() {
        if (!viewModel.isRegistered() || printWebView != null) {
            return;   // nothing to print, or we are already preparing a page
        }

        WebView webView = new WebView(requireActivity());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // Only print once the page has finished loading, or the printout is blank.
                if (view == printWebView) {
                    createPrintJob(view);
                    printWebView = null;
                }
            }
        });
        printWebView = webView;
        // loadDataWithBaseURL (not loadData) so accents and symbols in names survive.
        webView.loadDataWithBaseURL(null, buildHtml(), "text/HTML", "UTF-8", null);
    }

    private void createPrintJob(WebView view) {
        if (!isAdded()) {
            return;   // the dialog was closed while the page was loading
        }
        PrintManager printManager =
                (PrintManager) requireContext().getSystemService(Context.PRINT_SERVICE);
        if (printManager == null) {
            Toast.makeText(requireContext(), R.string.print_failed, Toast.LENGTH_LONG).show();
            return;
        }
        String jobName = getString(R.string.print_job_name);
        printManager.print(jobName, view.createPrintDocumentAdapter(jobName),
                new PrintAttributes.Builder().build());
    }

    /**
     * The page that gets printed.
     *
     * Everything that came from a person (their NAME) goes through htmlEncode first. Without it
     * a name like "<b>x</b>" would be treated as page code instead of text. Escaping anything
     * you put into HTML is a habit worth keeping.
     */
    private String buildHtml() {
        StringBuilder html = new StringBuilder();
        html.append("<html><body style='font-family:sans-serif;padding:24px'>");
        html.append("<h2>").append(TextUtils.htmlEncode(getString(R.string.app_title))).append("</h2>");
        html.append("<h3>").append(TextUtils.htmlEncode(getString(R.string.reg_title))).append("</h3>");
        html.append("<table style='border-collapse:collapse;width:100%'>");
        appendRow(html, R.string.reg_label_name, nameText());
        appendRow(html, R.string.reg_label_reg_no, regNoText());
        appendRow(html, R.string.reg_label_status, statusText());
        appendRow(html, R.string.reg_label_date, dateText());
        appendRow(html, R.string.reg_label_amount, amountText());
        html.append("</table>");
        html.append("<p style='color:#666;font-size:12px;margin-top:24px'>")
                .append(TextUtils.htmlEncode(getString(R.string.print_printed_on,
                        PaymentFormat.dateTime(new Date()))))
                .append("</p>");
        html.append("</body></html>");
        return html.toString();
    }

    private void appendRow(StringBuilder html, int labelRes, String value) {
        html.append("<tr>")
                .append("<td style='padding:10px;border-bottom:1px solid #ccc;width:40%'><b>")
                .append(TextUtils.htmlEncode(getString(labelRes)))
                .append("</b></td>")
                .append("<td style='padding:10px;border-bottom:1px solid #ccc'>")
                .append(TextUtils.htmlEncode(value))
                .append("</td></tr>");
    }
}
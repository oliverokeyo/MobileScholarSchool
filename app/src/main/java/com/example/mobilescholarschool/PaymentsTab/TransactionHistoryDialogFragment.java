package com.example.mobilescholarschool.PaymentsTab;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.mobilescholarschool.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Pop-up showing EVERY school fee payment, newest first.
 *
 * Why a DialogFragment and not a plain AlertDialog? Android may destroy and recreate the screen
 * (rotation, low memory). A plain dialog just vanishes, while the FragmentManager remembers a
 * DialogFragment and brings it back. It must be PUBLIC with an empty constructor so Android can
 * recreate it.
 */
public class TransactionHistoryDialogFragment extends DialogFragment
        implements PaymentsViewModel.Listener {

    public static final String TAG = "TransactionHistoryDialog";

    private PaymentsViewModel viewModel;
    private final TransactionAdapter adapter = new TransactionAdapter();

    private TextView txtTotal;
    private TextView txtEmpty;
    private RecyclerView rvTransactions;

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        // The SAME ViewModel the Payments screen uses: ask the ACTIVITY, not this fragment.
        viewModel = new ViewModelProvider(requireActivity()).get(PaymentsViewModel.class);

        View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_transaction_history, null);
        txtTotal = content.findViewById(R.id.txtHistoryTotal);
        txtEmpty = content.findViewById(R.id.txtHistoryEmpty);
        rvTransactions = content.findViewById(R.id.rvTransactions);
        rvTransactions.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvTransactions.setAdapter(adapter);

        render();

        return new AlertDialog.Builder(requireContext())
                .setTitle(R.string.history_title)
                .setView(content)
                .setPositiveButton(R.string.btn_close, null)
                .create();
    }

    @Override
    public void onStart() {
        super.onStart();
        viewModel.addListener(this);   // live: a payment finishing while this is open updates it
        render();
    }

    @Override
    public void onStop() {
        viewModel.removeListener(this);   // never leave a listener pointing at a stopped dialog
        super.onStop();
    }

    @Override
    public void onPaymentsChanged() {
        render();
    }

    /** Draws whatever the ViewModel currently holds. Safe to call as often as we like. */
    private void render() {
        if (!viewModel.isLoaded()) {
            showMessage(R.string.history_loading);
            return;
        }

        List<PaymentRecord> payments = viewModel.getPayments();
        if (payments.isEmpty()) {
            showMessage(R.string.history_empty);
            return;
        }

        txtEmpty.setVisibility(View.GONE);
        rvTransactions.setVisibility(View.VISIBLE);
        txtTotal.setVisibility(View.VISIBLE);
        txtTotal.setText(getString(R.string.history_total_format,
                PaymentFormat.money(viewModel.getTotalPaid())));
        adapter.submit(payments);
    }

    private void showMessage(int messageRes) {
        txtTotal.setVisibility(View.GONE);
        rvTransactions.setVisibility(View.GONE);
        txtEmpty.setVisibility(View.VISIBLE);
        txtEmpty.setText(messageRes);
    }

    // ------------------------------------------------------------------
    // The list
    // ------------------------------------------------------------------

    /**
     * A RecyclerView draws only the rows that are on screen and REUSES their views as you scroll,
     * so a long history costs no more than a short one. The adapter fills a reused row with the
     * data for the position that just scrolled into view.
     */
    private static class TransactionAdapter extends RecyclerView.Adapter<TransactionAdapter.Holder> {

        private final List<PaymentRecord> items = new ArrayList<>();

        void submit(List<PaymentRecord> fresh) {
            items.clear();
            items.addAll(fresh);
            // Simple and fine for a short list. (DiffUtil would animate the changes.)
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View row = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_transaction, parent, false);
            return new Holder(row);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Context context = holder.itemView.getContext();
            PaymentRecord payment = items.get(position);

            holder.amount.setText(context.getString(R.string.tx_amount_format,
                    PaymentFormat.money(payment.amount)));
            holder.status.setText(PaymentFormat.statusLabel(context, payment.status));
            holder.status.setTextColor(PaymentFormat.statusColor(payment.status));
            holder.date.setText(PaymentFormat.dateTime(payment.createdAt));

            String detail = PaymentFormat.detail(context, payment);
            holder.detail.setText(detail);
            holder.detail.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class Holder extends RecyclerView.ViewHolder {
            final TextView amount;
            final TextView status;
            final TextView date;
            final TextView detail;

            Holder(@NonNull View itemView) {
                super(itemView);
                amount = itemView.findViewById(R.id.txtItemAmount);
                status = itemView.findViewById(R.id.txtItemStatus);
                date = itemView.findViewById(R.id.txtItemDate);
                detail = itemView.findViewById(R.id.txtItemDetail);
            }
        }
    }
}
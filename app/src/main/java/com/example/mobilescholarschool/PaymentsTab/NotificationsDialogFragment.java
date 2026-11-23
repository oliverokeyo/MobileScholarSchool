package com.example.mobilescholarschool.PaymentsTab;

import android.app.Dialog;
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
 * What the bell shows: the same messages the screen's toasts and dialogs give, but KEPT, so a
 * student can read them again later. A toast vanishes after two seconds; this does not.
 *
 * The notifications are not stored anywhere extra. They are BUILT from the payments list, so
 * they are always in step with the real payments and need no new database rules.
 */
public class NotificationsDialogFragment extends DialogFragment
        implements PaymentsViewModel.Listener {

    public static final String TAG = "NotificationsDialog";

    // A payment still "pending" after this long is stale, so we stop nagging about the PIN.
    private static final long PENDING_WINDOW_MS = 10 * 60 * 1000;

    private PaymentsViewModel viewModel;
    private final NotificationAdapter adapter = new NotificationAdapter();

    private TextView txtSummary;
    private TextView txtEmpty;
    private RecyclerView rvNotifications;

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        viewModel = new ViewModelProvider(requireActivity()).get(PaymentsViewModel.class);

        View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_notifications, null);
        txtSummary = content.findViewById(R.id.txtNotifSummary);
        txtEmpty = content.findViewById(R.id.txtNotifEmpty);
        rvNotifications = content.findViewById(R.id.rvNotifications);
        rvNotifications.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvNotifications.setAdapter(adapter);

        render();

        return new AlertDialog.Builder(requireContext())
                .setTitle(R.string.notif_title)
                .setView(content)
                .setPositiveButton(R.string.btn_close, null)
                .create();
    }

    @Override
    public void onStart() {
        super.onStart();
        viewModel.addListener(this);
        render();
    }

    @Override
    public void onStop() {
        viewModel.removeListener(this);
        super.onStop();
    }

    @Override
    public void onPaymentsChanged() {
        render();
    }

    private void render() {
        if (!viewModel.isLoaded()) {
            showMessage(R.string.history_loading);
            return;
        }

        List<Item> items = buildItems();
        if (items.isEmpty()) {
            showMessage(R.string.notif_empty);
            return;
        }

        txtEmpty.setVisibility(View.GONE);
        rvNotifications.setVisibility(View.VISIBLE);

        // The success story first: how many payments went through and how much in total.
        int successes = viewModel.getSuccessCount();
        if (successes > 0) {
            txtSummary.setVisibility(View.VISIBLE);
            txtSummary.setText(getString(R.string.notif_summary_format,
                    successes, PaymentFormat.money(viewModel.getTotalPaid())));
        } else {
            txtSummary.setVisibility(View.GONE);
        }
        adapter.submit(items);
    }

    private void showMessage(int messageRes) {
        txtSummary.setVisibility(View.GONE);
        rvNotifications.setVisibility(View.GONE);
        txtEmpty.setVisibility(View.VISIBLE);
        txtEmpty.setText(messageRes);
    }

    /**
     * One notification per payment, newest first. The words come from the SAME strings the toasts
     * and result dialogs use, which is what makes the bell "mimic" the screen.
     */
    private List<Item> buildItems() {
        List<Item> items = new ArrayList<>();
        PaymentRecord firstSuccess = viewModel.getFirstSuccess();
        long now = System.currentTimeMillis();

        for (PaymentRecord payment : viewModel.getPayments()) {
            String money = PaymentFormat.money(payment.amount);
            String time = PaymentFormat.timeAgo(payment.eventDate());
            int color = PaymentFormat.statusColor(payment.status);

            if (payment.isSuccess()) {
                // The very first success carries the big news: you are now registered.
                boolean madeThemRegistered = firstSuccess != null && firstSuccess.id.equals(payment.id);
                items.add(new Item(
                        getString(R.string.payment_success_title),
                        getString(madeThemRegistered
                                ? R.string.notif_success_first
                                : R.string.payment_success_message, money),
                        time, color));

            } else if (PaymentRecord.STATUS_CANCELLED.equals(payment.status)) {
                items.add(new Item(
                        getString(R.string.payment_cancelled_title),
                        getString(R.string.payment_cancelled_message),
                        time, color));

            } else if (PaymentRecord.STATUS_PENDING.equals(payment.status)) {
                if (now - payment.createdAt.getTime() <= PENDING_WINDOW_MS) {
                    items.add(new Item(
                            getString(R.string.notif_pending_title),
                            getString(R.string.toast_check_phone),
                            time, color));
                }

            } else {
                String reason = payment.resultDesc == null || payment.resultDesc.isEmpty()
                        ? getString(R.string.payment_failed_default) : payment.resultDesc;
                items.add(new Item(getString(R.string.payment_failed_title), reason, time, color));
            }
        }
        return items;
    }

    // ------------------------------------------------------------------
    // The list
    // ------------------------------------------------------------------

    private static final class Item {
        final String title;
        final String message;
        final String time;
        final int color;

        Item(String title, String message, String time, int color) {
            this.title = title;
            this.message = message;
            this.time = time;
            this.color = color;
        }
    }

    private static class NotificationAdapter extends RecyclerView.Adapter<NotificationAdapter.Holder> {

        private final List<Item> items = new ArrayList<>();

        void submit(List<Item> fresh) {
            items.clear();
            items.addAll(fresh);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View row = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_notification, parent, false);
            return new Holder(row);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Item item = items.get(position);
            holder.title.setText(item.title);
            holder.title.setTextColor(item.color);
            holder.message.setText(item.message);
            holder.time.setText(item.time);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class Holder extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView message;
            final TextView time;

            Holder(@NonNull View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.txtNotifTitle);
                message = itemView.findViewById(R.id.txtNotifMessage);
                time = itemView.findViewById(R.id.txtNotifTime);
            }
        }
    }
}
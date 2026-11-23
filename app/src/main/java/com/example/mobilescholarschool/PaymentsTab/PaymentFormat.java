package com.example.mobilescholarschool.PaymentsTab;

import android.content.Context;
import android.text.format.DateUtils;

import com.example.mobilescholarschool.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Turns payment data into text and colours, so the three dialogs all say things the same way. */
final class PaymentFormat {

    private static final int COLOR_SUCCESS = 0xFF1B5E20;    // dark green
    private static final int COLOR_PENDING = 0xFFE65100;    // dark orange
    private static final int COLOR_CANCELLED = 0xFF616161;  // grey
    private static final int COLOR_FAILED = 0xFFB71C1C;     // dark red

    private PaymentFormat() { }

    /** 12500 becomes "12,500". */
    static String money(long amount) {
        return String.format(Locale.US, "%,d", amount);
    }

    /** "22 Nov 2026, 3:09 PM" */
    static String dateTime(Date date) {
        // SimpleDateFormat is not thread-safe, so we make a fresh one each time.
        return new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(date);
    }

    /** "5 minutes ago". The clamp stops a slightly-fast clock from saying "in 1 minute". */
    static String timeAgo(Date date) {
        long now = System.currentTimeMillis();
        long then = Math.min(date.getTime(), now);
        return DateUtils.getRelativeTimeSpanString(then, now, DateUtils.MINUTE_IN_MILLIS).toString();
    }

    static String statusLabel(Context context, String status) {
        if (PaymentRecord.STATUS_SUCCESS.equals(status)) {
            return context.getString(R.string.tx_status_success);
        }
        if (PaymentRecord.STATUS_CANCELLED.equals(status)) {
            return context.getString(R.string.tx_status_cancelled);
        }
        if (PaymentRecord.STATUS_FAILED.equals(status)) {
            return context.getString(R.string.tx_status_failed);
        }
        return context.getString(R.string.tx_status_pending);
    }

    static int statusColor(String status) {
        if (PaymentRecord.STATUS_SUCCESS.equals(status)) {
            return COLOR_SUCCESS;
        }
        if (PaymentRecord.STATUS_CANCELLED.equals(status)) {
            return COLOR_CANCELLED;
        }
        if (PaymentRecord.STATUS_FAILED.equals(status)) {
            return COLOR_FAILED;
        }
        return COLOR_PENDING;
    }

    /** The small grey line under a transaction: a reference for a success, the reason otherwise. */
    static String detail(Context context, PaymentRecord record) {
        if (record.isSuccess()) {
            String reference = record.reference();
            return reference == null ? "" : context.getString(R.string.tx_reference_format, reference);
        }
        if (PaymentRecord.STATUS_PENDING.equals(record.status)) {
            return "";
        }
        return record.resultDesc == null ? "" : record.resultDesc;
    }
}
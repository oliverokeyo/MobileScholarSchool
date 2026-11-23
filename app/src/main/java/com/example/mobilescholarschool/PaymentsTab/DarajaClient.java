package com.example.mobilescholarschool.PaymentsTab;

import android.util.Base64;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Talks to Safaricom's Daraja API: get a token, send an STK push, ask how it went.
 *
 * EVERY method here makes a network call and BLOCKS until it finishes. Android forbids that on
 * the main thread (the screen would freeze), so PaymentActivity calls them from a background
 * thread only.
 */
final class DarajaClient {

    private static final String TAG = "DarajaClient";
    private static final int TIMEOUT_MS = 20_000;

    // Daraja rejects longer text in these two fields.
    private static final int MAX_ACCOUNT_REFERENCE = 12;
    private static final int MAX_TRANSACTION_DESC = 13;

    private DarajaClient() { }

    // ------------------------------------------------------------------
    // Result types
    // ------------------------------------------------------------------

    /** What Daraja says right after we ask it to send the prompt. */
    static final class StkPushResult {
        final String merchantRequestId;
        final String checkoutRequestId;   // the ticket number we use to ask about the result later

        StkPushResult(String merchantRequestId, String checkoutRequestId) {
            this.merchantRequestId = merchantRequestId;
            this.checkoutRequestId = checkoutRequestId;
        }
    }

    /** What Daraja says when we ask "what happened to that push?". */
    static final class StkQueryResult {
        final boolean stillProcessing;   // true: the customer has not answered yet, ask again
        final int resultCode;            // 0 = paid, 1032 = cancelled, 1 = insufficient balance...
        final String resultDesc;

        StkQueryResult(boolean stillProcessing, int resultCode, String resultDesc) {
            this.stillProcessing = stillProcessing;
            this.resultCode = resultCode;
            this.resultDesc = resultDesc;
        }
    }

    /** Daraja answered, but with a refusal. The message is safe to show to the student. */
    static final class DarajaException extends Exception {
        DarajaException(String message) {
            super(message);
        }
    }

    private static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    // ------------------------------------------------------------------
    // Step 1: OAuth token
    // ------------------------------------------------------------------

    /** Trades the consumer key + secret for a token that is valid for about an hour. */
    static String getAccessToken() throws IOException, JSONException, DarajaException {
        String credentials = MpesaConfig.CONSUMER_KEY + ":" + MpesaConfig.CONSUMER_SECRET;
        String basic = Base64.encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);

        HttpURLConnection connection =
                open("/oauth/v1/generate?grant_type=client_credentials", "GET");
        connection.setRequestProperty("Authorization", "Basic " + basic);

        Response response = send(connection, null);   // body is NOT logged: it holds the token
        if (response.code != 200) {
            throw new DarajaException("Could not sign in to M-PESA (HTTP " + response.code
                    + "). Check your consumer key and secret.");
        }
        return new JSONObject(response.body).getString("access_token");
    }

    // ------------------------------------------------------------------
    // Step 2: the STK push (sends the PIN prompt to the customer's phone)
    // ------------------------------------------------------------------

    /**
     * @param phone already in Daraja format: 2547XXXXXXXX
     */
    static StkPushResult stkPush(String accessToken, String phone, long amount,
                                 String accountReference, String description)
            throws IOException, JSONException, DarajaException {

        String timestamp = timestamp();

        JSONObject body = new JSONObject();
        body.put("BusinessShortCode", MpesaConfig.SHORTCODE);
        body.put("Password", password(timestamp));
        body.put("Timestamp", timestamp);
        body.put("TransactionType", "CustomerPayBillOnline");
        body.put("Amount", amount);
        body.put("PartyA", phone);                          // who pays
        body.put("PartyB", MpesaConfig.SHORTCODE);          // who is paid
        body.put("PhoneNumber", phone);                     // where the prompt appears
        body.put("CallBackURL", MpesaConfig.CALLBACK_URL);
        body.put("AccountReference", truncate(accountReference, MAX_ACCOUNT_REFERENCE));
        body.put("TransactionDesc", truncate(description, MAX_TRANSACTION_DESC));

        HttpURLConnection connection = open("/mpesa/stkpush/v1/processrequest", "POST");
        connection.setRequestProperty("Authorization", "Bearer " + accessToken);

        Response response = send(connection, body.toString());
        Log.d(TAG, "STK push -> HTTP " + response.code + " " + response.body);

        JSONObject json = parseOrEmpty(response.body);

        // ResponseCode "0" only means "request accepted", NOT "customer paid".
        if (response.code == 200 && "0".equals(json.optString("ResponseCode"))) {
            return new StkPushResult(
                    json.optString("MerchantRequestID"),
                    json.getString("CheckoutRequestID"));
        }

        String reason = json.optString("errorMessage",
                json.optString("ResponseDescription", "HTTP " + response.code));
        throw new DarajaException("M-PESA refused the request: " + reason);
    }

    // ------------------------------------------------------------------
    // Step 3: ask how it went (our substitute for the callback)
    // ------------------------------------------------------------------

    static StkQueryResult stkQuery(String accessToken, String checkoutRequestId)
            throws IOException, JSONException, DarajaException {

        String timestamp = timestamp();

        JSONObject body = new JSONObject();
        body.put("BusinessShortCode", MpesaConfig.SHORTCODE);
        body.put("Password", password(timestamp));
        body.put("Timestamp", timestamp);
        body.put("CheckoutRequestID", checkoutRequestId);

        HttpURLConnection connection = open("/mpesa/stkpushquery/v1/query", "POST");
        connection.setRequestProperty("Authorization", "Bearer " + accessToken);

        Response response = send(connection, body.toString());
        Log.d(TAG, "STK query -> HTTP " + response.code + " " + response.body);

        JSONObject json = parseOrEmpty(response.body);

        // A real answer: the customer has paid, cancelled or failed.
        if (response.code == 200 && json.has("ResultCode")) {
            return new StkQueryResult(false,
                    parseCode(json.optString("ResultCode")),
                    json.optString("ResultDesc"));
        }

        // While the customer is still looking at the prompt, Daraja answers with an ERROR
        // saying the transaction is being processed. That is not a failure: ask again later.
        String message = json.optString("errorMessage", "");
        if (message.toLowerCase(Locale.ROOT).contains("being processed")) {
            return new StkQueryResult(true, -1, message);
        }

        throw new DarajaException("Could not check the payment (HTTP " + response.code + "). " + message);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Daraja's clock is Kenya time. Using the phone's own zone would break the password. */
    private static String timestamp() {
        // SimpleDateFormat is not thread-safe, so we make a fresh one each time.
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("Africa/Nairobi"));
        return format.format(new Date());
    }

    /** Password = Base64( shortcode + passkey + timestamp ). */
    private static String password(String timestamp) {
        String raw = MpesaConfig.SHORTCODE + MpesaConfig.PASSKEY + timestamp;
        return Base64.encodeToString(raw.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    private static HttpURLConnection open(String path, String method) throws IOException {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(MpesaConfig.BASE_URL + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        return connection;
    }

    /** Sends the request (with an optional JSON body) and reads the whole answer. */
    private static Response send(HttpURLConnection connection, String jsonBody) throws IOException {
        try {
            if (jsonBody != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = connection.getResponseCode();
            // Errors (HTTP 400 and above) come on a different stream from successes.
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Response(code, stream == null ? "" : readAll(stream));
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line);
            }
        }
        return text.toString();
    }

    /** Never crashes on a non-JSON answer (for example an HTML error page). */
    private static JSONObject parseOrEmpty(String text) {
        try {
            return new JSONObject(text);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /** Daraja sends ResultCode as text ("0"). -1 means we could not read it. */
    private static int parseCode(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
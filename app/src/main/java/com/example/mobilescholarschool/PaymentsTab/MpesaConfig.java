package com.example.mobilescholarschool.PaymentsTab;

/**
 * Daraja SANDBOX settings, all in one place.
 *
 * WARNING: anything in this file ends up inside the APK, where anyone can read it.
 * That is acceptable ONLY because these are sandbox test credentials.
 * For production, these values live on YOUR SERVER and the app never sees them.
 * Add this file to .gitignore so the keys never reach GitHub.
 */
public final class MpesaConfig {

    private MpesaConfig() { }

    /** Sandbox. The production address is different, and must only ever be used from a server. */
    public static final String BASE_URL = "https://sandbox.safaricom.co.ke";

    // From developer.safaricom.co.ke -> My Apps -> your app
    public static final String CONSUMER_KEY = "jh4mORwu8T8EPLgRngWzz024ZrSTrHa7HZ2tpTLuRIjGlQFr";
    public static final String CONSUMER_SECRET = "vdGWcB0hSVUUeT3YsxMeSsJBHzoasasmQHpelrcPcWvGQ3IA3xQ8NA0567BcmHDG";

    // From the portal's "Test Credentials" page (M-PESA Express)
    public static final String SHORTCODE = "174379";
    public static final String PASSKEY = "bfb279f9aa9bdbcf158e97dd71a467cd2e0c893059b10f78e6b72ada1ed2c919";

    // Daraja REQUIRES a callback URL in the request, but we never receive on it: the app asks
    // Daraja for the result instead (the Query endpoint). So any valid HTTPS address works.
    public static final String CALLBACK_URL = "https://example.com/mpesa/callback";

    /** False until you have replaced the PASTE_ placeholders above. */
    public static boolean isConfigured() {
        return !CONSUMER_KEY.startsWith("PASTE")
                && !CONSUMER_SECRET.startsWith("PASTE")
                && !PASSKEY.startsWith("PASTE");
    }
}
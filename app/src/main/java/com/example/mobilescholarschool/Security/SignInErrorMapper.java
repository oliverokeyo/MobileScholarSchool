package com.example.mobilescholarschool.Security;

import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.FirebaseTooManyRequestsException;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;

import java.io.IOException;

/**
 * NEW: sorts a Firebase sign-in failure into a KIND, and gives a sentence a student can read.
 *
 * LESSON: the SAME button can fail for very different reasons, and each needs a different
 * reaction:
 *   NETWORK           -> not the student's fault; offer offline sign-in
 *   WRONG_CREDENTIALS -> let them retry (and check whether our cached password is now stale)
 *   ACCOUNT_DISABLED  -> the school disabled it: remove offline access, do NOT offer offline
 *   ACCOUNT_NOT_FOUND -> the account was deleted: remove offline access
 *   TOO_MANY_REQUESTS -> Firebase is slowing them down: ask them to wait
 * Never show raw exception text to students; it is confusing and can leak details.
 */
public final class SignInErrorMapper {

    public enum Kind {
        NETWORK, WRONG_CREDENTIALS, ACCOUNT_DISABLED, ACCOUNT_NOT_FOUND,
        TOO_MANY_REQUESTS, SESSION_EXPIRED, UNKNOWN
    }

    public static final class Info {
        public final Kind kind;
        public final String message;

        private Info(Kind kind, String message) {
            this.kind = kind;
            this.message = message;
        }

        /** Only a connection problem justifies falling back to offline sign-in. */
        public boolean allowsOfflineFallback() {
            return kind == Kind.NETWORK;
        }

        /** The server has said this student must not get in: delete any offline access. */
        public boolean revokesOfflineAccess() {
            return kind == Kind.ACCOUNT_DISABLED || kind == Kind.ACCOUNT_NOT_FOUND;
        }
    }

    private SignInErrorMapper() { }

    public static Info map(Exception e) {
        if (isNetwork(e)) {
            return new Info(Kind.NETWORK, "No internet connection, or the server could not be "
                    + "reached. Check your connection and try again.");
        }
        if (e instanceof FirebaseTooManyRequestsException) {
            return new Info(Kind.TOO_MANY_REQUESTS,
                    "Too many attempts. Please wait a few minutes and try again.");
        }
        if (e instanceof FirebaseAuthInvalidUserException) {
            String code = ((FirebaseAuthInvalidUserException) e).getErrorCode();
            if ("ERROR_USER_DISABLED".equals(code)) {
                return new Info(Kind.ACCOUNT_DISABLED,
                        "This account has been disabled. Please contact the school administration.");
            }
            if ("ERROR_USER_TOKEN_EXPIRED".equals(code)) {
                return new Info(Kind.SESSION_EXPIRED,
                        "Your sign-in has expired. Please sign in again.");
            }
            return new Info(Kind.ACCOUNT_NOT_FOUND,
                    "No account was found for this email. Please register first.");
        }
        if (e instanceof FirebaseAuthInvalidCredentialsException) {
            String code = ((FirebaseAuthInvalidCredentialsException) e).getErrorCode();
            if ("ERROR_INVALID_EMAIL".equals(code)) {
                return new Info(Kind.WRONG_CREDENTIALS, "That email address is not valid.");
            }
            return new Info(Kind.WRONG_CREDENTIALS, "The email or password is incorrect.");
        }
        if (e instanceof FirebaseAuthException
                && "ERROR_USER_DISABLED".equals(((FirebaseAuthException) e).getErrorCode())) {
            return new Info(Kind.ACCOUNT_DISABLED,
                    "This account has been disabled. Please contact the school administration.");
        }
        return new Info(Kind.UNKNOWN, "Sign-in failed. Please try again.");
    }

    /** A connection problem can be wrapped inside another exception: look through the chain. */
    private static boolean isNetwork(Throwable t) {
        for (int depth = 0; t != null && depth < 6; depth++, t = t.getCause()) {
            if (t instanceof FirebaseNetworkException || t instanceof IOException) {
                return true;
            }
        }
        return false;
    }
}

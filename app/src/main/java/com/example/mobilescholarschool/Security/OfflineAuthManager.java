package com.example.mobilescholarschool.Security;

import android.content.Context;

import com.example.mobilescholarschool.Data.OfflineAuthRepository;
import com.example.mobilescholarschool.Data.SyncLog;
import com.google.firebase.auth.FirebaseUser;

import java.security.GeneralSecurityException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * NEW: the ONE place that decides whether a student may sign in without internet.
 *
 * LESSON 1: OFFLINE SIGN-IN IS A CONVENIENCE, NOT AUTHORIZATION.
 * Firebase is still the only thing that can say "this person is a real, enabled student".
 * Offline sign-in can only ever say "the person typing knows the password that Firebase
 * accepted on THIS phone recently". So it:
 *   - only exists for accounts that completed an ONLINE Firebase sign-in (enroll),
 *   - expires after MAX_OFFLINE_AGE_MILLIS without a fresh online check,
 *   - is wiped the moment Firebase reports the account disabled/deleted or the password changed,
 *   - never creates a FirebaseUser or token, so Firestore rules and servers still say "no"
 *     to anything that needs real authorization (see OnlineOnlyGuard).
 *
 * LESSON 2: RATE LIMITING. A 4-digit PIN falls in minutes; a locked-out guesser does not.
 *   - 5 free wrong attempts, then a lock that doubles: 30 s, 60 s, 2 min ... capped at 15 min.
 *   - After 12 wrong attempts offline sign-in switches OFF until the student signs in online.
 *   - The counters live in SQLite, so closing the app does not reset them.
 *   - Lock times use the larger of "now" and the latest time we ever saw, so winding the
 *     phone's clock backwards does not skip a lock.
 * (A rooted phone can still edit the database. That is why the verifier is also bound to the
 * hardware Keystore, and why offline mode can never authorize anything on the server.)
 *
 * All methods hit the disk and do slow maths: call them from a background thread (DbExecutor).
 * Callers should overwrite the char[] password with zeros afterwards (Arrays.fill).
 */
public final class OfflineAuthManager {

    private static final String TAG = "OfflineAuthManager";

    /**
     * POLICY SWITCH. false = "verified" means "Firebase accepted this email and password online".
     * Set true if your app sends verification emails and you want only email-verified accounts
     * to be eligible for offline sign-in.
     */
    public static final boolean REQUIRE_EMAIL_VERIFIED = false;

    /** Offline sign-in stops working this long after the last successful ONLINE sign-in. */
    public static final long MAX_OFFLINE_AGE_MILLIS = TimeUnit.DAYS.toMillis(14);

    static final int FREE_ATTEMPTS = 5;
    static final int HARD_LIMIT = 12;
    static final long BASE_LOCK_MILLIS = TimeUnit.SECONDS.toMillis(30);
    static final long MAX_LOCK_MILLIS = TimeUnit.MINUTES.toMillis(15);

    // Two threads must not read-modify-write the failure counter at the same time
    private static final Object LOCK = new Object();

    public enum Status {
        OK,
        WRONG_CREDENTIALS,   // wrong email/password (attemptsLeft says how many free tries remain)
        LOCKED,              // too many wrong tries: wait retryAfterMillis
        NOT_ENROLLED,        // this email never signed in online on this phone
        EXPIRED,             // last online check is too old: connect and sign in online
        REQUIRES_ONLINE,     // too many failures overall: sign in online to switch it back on
        ERROR                // the device security feature failed: sign in online
    }

    /** The answer to "may this person in?". */
    public static final class Result {
        public final Status status;
        public final String uid;
        public final String email;
        public final long retryAfterMillis;
        public final int attemptsLeft;

        private Result(Status status, String uid, String email, long retryAfterMillis,
                       int attemptsLeft) {
            this.status = status;
            this.uid = uid;
            this.email = email;
            this.retryAfterMillis = retryAfterMillis;
            this.attemptsLeft = attemptsLeft;
        }

        public boolean isOk() {
            return status == Status.OK;
        }

        private static Result of(Status s) {
            return new Result(s, null, null, 0, 0);
        }
    }

    private OfflineAuthManager() { }

    /** Trim and lower-case so "Amy@Mail.com " and "amy@mail.com" are the same student. */
    public static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    // ============================================================== ONLINE -> enable offline

    /**
     * Call right after Firebase accepted this email and password ONLINE (signInWithEmailAndPassword
     * success). Creates/refreshes the verifier and resets the counters.
     *
     * @return true if offline sign-in is now available for this student
     */
    public static boolean enroll(Context c, FirebaseUser user, char[] password) {
        if (user == null || user.getEmail() == null) {
            return false;
        }
        if (REQUIRE_EMAIL_VERIFIED && !user.isEmailVerified()) {
            forget(c, user.getUid());
            SyncLog.w(c, TAG, "Offline sign-in not enabled: email is not verified");
            return false;
        }
        try {
            PasswordHasher.Hash h = PasswordHasher.create(password);
            synchronized (LOCK) {
                OfflineAuthRepository.save(c, user.getUid(), normalize(user.getEmail()),
                        h.salt, h.iterations, h.algorithm, h.verifier, System.currentTimeMillis());
            }
            SyncLog.i(c, TAG, "Offline sign-in enabled for this device");
            return true;
        } catch (GeneralSecurityException | RuntimeException e) {
            // Log the TYPE only: messages can contain details we do not want in a log
            SyncLog.e(c, TAG, "Could not enable offline sign-in: " + e.getClass().getSimpleName());
            return false;
        }
    }

    // ============================================================== OFFLINE sign-in

    public static Result signInOffline(Context c, String rawEmail, char[] password) {
        String email = normalize(rawEmail);
        synchronized (LOCK) {
            OfflineAuthRepository.Record r = OfflineAuthRepository.findByEmail(c, email);
            if (r == null) {
                return Result.of(Status.NOT_ENROLLED);
            }

            // Never let the clock go backwards in our eyes
            long now = Math.max(System.currentTimeMillis(), r.lastSeenMillis);

            // Cheap checks FIRST: a locked-out guesser must not even cost us CPU time
            if (r.failedAttempts >= HARD_LIMIT) {
                return Result.of(Status.REQUIRES_ONLINE);
            }
            if (now - r.lastOnlineMillis > MAX_OFFLINE_AGE_MILLIS) {
                return Result.of(Status.EXPIRED);
            }
            if (r.lockedUntilMillis > now) {
                return locked(r.lockedUntilMillis - now);
            }

            boolean correct;
            try {
                correct = PasswordHasher.verify(password, r.salt, r.iterations, r.algorithm,
                        r.verifier);
            } catch (PasswordHasher.KeyUnavailableException e) {
                // e.g. the database was restored onto a new phone: the verifier is unusable
                OfflineAuthRepository.delete(c, r.uid);
                SyncLog.w(c, TAG, "Device key missing: offline sign-in removed, online needed");
                return Result.of(Status.NOT_ENROLLED);
            } catch (GeneralSecurityException | RuntimeException e) {
                SyncLog.e(c, TAG, "Offline verify failed: " + e.getClass().getSimpleName());
                return Result.of(Status.ERROR);
            }

            if (correct) {
                OfflineAuthRepository.recordSuccess(c, r.uid, now);
                SyncLog.i(c, TAG, "Offline sign-in succeeded");
                return new Result(Status.OK, r.uid, r.email, 0, FREE_ATTEMPTS);
            }

            int fails = r.failedAttempts + 1;
            if (fails >= HARD_LIMIT) {
                OfflineAuthRepository.recordFailure(c, r.uid, fails, 0, now);
                SyncLog.w(c, TAG, "Offline sign-in switched off after too many wrong attempts");
                return Result.of(Status.REQUIRES_ONLINE);
            }
            long lockedUntil = 0;
            if (fails >= FREE_ATTEMPTS) {
                long lock = Math.min(MAX_LOCK_MILLIS, BASE_LOCK_MILLIS << (fails - FREE_ATTEMPTS));
                lockedUntil = now + lock;
            }
            OfflineAuthRepository.recordFailure(c, r.uid, fails, lockedUntil, now);
            SyncLog.w(c, TAG, "Wrong offline password (attempt " + fails + ")");
            if (lockedUntil > 0) {
                return locked(lockedUntil - now);
            }
            return new Result(Status.WRONG_CREDENTIALS, null, null, 0, FREE_ATTEMPTS - fails);
        }
    }

    private static Result locked(long retryAfterMillis) {
        return new Result(Status.LOCKED, null, null, retryAfterMillis, 0);
    }

    // ============================================================== SERVER said no -> revoke

    /**
     * Firebase rejected an email + password ONLINE. If the same password still matches our local
     * verifier, the password was changed on the server, so the local copy is stale and must go.
     *
     * LESSON: why compare first instead of deleting straight away? A simple typo is also
     * "rejected" by Firebase. Deleting on every typo would let anyone wipe a student's offline
     * access by typing wrong passwords. A typo will NOT match the local verifier, so it is kept.
     *
     * @return true if the offline credential was removed
     */
    public static boolean onServerRejectedCredentials(Context c, String rawEmail, char[] password) {
        String email = normalize(rawEmail);
        synchronized (LOCK) {
            OfflineAuthRepository.Record r = OfflineAuthRepository.findByEmail(c, email);
            if (r == null) {
                return false;
            }
            try {
                if (PasswordHasher.verify(password, r.salt, r.iterations, r.algorithm,
                        r.verifier)) {
                    OfflineAuthRepository.delete(c, r.uid);
                    SyncLog.w(c, TAG, "Server rejected a password we had cached: offline access removed");
                    return true;
                }
            } catch (GeneralSecurityException | RuntimeException e) {
                // Cannot compare: leave it; the 14-day expiry is the safety net
            }
            return false;
        }
    }

    /** Firebase says the account is disabled or gone: remove offline access at once. */
    public static void forget(Context c, String uid) {
        synchronized (LOCK) {
            OfflineAuthRepository.delete(c, uid);
        }
        SyncLog.w(c, TAG, "Offline sign-in removed for an account");
    }

    public static void forgetEmail(Context c, String rawEmail) {
        synchronized (LOCK) {
            OfflineAuthRepository.deleteByEmail(c, normalize(rawEmail));
        }
        SyncLog.w(c, TAG, "Offline sign-in removed for an account");
    }

    /** Can this email try offline sign-in at all? (lets the screen decide what to offer) */
    public static boolean isEnrolled(Context c, String rawEmail) {
        return OfflineAuthRepository.existsForEmail(c, normalize(rawEmail));
    }
}

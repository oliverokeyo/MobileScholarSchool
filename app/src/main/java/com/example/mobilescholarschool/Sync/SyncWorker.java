package com.example.mobilescholarschool.Sync;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.mobilescholarschool.Components.PendingRegistrationStore;
import com.example.mobilescholarschool.Components.SessionUser;
import com.example.mobilescholarschool.Data.AppDatabase;
import com.example.mobilescholarschool.Data.BookRequestRepository;
import com.example.mobilescholarschool.Data.HostelBookingRepository;
import com.example.mobilescholarschool.Data.Outbox;
import com.example.mobilescholarschool.Data.ProfileRepository;
import com.example.mobilescholarschool.Data.SyncLog;
import com.example.mobilescholarschool.Data.SyncState;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.Source;

import org.json.JSONObject;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * NEW: the "postman". It replaces RegistrationSyncWorker and does two jobs, in this order:
 *
 *   1. Empty the OUTBOX of the signed-in student (profile edits, book requests, hostel bookings).
 *   2. Finish a pending REGISTRATION (the old worker's job; its password stays in the
 *      encrypted PendingRegistrationStore, never in the SQLite file).
 *
 * LESSON: sort every failure into one of two buckets.
 *   TEMPORARY (no signal, timeout, server busy)  -> keep the row, return retry(), try later.
 *   PERMANENT (the security rules said no)       -> mark the row "failed", remember why, STOP
 *                                                   trying. Retrying a "no" forever wastes battery.
 */
public class SyncWorker extends Worker {

    private static final String TAG = "SyncWorker";

    // Firestore's set() only finishes when the SERVER confirms. With a weak signal that can
    // take forever, so we give up waiting after this long and try again later.
    private static final long SEND_TIMEOUT_SECONDS = 25;
    private static final long AUTH_TIMEOUT_SECONDS = 30;

    // Safety net against an accidental endless loop
    private static final int MAX_PASSES = 50;

    private static final String MSG_PROFILE_REJECTED =
            "The server did not accept your profile change.";
    private static final String MSG_REQUEST_REJECTED =
            "The library could not accept this request (for example, the borrow date has "
                    + "already passed). Please submit a new request.";
    private static final String MSG_BOOKING_REJECTED =
            "The hostel could not accept this booking (the room may be full or its details "
                    + "changed). Please search again.";

    // NEW: ScholarApp's network listener asks "is a sync already running?" before kicking off another.
    // volatile = every thread sees the latest value. WorkManager runs in our own process, so this works.
    private static volatile boolean running = false;

    public static boolean isRunning() {
        return running;
    }

    /** What happened to ONE outbox item. */
    private enum Outcome { DONE, STALE, RETRY }

    /** What happened when we tried to create ONE document. */
    private enum Push { SENT, ALREADY_THERE, REJECTED, RETRY }

    /** The result of one of the two big jobs. */
    private enum Step { OK, RETRY, FAILED }

    public SyncWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        running = true;
        try {
            return sync();
        } finally {
            running = false;
        }
    }

    private Result sync() {
        Context ctx = getApplicationContext();
        SyncLog.i(ctx, TAG, "Sync started");

        // CHANGED ORDER: registration FIRST, outbox second.
        // A student who signed up offline may already have saved requests, bookings and profile
        // edits. They can only be sent once the account exists, and the profile document must
        // exist before the rules accept a request (they compare the student number with it).
        // Creating the account also re-labels those rows with the real uid, so the outbox step
        // below finds them in the SAME run.
        Step registration = syncPendingRegistration(ctx);

        Step outbox;
        try {
            outbox = drainOutbox(ctx);
        } catch (RuntimeException e) {
            SyncLog.e(ctx, TAG, "Outbox crashed: " + e);
            outbox = Step.RETRY;
        }

        SyncLog.i(ctx, TAG, "Sync finished (registration=" + registration + ", outbox=" + outbox + ")");
        if (outbox == Step.RETRY || registration == Step.RETRY) {
            return Result.retry();
        }
        if (registration == Step.FAILED) {
            return Result.failure();
        }
        return Result.success();
    }

    // ================================================================ JOB 1: the outbox

    private Step drainOutbox(Context ctx) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            // Rules only allow signed-in students to write, so rows wait for their owner.
            SyncLog.i(ctx, TAG, "Nobody is signed in: saved items wait until their owner signs in");
            return Step.OK;
        }
        String uid = user.getUid();   // only THIS student's rows are ever sent

        for (int pass = 0; pass < MAX_PASSES; pass++) {
            // Read the list AGAIN every pass: it picks up rows saved while we were busy,
            // and rows marked STALE (edited again mid-send).
            List<Outbox.Item> items = Outbox.pendingFor(ctx, uid);
            if (items.isEmpty()) {
                return Step.OK;
            }
            for (Outbox.Item item : items) {
                if (isStopped()) {
                    return Step.RETRY;   // Android asked us to stop: finish later
                }
                if (process(ctx, item) == Outcome.RETRY) {
                    return Step.RETRY;   // keep order: do not skip ahead
                }
            }
        }
        return Step.RETRY;
    }

    private Outcome process(Context ctx, Outbox.Item item) {
        switch (item.entity) {
            case Outbox.ENTITY_PROFILE:
                return syncProfile(ctx, item);
            case Outbox.ENTITY_BOOK_REQUEST:
                return syncBookRequest(ctx, item);
            case Outbox.ENTITY_HOSTEL_BOOKING:
                return syncHostelBooking(ctx, item);
            default:
                SyncLog.w(ctx, TAG, "Unknown outbox entity '" + item.entity + "': removed");
                Outbox.remove(ctx, item.id);
                return Outcome.DONE;
        }
    }

    // ---------------------------------------------------------------- profile edit

    private Outcome syncProfile(Context ctx, Outbox.Item item) {
        ProfileRepository.LocalProfile p = ProfileRepository.get(ctx, item.uid);
        if (p == null || !SyncState.PENDING_SYNC.equals(p.syncState)) {
            Outbox.remove(ctx, item.id);   // nothing left to send
            return Outcome.DONE;
        }

        // Only the fields the security rules allow a student to change
        Map<String, Object> updates = new HashMap<>();
        updates.put("fullName", p.fullName);
        updates.put("idNumber", p.idNumber);
        updates.put("registrationNo", p.registrationNo);
        updates.put("gender", p.gender);
        updates.put("course", p.course);
        updates.put("department", p.department);
        updates.put("updatedAt", FieldValue.serverTimestamp());   // the SERVER's clock

        try {
            Tasks.await(FirebaseFirestore.getInstance().collection("users").document(item.uid)
                    .set(updates, SetOptions.merge()), SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (isPermanent(codeOf(e.getCause()))) {
                ProfileRepository.markFailed(ctx, item.uid, MSG_PROFILE_REJECTED);
                Outbox.remove(ctx, item.id);
                SyncLog.e(ctx, TAG, "Server rejected the profile edit: " + e.getCause());
                return Outcome.DONE;
            }
            noteRetry(ctx, item, e);
            return Outcome.RETRY;
        } catch (TimeoutException | InterruptedException e) {
            noteRetry(ctx, item, e);
            return Outcome.RETRY;
        }

        if (ProfileRepository.markSyncedIfUnchanged(ctx, item.uid, p.updatedLocalMillis)) {
            Outbox.remove(ctx, item.id);
            SyncLog.i(ctx, TAG, "Profile edit delivered");
            return Outcome.DONE;
        }
        // The student edited again while we were sending: keep the outbox row, send the newer copy
        SyncLog.i(ctx, TAG, "Profile changed while sending: sending the newer copy");
        return Outcome.STALE;
    }

    // ---------------------------------------------------------------- book request

    private Outcome syncBookRequest(Context ctx, Outbox.Item item) {
        BookRequestRepository.Request r = BookRequestRepository.get(ctx, item.entityId);
        if (r == null || !SyncState.PENDING_SYNC.equals(r.syncState)) {
            Outbox.remove(ctx, item.id);
            return Outcome.DONE;
        }

        // EXACTLY the nine fields the security rules require
        Map<String, Object> data = new HashMap<>();
        data.put("studentUid", r.uid);
        data.put("studentIdNumber", r.studentIdNumber);
        data.put("isbn", r.isbn);
        data.put("bookTitle", r.bookTitle);
        data.put("author", r.author);
        data.put("borrowDate", new Timestamp(new Date(r.borrowMillis)));
        data.put("returnDate", new Timestamp(new Date(r.returnMillis)));
        data.put("status", "pending");
        data.put("createdAt", FieldValue.serverTimestamp());

        switch (pushCreate(ctx, item, "bookRequests", data)) {
            case RETRY:
                return Outcome.RETRY;
            case REJECTED:
                BookRequestRepository.markFailed(ctx, r.localId, MSG_REQUEST_REJECTED);
                Outbox.remove(ctx, item.id);
                SyncLog.e(ctx, TAG, "Book request " + r.localId + " rejected by the server");
                return Outcome.DONE;
            default:   // SENT or ALREADY_THERE
                BookRequestRepository.markSynced(ctx, r.localId);
                Outbox.remove(ctx, item.id);
                SyncLog.i(ctx, TAG, "Book request " + r.localId + " delivered");
                return Outcome.DONE;
        }
    }

    // ---------------------------------------------------------------- hostel booking

    private Outcome syncHostelBooking(Context ctx, Outbox.Item item) {
        HostelBookingRepository.Booking b = HostelBookingRepository.get(ctx, item.entityId);
        if (b == null || !SyncState.PENDING_SYNC.equals(b.syncState)) {
            Outbox.remove(ctx, item.id);
            return Outcome.DONE;
        }

        // EXACTLY the ten fields the security rules require
        Map<String, Object> data = new HashMap<>();
        data.put("studentUid", b.uid);
        data.put("studentIdNumber", b.studentIdNumber);
        data.put("hostelId", b.hostelId);
        data.put("hostelName", b.hostelName);
        data.put("roomId", b.roomId);
        data.put("roomType", b.roomType);
        data.put("sharing", b.sharing);
        data.put("rentPerMonth", b.rentPerMonth);
        data.put("status", "pending");
        data.put("createdAt", FieldValue.serverTimestamp());

        switch (pushCreate(ctx, item, "hostelBookings", data)) {
            case RETRY:
                return Outcome.RETRY;
            case REJECTED:
                HostelBookingRepository.markFailed(ctx, b.localId, MSG_BOOKING_REJECTED);
                Outbox.remove(ctx, item.id);
                SyncLog.e(ctx, TAG, "Hostel booking " + b.localId + " rejected by the server");
                return Outcome.DONE;
            default:
                HostelBookingRepository.markSynced(ctx, b.localId);
                Outbox.remove(ctx, item.id);
                SyncLog.i(ctx, TAG, "Hostel booking " + b.localId + " delivered");
                return Outcome.DONE;
        }
    }

    // ---------------------------------------------------------------- shared helpers

    /**
     * Creates one document whose id is the row's local id.
     *
     * LESSON: a RETRY AFTER A LOST REPLY. Suppose the first send worked but the "OK" never
     * reached the phone. The second set() hits an EXISTING document, which Firestore treats as
     * an UPDATE, and your rules forbid updates, so it comes back PERMISSION_DENIED. That "no"
     * does not mean the request is bad. So on PERMISSION_DENIED we check whether the document
     * is already on the server before declaring failure.
     */
    private Push pushCreate(Context ctx, Outbox.Item item, String collection,
                            Map<String, Object> data) {
        DocumentReference ref = FirebaseFirestore.getInstance()
                .collection(collection).document(item.entityId);
        try {
            Tasks.await(ref.set(data), SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return Push.SENT;
        } catch (ExecutionException e) {
            FirebaseFirestoreException.Code code = codeOf(e.getCause());
            if (code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                Boolean exists = existsOnServer(ref);
                if (exists == null) {
                    noteRetry(ctx, item, e);
                    return Push.RETRY;
                }
                return exists ? Push.ALREADY_THERE : Push.REJECTED;
            }
            if (isPermanent(code)) {
                return Push.REJECTED;
            }
            noteRetry(ctx, item, e);
            return Push.RETRY;
        } catch (TimeoutException | InterruptedException e) {
            noteRetry(ctx, item, e);
            return Push.RETRY;
        }
    }

    /**
     * @return true/false if we learned the answer, null if we could not find out (no signal).
     *
     * LESSON: "rules are not filters". Our read rule looks at resource.data.studentUid. For a
     * document that does NOT exist there is no resource, so the read is itself denied. Hence a
     * PERMISSION_DENIED here means "it is not there".
     */
    private Boolean existsOnServer(DocumentReference ref) {
        try {
            DocumentSnapshot snap = Tasks.await(ref.get(Source.SERVER),
                    SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return snap.exists();
        } catch (ExecutionException e) {
            if (codeOf(e.getCause()) == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                return false;
            }
            return null;
        } catch (TimeoutException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private void noteRetry(Context ctx, Outbox.Item item, Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();   // keep the "please stop" signal alive
        }
        Outbox.recordFailure(ctx, item.id, String.valueOf(e.getMessage()));
        SyncLog.w(ctx, TAG, "Will retry " + item.entity + " " + item.entityId + ": " + e.getMessage());
    }

    private static FirebaseFirestoreException.Code codeOf(Throwable t) {
        return t instanceof FirebaseFirestoreException
                ? ((FirebaseFirestoreException) t).getCode() : null;
    }

    /** Errors that will fail the same way every time, so retrying is pointless. */
    private static boolean isPermanent(FirebaseFirestoreException.Code code) {
        return code == FirebaseFirestoreException.Code.PERMISSION_DENIED
                || code == FirebaseFirestoreException.Code.INVALID_ARGUMENT
                || code == FirebaseFirestoreException.Code.FAILED_PRECONDITION
                || code == FirebaseFirestoreException.Code.OUT_OF_RANGE
                || code == FirebaseFirestoreException.Code.UNIMPLEMENTED;
    }

    // ================================================================ JOB 2: registration

    /**
     * Turns a saved offline sign-up into a real account.
     *
     * CHANGED:
     *   - After the account and profile exist, every row saved under the PROVISIONAL id is
     *     re-labelled with the real uid (rekeyUid) BEFORE the pending registration is cleared.
     *     If the phone dies in between, the next run repeats harmlessly (the account already
     *     exists, so it signs in, rewrites the same profile and re-keys nothing).
     *   - The student is left SIGNED IN (no signOut), so the waiting Home screen turns into the
     *     real Home by itself.
     *   - A permanent failure is remembered (SessionUser) so Home can explain it and offer
     *     "Start over" instead of waiting forever.
     */
    private Step syncPendingRegistration(Context ctx) {
        try {
            JSONObject p = PendingRegistrationStore.load(ctx);
            if (p == null) {
                return Step.OK;   // nothing to do
            }

            // NEW (account isolation): only act on a registration the APP still considers waiting.
            // Without this check, ANY leftover registration signed its account in whenever nobody
            // was signed in, which is how a signed-out account kept coming back at every launch.
            if (!SessionUser.isRegistrationStillWanted(ctx, p.optString("localUid", ""))) {
                PendingRegistrationStore.clear(ctx);
                SyncLog.w(ctx, TAG, "Discarded a leftover registration nobody is waiting for");
                return Step.OK;
            }

            FirebaseAuth auth = FirebaseAuth.getInstance();
            String email = p.getString("email");
            String password = p.getString("password");

            FirebaseUser current = auth.getCurrentUser();
            if (current != null && !email.equalsIgnoreCase(current.getEmail())) {
                SyncLog.i(ctx, TAG, "Someone else is signed in: registration waits");
                return Step.OK;
            }

            FirebaseUser user;
            try {
                user = Tasks.await(auth.createUserWithEmailAndPassword(email, password),
                        AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS).getUser();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof FirebaseAuthUserCollisionException) {
                    // An earlier attempt already created the account: continue from there
                    user = Tasks.await(auth.signInWithEmailAndPassword(email, password),
                            AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS).getUser();
                } else {
                    throw e;
                }
            }

            // NEW: the student may have signed out or cancelled while the account was being made.
            // Check again, and if so do NOT leave that account signed in.
            if (!SessionUser.isRegistrationStillWanted(ctx, p.optString("localUid", ""))) {
                auth.signOut();
                PendingRegistrationStore.clear(ctx);
                SyncLog.w(ctx, TAG, "Registration cancelled while it was being delivered: signed out again");
                return Step.OK;
            }

            Map<String, Object> profile = new HashMap<>();
            profile.put("fullName", p.getString("fullName"));
            profile.put("idNumber", p.getString("idNumber"));
            profile.put("registrationNo", p.getString("registrationNo"));
            profile.put("email", email);
            profile.put("gender", p.getString("gender"));
            profile.put("department", p.getString("department"));
            profile.put("course", p.getString("course"));
            profile.put("createdAt", FieldValue.serverTimestamp());

            Tasks.await(FirebaseFirestore.getInstance()
                            .collection("users").document(user.getUid()).set(profile),
                    SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // NEW: hand everything the student saved while offline over to the real account
            String provisional = p.optString("localUid", "");
            if (!provisional.isEmpty()) {
                AppDatabase.get(ctx).rekeyUid(provisional, user.getUid());
            }

            PendingRegistrationStore.clear(ctx);   // only AFTER the server confirmed
            if (provisional.equals(SessionUser.provisionalUid(ctx))) {
                SessionUser.clearProvisional(ctx);
            }
            SyncLog.i(ctx, TAG, "Pending registration delivered; student left signed in");
            return Step.OK;

        } catch (ExecutionException e) {
            if (e.getCause() instanceof FirebaseNetworkException) {
                return Step.RETRY;               // still offline: try again later
            }
            SessionUser.setRegistrationError(ctx, friendlyRegistrationError(e.getCause()));
            SyncLog.e(ctx, TAG, "Registration failed permanently: " + e.getCause());
            return Step.FAILED;
        } catch (Exception e) {
            SyncLog.e(ctx, TAG, "Registration hit an unexpected error: " + e);
            return Step.RETRY;
        }
    }

    /** The sentence Home shows when the account could not be created. */
    private static String friendlyRegistrationError(Throwable cause) {
        if (cause instanceof FirebaseAuthInvalidCredentialsException
                || cause instanceof FirebaseAuthInvalidUserException) {
            return "This email is already registered with a different password.";
        }
        return "The server could not create your account.";
    }
}
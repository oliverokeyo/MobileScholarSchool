package com.example.mobilescholarschool.SignUp;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.mobilescholarschool.Components.PendingRegistrationStore;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class RegistrationSyncWorker extends Worker {

    public RegistrationSyncWorker(@NonNull Context ctx, @NonNull WorkerParameters p) {
        super(ctx, p);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        try {
            JSONObject p = PendingRegistrationStore.load(ctx);
            if (p == null) return Result.success();          // nothing to do

            FirebaseAuth auth = FirebaseAuth.getInstance();
            String email = p.getString("email");
            String password = p.getString("password");

            FirebaseUser user;
            try {
                user = Tasks.await(auth.createUserWithEmailAndPassword(email, password)).getUser();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof FirebaseAuthUserCollisionException) {
                    // An earlier attempt already created the account: continue from there
                    user = Tasks.await(auth.signInWithEmailAndPassword(email, password)).getUser();
                } else {
                    throw e;
                }
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
                    .collection("users").document(user.getUid()).set(profile));

            PendingRegistrationStore.clear(ctx);   // only AFTER the server confirmed
            auth.signOut();
            return Result.success();

        } catch (ExecutionException e) {
            if (e.getCause() instanceof FirebaseNetworkException) {
                return Result.retry();               // still offline: try again later
            }
            Log.e("RegSync", "Permanent failure", e);
            return Result.failure();
        } catch (Exception e) {
            Log.e("RegSync", "Unexpected error", e);
            return Result.retry();
        }
    }
}
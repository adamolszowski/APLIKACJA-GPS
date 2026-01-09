package com.example.aplikacjagps;

import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class MonitorActivity extends AppCompatActivity {

    private FirebaseAuth auth;
    private FirebaseFirestore db;

    private TextView tvStatus, tvLatLng, tvSessionId, tvTargetUid, tvTargetPublicId;

    private ValueEventListener locationListener;
    private DatabaseReference locationRef;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_monitor);

        tvStatus = findViewById(R.id.tvStatus);
        tvLatLng = findViewById(R.id.tvLatLng);
        tvSessionId = findViewById(R.id.tvSessionId);
        tvTargetUid = findViewById(R.id.tvTargetUid);
        tvTargetPublicId = findViewById(R.id.tvTargetPublicId);

        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        findAndListenActiveSession();
    }

    private void findAndListenActiveSession() {
        if (auth.getCurrentUser() == null) {
            tvStatus.setText("Brak zalogowanego użytkownika.");
            return;
        }

        String myUid = auth.getCurrentUser().getUid();
        tvStatus.setText("Szukam aktywnej sesji...");

        db.collection("sessions")
                .whereEqualTo("status", "active")
                .whereEqualTo("monitorUid", myUid)
                .limit(1)
                .get()
                .addOnSuccessListener(qs -> {
                    if (qs.isEmpty()) {
                        tvStatus.setText("Brak aktywnej sesji dla monitorującego.");
                        tvSessionId.setText("SessionId: -");
                        tvTargetUid.setText("Monitorowany UID: -");
                        tvTargetPublicId.setText("Monitorowany ID (public): -");
                        return;
                    }

                    DocumentSnapshot sessionDoc = qs.getDocuments().get(0);

                    String sessionId = sessionDoc.getId();
                    String targetUid = sessionDoc.getString("targetUid"); // <-- TO jest UID monitorowanego

                    tvStatus.setText("Sesja aktywna");
                    tvSessionId.setText("SessionId: " + sessionId);
                    tvTargetUid.setText("Monitorowany UID: " + (targetUid != null ? targetUid : "-"));

                    // (Opcjonalnie) pokaż publicId monitorowanego z users/{uid}
                    if (targetUid != null && !targetUid.isEmpty()) {
                        db.collection("users").document(targetUid)
                                .get()
                                .addOnSuccessListener(userDoc -> {
                                    String publicId = userDoc.getString("publicId");
                                    tvTargetPublicId.setText("Monitorowany ID (public): " +
                                            (publicId != null ? publicId : "-"));
                                })
                                .addOnFailureListener(e ->
                                        tvTargetPublicId.setText("Monitorowany ID (public): (błąd odczytu)")
                                );
                    } else {
                        tvTargetPublicId.setText("Monitorowany ID (public): -");
                    }

                    listenRtdb(sessionId);
                })
                .addOnFailureListener(e -> tvStatus.setText("Błąd Firestore: " + e.getMessage()));
    }

    private void listenRtdb(String sessionId) {
        locationRef = FirebaseDatabase.getInstance()
                .getReference("locations")
                .child(sessionId)
                .child("latest");

        locationListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    tvLatLng.setText("Brak danych lokalizacji jeszcze...");
                    return;
                }

                Double lat = snapshot.child("lat").getValue(Double.class);
                Double lng = snapshot.child("lng").getValue(Double.class);

                tvLatLng.setText("LAT: " + lat + "\nLNG: " + lng);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                tvLatLng.setText("RTDB error: " + error.getMessage());
            }
        };

        locationRef.addValueEventListener(locationListener);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (locationRef != null && locationListener != null) {
            locationRef.removeEventListener(locationListener);
        }
    }
}

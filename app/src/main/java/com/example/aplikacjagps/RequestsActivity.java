package com.example.aplikacjagps;

import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.HashMap;
import java.util.Map;

public class RequestsActivity extends AppCompatActivity {

    private static final long MIN_INTERVAL_SEC = 5L;
    private static final long DEFAULT_GPS_SEC = 60L;
    private static final long DEFAULT_BIO_SEC = 60L;
    private static final long BIO_WINDOW_SEC = 3L;

    private FirebaseAuth auth;
    private FirebaseFirestore db;
    private LinearLayout container;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_requests);

        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        container = findViewById(R.id.containerRequests);
    }

    @Override
    protected void onStart() {
        super.onStart();

        if (auth.getCurrentUser() == null) {
            finish();
            return;
        }

        loadPendingRequests();
    }

    private void loadPendingRequests() {
        container.removeAllViews();

        String myUid = auth.getCurrentUser().getUid();

        db.collection("requests")
                .whereEqualTo("toUid", myUid)
                .whereEqualTo("status", "pending")
                .get()
                .addOnSuccessListener(query -> {
                    if (query.isEmpty()) {
                        TextView tv = new TextView(this);
                        tv.setText("Brak oczekujących zapytań.");
                        tv.setTextSize(16f);
                        container.addView(tv);
                        return;
                    }

                    for (QueryDocumentSnapshot doc : query) {
                        String requestId = doc.getId();
                        String fromUid = doc.getString("fromUid");

                        Long gps = doc.getLong("gpsIntervalSec");
                        Long bio = doc.getLong("biometricIntervalSec");
                        Long win = doc.getLong("biometricWindowSec");

                        long gpsSec = clampMin5(gps, DEFAULT_GPS_SEC);
                        long bioSec = clampMin5(bio, DEFAULT_BIO_SEC);
                        long winSec = (win == null ? BIO_WINDOW_SEC : win);

                        container.addView(buildRequestCard(requestId, fromUid, gpsSec, bioSec, winSec));
                    }
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Błąd pobierania: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
    }

    private long clampMin5(Long v, long def) {
        long x = (v == null ? def : v);
        return Math.max(MIN_INTERVAL_SEC, x);
    }

    private LinearLayout buildRequestCard(String requestId, String fromUid, long gpsSec, long bioSec, long winSec) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(24, 24, 24, 24);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(0, 0, 0, 24);
        card.setLayoutParams(lp);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("Zapytanie o monitorowanie");
        tvTitle.setTextSize(18f);

        TextView tvFrom = new TextView(this);
        tvFrom.setText("Od UID: " + fromUid);

        TextView tvParams = new TextView(this);
        tvParams.setText("Parametry: GPS " + gpsSec + "s | Biometria " + bioSec + "s | Okno " + winSec + "s");

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button btnAccept = new Button(this);
        btnAccept.setText("Akceptuj");

        Button btnReject = new Button(this);
        btnReject.setText("Odrzuć");

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        blp.setMargins(0, 16, 16, 0);
        btnAccept.setLayoutParams(blp);

        LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        blp2.setMargins(16, 16, 0, 0);
        btnReject.setLayoutParams(blp2);

        btnAccept.setOnClickListener(v -> {
            if (!BiometricAuthHelper.canUseBiometrics(this)) {
                Toast.makeText(this, "Brak biometrii / brak dodanego odcisku palca", Toast.LENGTH_LONG).show();
                return;
            }

            BiometricAuthHelper.authenticate(
                    this,
                    "Potwierdź akceptację",
                    "Użyj odcisku palca",
                    "Wymagane uwierzytelnienie biometryczne",
                    () -> createSessionAndAccept(requestId)
            );
        });

        btnReject.setOnClickListener(v -> updateRequestStatus(requestId, "rejected"));

        buttons.addView(btnAccept);
        buttons.addView(btnReject);

        card.addView(tvTitle);
        card.addView(tvFrom);
        card.addView(tvParams);
        card.addView(buttons);

        return card;
    }

    private void updateRequestStatus(String requestId, String status) {
        db.collection("requests")
                .document(requestId)
                .update("status", status)
                .addOnSuccessListener(unused -> loadPendingRequests());
    }

    private void createSessionAndAccept(String requestId) {
        db.collection("requests").document(requestId).get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) return;

                    String fromUid = doc.getString("fromUid");
                    String toUid = doc.getString("toUid");

                    long gpsSec = clampMin5(doc.getLong("gpsIntervalSec"), DEFAULT_GPS_SEC);
                    long bioSec = clampMin5(doc.getLong("biometricIntervalSec"), DEFAULT_BIO_SEC);
                    long winSec = (doc.getLong("biometricWindowSec") == null ? BIO_WINDOW_SEC : doc.getLong("biometricWindowSec"));

                    Map<String, Object> session = new HashMap<>();
                    session.put("monitorUid", fromUid);
                    session.put("targetUid", toUid);
                    session.put("status", "active");
                    session.put("startedAt", FieldValue.serverTimestamp());

                    // parametry sesji:
                    session.put("gpsIntervalSec", gpsSec);
                    session.put("biometricIntervalSec", bioSec);
                    session.put("biometricWindowSec", winSec);

                    // status biometrii (dla monitora)
                    session.put("biometricLastResult", "n/a");
                    session.put("biometricLastOkAt", null);

                    db.collection("sessions")
                            .add(session)
                            .addOnSuccessListener(sessionRef -> {
                                String sessionId = sessionRef.getId();

                                doc.getReference().update(
                                        "status", "accepted",
                                        "sessionId", sessionId,
                                        "acceptedAt", FieldValue.serverTimestamp()
                                );

                                loadPendingRequests();
                            });
                });
    }
}

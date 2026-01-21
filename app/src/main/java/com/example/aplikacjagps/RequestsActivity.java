package com.example.aplikacjagps;

import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.FirebaseDatabase;
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


    private LinearLayout containerRequests;


    private LinearLayout containerActiveSessions;


    private final Map<String, String> publicIdCache = new HashMap<>();

    private interface PublicIdCallback {
        void onResult(String publicIdOrUid);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_requests);

        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        containerRequests = findViewById(R.id.containerRequests);
        containerActiveSessions = findViewById(R.id.containerActiveSessions);

        Button btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());
    }

    @Override
    protected void onStart() {
        super.onStart();

        if (auth.getCurrentUser() == null) {
            finish();
            return;
        }

        loadPendingRequests();
        loadActiveSessions();
    }


    private void getPublicIdOrUid(String uid, PublicIdCallback cb) {
        if (uid == null || uid.trim().isEmpty()) {
            cb.onResult("-");
            return;
        }

        if (publicIdCache.containsKey(uid)) {
            cb.onResult(publicIdCache.get(uid));
            return;
        }

        db.collection("users").document(uid)
                .get()
                .addOnSuccessListener(doc -> {
                    String publicId = null;
                    if (doc.exists()) publicId = doc.getString("publicId");

                    String result = (publicId != null && !publicId.trim().isEmpty()) ? publicId : uid;
                    publicIdCache.put(uid, result);
                    cb.onResult(result);
                })
                .addOnFailureListener(e -> {

                    publicIdCache.put(uid, uid);
                    cb.onResult(uid);
                });
    }


    private void loadPendingRequests() {
        containerRequests.removeAllViews();

        String myUid = auth.getCurrentUser().getUid();

        db.collection("requests")
                .whereEqualTo("toUid", myUid)
                .whereEqualTo("status", "pending")
                .get()
                .addOnSuccessListener(query -> {
                    if (query.isEmpty()) {
                        TextView tv = new TextView(this);
                        tv.setText("Brak oczekujących zapytań.");
                        tv.setTextSize(15f);
                        containerRequests.addView(tv);
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

                        containerRequests.addView(buildRequestCard(requestId, fromUid, gpsSec, bioSec, winSec));
                    }
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Błąd pobierania zaproszeń: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
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
        tvTitle.setText("Zaproszenie do monitorowania");
        tvTitle.setTextSize(17f);

        TextView tvFrom = new TextView(this);
        tvFrom.setText("Od ID: ...");


        getPublicIdOrUid(fromUid, publicId -> tvFrom.setText("Od ID: " + publicId));

        TextView tvParams = new TextView(this);
        tvParams.setText("Parametry: GPS " + gpsSec + "s | Biometria " + bioSec + "s | Okno " + winSec + "s");

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button btnAccept = new Button(this);
        btnAccept.setText("AKCEPTUJ");

        Button btnReject = new Button(this);
        btnReject.setText("ODRZUĆ");

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
                    Long winRaw = doc.getLong("biometricWindowSec");
                    long winSec = (winRaw == null ? BIO_WINDOW_SEC : winRaw);

                    Map<String, Object> session = new HashMap<>();
                    session.put("monitorUid", fromUid);
                    session.put("targetUid", toUid);
                    session.put("status", "active");
                    session.put("startedAt", FieldValue.serverTimestamp());

                    session.put("gpsIntervalSec", gpsSec);
                    session.put("biometricIntervalSec", bioSec);
                    session.put("biometricWindowSec", winSec);

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
                                loadActiveSessions();
                            });
                });
    }

    private long clampMin5(Long v, long def) {
        long x = (v == null ? def : v);
        return Math.max(MIN_INTERVAL_SEC, x);
    }


    private void loadActiveSessions() {
        containerActiveSessions.removeAllViews();

        String myUid = auth.getCurrentUser().getUid();

        db.collection("sessions")
                .whereEqualTo("status", "active")
                .whereEqualTo("monitorUid", myUid)
                .get()
                .addOnSuccessListener(qMonitor -> {

                    db.collection("sessions")
                            .whereEqualTo("status", "active")
                            .whereEqualTo("targetUid", myUid)
                            .get()
                            .addOnSuccessListener(qTarget -> {

                                boolean empty = qMonitor.isEmpty() && qTarget.isEmpty();
                                if (empty) {
                                    TextView tv = new TextView(this);
                                    tv.setText("Brak aktywnych sesji.");
                                    tv.setTextSize(15f);
                                    containerActiveSessions.addView(tv);
                                    return;
                                }

                                for (QueryDocumentSnapshot doc : qMonitor) {
                                    addActiveSessionCard(doc, "monitor");
                                }

                                for (QueryDocumentSnapshot doc : qTarget) {
                                    addActiveSessionCard(doc, "target");
                                }
                            });
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Błąd pobierania sesji: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
    }

    private void addActiveSessionCard(QueryDocumentSnapshot doc, String role) {
        String sessionId = doc.getId();
        String monitorUid = doc.getString("monitorUid");
        String targetUid = doc.getString("targetUid");

        Long gpsSec = doc.getLong("gpsIntervalSec");
        Long bioSec = doc.getLong("biometricIntervalSec");
        Long winSec = doc.getLong("biometricWindowSec");

        long g = (gpsSec == null ? DEFAULT_GPS_SEC : Math.max(MIN_INTERVAL_SEC, gpsSec));
        long b = (bioSec == null ? DEFAULT_BIO_SEC : Math.max(MIN_INTERVAL_SEC, bioSec));
        long w = (winSec == null ? BIO_WINDOW_SEC : winSec);

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
        tvTitle.setText("Aktywna sesja");
        tvTitle.setTextSize(17f);

        TextView tvWho = new TextView(this);
        tvWho.setText("...");

        if ("monitor".equals(role)) {

            getPublicIdOrUid(targetUid, publicId ->
                    tvWho.setText("Monitorujesz ID: " + publicId)
            );
        } else {

            getPublicIdOrUid(monitorUid, publicId ->
                    tvWho.setText("Jesteś monitorowany przez ID: " + publicId)
            );
        }

        TextView tvParams = new TextView(this);
        tvParams.setText("Parametry: GPS " + g + "s | Biometria " + b + "s | Okno " + w + "s");

        Button btnEnd = new Button(this);
        btnEnd.setText("ZAKOŃCZ SESJĘ");
        btnEnd.setOnClickListener(v -> endSessionWithBiometric(sessionId));

        card.addView(tvTitle);
        card.addView(tvWho);
        card.addView(tvParams);
        card.addView(btnEnd);

        containerActiveSessions.addView(card);
    }

    private void endSessionWithBiometric(String sessionId) {
        if (!BiometricAuthHelper.canUseBiometrics(this)) {
            Toast.makeText(this, "Brak biometrii / brak dodanego odcisku palca", Toast.LENGTH_LONG).show();
            return;
        }

        BiometricAuthHelper.authenticate(
                this,
                "Zakończ sesję",
                "Potwierdź odciskiem palca",
                "Zmieni status sesji na ended",
                () -> endSession(sessionId)
        );
    }

    private void endSession(String sessionId) {
        db.collection("sessions")
                .document(sessionId)
                .update(
                        "status", "ended",
                        "endedAt", FieldValue.serverTimestamp()
                )
                .addOnSuccessListener(unused -> {

                    FirebaseDatabase.getInstance()
                            .getReference("locations")
                            .child(sessionId)
                            .removeValue();

                    Toast.makeText(this, "Sesja zakończona", Toast.LENGTH_SHORT).show();
                    loadPendingRequests();
                    loadActiveSessions();
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Błąd kończenia sesji: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
    }
}

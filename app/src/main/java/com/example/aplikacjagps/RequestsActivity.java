package com.example.aplikacjagps;

import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.HashMap;
import java.util.Map;

import com.google.firebase.firestore.FieldValue;


public class RequestsActivity extends AppCompatActivity {

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

                        container.addView(buildRequestCard(requestId, fromUid));
                    }
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Błąd pobierania: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
    }

    private LinearLayout buildRequestCard(String requestId, String fromUid) {
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

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button btnAccept = new Button(this);
        btnAccept.setText("Akceptuj");

        Button btnReject = new Button(this);
        btnReject.setText("Odrzuć");

        // równy rozmiar przycisków
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        blp.setMargins(0, 16, 16, 0);
        btnAccept.setLayoutParams(blp);

        LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        blp2.setMargins(16, 16, 0, 0);
        btnReject.setLayoutParams(blp2);

        btnAccept.setOnClickListener(v -> {
            // Biometria wymagana do akceptacji requestu
            if (!BiometricAuthHelper.canUseBiometrics(this)) {
                Toast.makeText(this, "Brak biometrii / brak dodanego odcisku palca", Toast.LENGTH_LONG).show();
                return;
            }

            BiometricAuthHelper.authenticate(
                    this,
                    "Potwierdź akceptację",
                    "Użyj odcisku palca",
                    "Wymagane uwierzytelnienie biometryczne",
                    () -> updateRequestStatus(requestId, "accepted")
            );
        });

        btnReject.setOnClickListener(v -> updateRequestStatus(requestId, "rejected"));

        buttons.addView(btnAccept);
        buttons.addView(btnReject);

        card.addView(tvTitle);
        card.addView(tvFrom);
        card.addView(buttons);

        return card;
    }

    private void updateRequestStatus(String requestId, String status) {
        if (status.equals("accepted")) {
            createSessionAndAccept(requestId);
            return;
        }

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

                    Map<String, Object> session = new HashMap<>();
                    session.put("monitorUid", fromUid);
                    session.put("targetUid", toUid);
                    session.put("status", "active");
                    session.put("startedAt", FieldValue.serverTimestamp());

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

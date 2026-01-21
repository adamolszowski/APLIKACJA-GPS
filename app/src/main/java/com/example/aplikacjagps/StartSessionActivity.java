package com.example.aplikacjagps;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

public class StartSessionActivity extends AppCompatActivity {

    private static final long MIN_INTERVAL_SEC = 5L;
    private static final long DEFAULT_GPS_SEC = 60L;
    private static final long DEFAULT_BIO_SEC = 60L;
    private static final long BIO_WINDOW_SEC = 3L;

    private FirebaseAuth auth;
    private FirebaseFirestore db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_start_session);

        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        EditText etPublicId = findViewById(R.id.etPublicId);
        EditText etGpsInterval = findViewById(R.id.etGpsIntervalSec);
        EditText etBioInterval = findViewById(R.id.etBioIntervalSec);

        Button btnSend = findViewById(R.id.btnSendRequest);
        Button btnBack = findViewById(R.id.btnBack);

        btnBack.setOnClickListener(v -> finish());

        btnSend.setOnClickListener(v -> {
            if (auth.getCurrentUser() == null) {
                Toast.makeText(this, "Zaloguj się ponownie", Toast.LENGTH_LONG).show();
                finish();
                return;
            }

            String targetPublicId = etPublicId.getText().toString().trim();
            if (targetPublicId.isEmpty()) {
                Toast.makeText(this, "Podaj ID użytkownika", Toast.LENGTH_SHORT).show();
                return;
            }

            long gpsSec = parseIntervalOrDefault(etGpsInterval.getText().toString(), DEFAULT_GPS_SEC);
            long bioSec = parseIntervalOrDefault(etBioInterval.getText().toString(), DEFAULT_BIO_SEC);

            if (gpsSec < MIN_INTERVAL_SEC || bioSec < MIN_INTERVAL_SEC) {
                Toast.makeText(this, "Minimalny interwał to 5 sekund", Toast.LENGTH_LONG).show();
                return;
            }

            if (!BiometricAuthHelper.canUseBiometrics(this)) {
                Toast.makeText(this, "Brak biometrii / brak dodanego odcisku palca", Toast.LENGTH_LONG).show();
                return;
            }

            long finalGpsSec = gpsSec;
            long finalBioSec = bioSec;

            BiometricAuthHelper.authenticate(
                    this,
                    "Potwierdź wysłanie zapytania",
                    "Użyj odcisku palca",
                    "Wymagane uwierzytelnienie biometryczne",
                    () -> findUserAndCreateRequest(targetPublicId, finalGpsSec, finalBioSec)
            );
        });
    }

    private long parseIntervalOrDefault(String raw, long def) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return def;
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return def;
        }
    }

    private void findUserAndCreateRequest(String publicId, long gpsIntervalSec, long bioIntervalSec) {
        db.collection("users")
                .whereEqualTo("publicId", publicId)
                .limit(1)
                .get()
                .addOnSuccessListener(query -> {
                    if (query.isEmpty()) {
                        Toast.makeText(this, "Nie znaleziono użytkownika", Toast.LENGTH_LONG).show();
                        return;
                    }
                    String targetUid = query.getDocuments().get(0).getId();
                    createRequest(targetUid, gpsIntervalSec, bioIntervalSec);
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Błąd wyszukiwania", Toast.LENGTH_LONG).show()
                );
    }

    private void createRequest(String targetUid, long gpsIntervalSec, long bioIntervalSec) {
        String fromUid = auth.getCurrentUser().getUid();

        Map<String, Object> data = new HashMap<>();
        data.put("fromUid", fromUid);
        data.put("toUid", targetUid);
        data.put("status", "pending");
        data.put("createdAt", FieldValue.serverTimestamp());


        data.put("gpsIntervalSec", gpsIntervalSec);
        data.put("biometricIntervalSec", bioIntervalSec);
        data.put("biometricWindowSec", BIO_WINDOW_SEC);

        db.collection("requests")
                .add(data)
                .addOnSuccessListener(doc ->
                        Toast.makeText(this, "Zapytanie wysłane", Toast.LENGTH_LONG).show()
                )
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Błąd zapisu requestu", Toast.LENGTH_LONG).show()
                );
    }
}

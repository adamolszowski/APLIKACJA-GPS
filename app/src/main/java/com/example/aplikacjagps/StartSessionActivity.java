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

    private FirebaseAuth auth;
    private FirebaseFirestore db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_start_session);

        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        EditText etPublicId = findViewById(R.id.etPublicId);
        Button btnSend = findViewById(R.id.btnSendRequest);

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


            if (!BiometricAuthHelper.canUseBiometrics(this)) {
                Toast.makeText(this, "Brak biometrii / brak dodanego odcisku palca", Toast.LENGTH_LONG).show();
                return;
            }

            BiometricAuthHelper.authenticate(
                    this,
                    "Potwierdź wysłanie zapytania",
                    "Użyj odcisku palca",
                    "Wymagane uwierzytelnienie biometryczne",
                    () -> findUserAndCreateRequest(targetPublicId)
            );
        });

    }

    private void findUserAndCreateRequest(String publicId) {
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
                    createRequest(targetUid);
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Błąd wyszukiwania", Toast.LENGTH_LONG).show()
                );
    }

    private void createRequest(String targetUid) {
        String fromUid = auth.getCurrentUser().getUid();

        Map<String, Object> data = new HashMap<>();
        data.put("fromUid", fromUid);
        data.put("toUid", targetUid);
        data.put("status", "pending");
        data.put("createdAt", FieldValue.serverTimestamp());

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

package com.example.aplikacjagps;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;

import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;


public class RegisterActivity extends AppCompatActivity {

    private FirebaseAuth auth;
    private FirebaseFirestore db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);
        db = FirebaseFirestore.getInstance();
        auth = FirebaseAuth.getInstance();

        EditText etEmail = findViewById(R.id.etEmail);
        EditText etPassword = findViewById(R.id.etPassword);
        Button btnRegister = findViewById(R.id.btnRegister);
        Button btnGoLogin = findViewById(R.id.btnGoLogin);

        btnRegister.setOnClickListener(v -> {
            String email = etEmail.getText().toString().trim();
            String pass = etPassword.getText().toString();

            if (TextUtils.isEmpty(email) || TextUtils.isEmpty(pass)) {
                Toast.makeText(this, "Podaj email i hasło", Toast.LENGTH_SHORT).show();
                return;
            }

            auth.createUserWithEmailAndPassword(email, pass)
                    .addOnSuccessListener(result -> {
                        FirebaseUser user = auth.getCurrentUser();
                        if (user == null) {
                            Toast.makeText(this, "Błąd: brak użytkownika po rejestracji", Toast.LENGTH_LONG).show();
                            return;
                        }

                        String uid = user.getUid();
                        String publicId = generatePublicId(6); // np. 6 znaków

                        Map<String, Object> data = new HashMap<>();
                        data.put("publicId", publicId);
                        data.put("email", email);
                        data.put("createdAt", FieldValue.serverTimestamp());

                        db.collection("users").document(uid)
                                .set(data)
                                .addOnSuccessListener(unused -> {
                                    Toast.makeText(this, "Konto utworzone!", Toast.LENGTH_SHORT).show();
                                    startActivity(new Intent(this, HomeActivity.class));
                                    finish();
                                })
                                .addOnFailureListener(e ->
                                        Toast.makeText(this, "Błąd zapisu profilu: " + e.getMessage(), Toast.LENGTH_LONG).show()
                                );
                    })

                    .addOnFailureListener(e ->
                            Toast.makeText(this, "Błąd rejestracji: " + e.getMessage(), Toast.LENGTH_LONG).show()
                    );
        });

        btnGoLogin.setOnClickListener(v -> {
            finish(); // wraca do LoginActivity
        });
    }

    private String generatePublicId(int len) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // bez 0/O/I/1 (czytelniej)
        Random r = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(chars.charAt(r.nextInt(chars.length())));
        }
        return sb.toString().toUpperCase(Locale.ROOT);
    }

}

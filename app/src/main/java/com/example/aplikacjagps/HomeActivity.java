package com.example.aplikacjagps;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.DatabaseReference;

import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;

import android.widget.Toast;





public class HomeActivity extends AppCompatActivity {

    private FirebaseAuth auth;
    private FirebaseFirestore db;

    private TextView tvUid;
    private TextView tvPublicId;

    private java.util.Timer timer;
    private LocationSender locationSender;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        locationSender = new LocationSender(this);


        tvUid = findViewById(R.id.tvUid);
        tvPublicId = findViewById(R.id.tvPublicId);
        Button btnLogout = findViewById(R.id.btnLogout);

        btnLogout.setOnClickListener(v -> {
            stopService(new Intent(this, LocationForegroundService.class));
            auth.signOut();
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        });

        Button btnStartSession = findViewById(R.id.btnStartSession);

        btnStartSession.setOnClickListener(v -> {
            startActivity(new Intent(this, StartSessionActivity.class));
        });

        Button btnRequests = findViewById(R.id.btnRequests);
        btnRequests.setOnClickListener(v ->
                startActivity(new Intent(this, RequestsActivity.class))
        );

        Button btnOpenMonitor = findViewById(R.id.btnOpenMonitor);
        btnOpenMonitor.setOnClickListener(v -> {
            startActivity(new Intent(HomeActivity.this, MonitorActivity.class));
        });



    }

    private void startForegroundServiceIfNeeded() {
        Intent intent = new Intent(this, LocationForegroundService.class);
        ContextCompat.startForegroundService(this, intent);
    }



    @Override
    protected void onStart() {
        super.onStart();



        if (auth.getCurrentUser() == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        if (Build.VERSION.SDK_INT >= 33 &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    1001
            );
            return; // stop: start serwisu dopiero po decyzji usera
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    2001
            );
            return;
        }




        startServiceOnlyIfSessionActive();
        startSendingIfTargetHasActiveSession();


        String uid = auth.getCurrentUser().getUid();
        tvUid.setText("UID: " + uid);

        db.collection("users").document(uid)
                .get()
                .addOnSuccessListener(doc -> {
                    if (doc.exists()) {
                        String publicId = doc.getString("publicId");
                        tvPublicId.setText("Twoje ID: " + (publicId != null ? publicId : "brak"));
                    } else {
                        tvPublicId.setText("Twoje ID: (brak profilu)");
                    }
                })
                .addOnFailureListener(e -> tvPublicId.setText("Twoje ID: (błąd odczytu)"));


    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == 1001) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (auth.getCurrentUser() != null) {
                    startServiceOnlyIfSessionActive();
                }
            } else {
                Toast.makeText(this, "Bez zgody na powiadomienia serwis w tle może nie działać poprawnie.", Toast.LENGTH_LONG).show();
            }
        }

        if (requestCode == 2001) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // tu wrócimy do normalnego flow (np. ponownie onStart albo wywołanie sprawdzania sesji)
                startServiceOnlyIfSessionActive();
            } else {
                Toast.makeText(this, "Bez lokalizacji nie da się wysyłać pozycji.", Toast.LENGTH_LONG).show();
            }
        }

    }

    private void startServiceOnlyIfSessionActive() {
        String uid = auth.getCurrentUser().getUid();

        db.collection("sessions")
                .whereEqualTo("status", "active")
                .whereEqualTo("targetUid", uid)
                .limit(1)
                .get()
                .addOnSuccessListener(q1 -> {
                    if (!q1.isEmpty()) {
                        startForegroundServiceIfNeeded();
                        return;
                    }

                    db.collection("sessions")
                            .whereEqualTo("status", "active")
                            .whereEqualTo("monitorUid", uid)
                            .limit(1)
                            .get()
                            .addOnSuccessListener(q2 -> {
                                if (!q2.isEmpty()) startForegroundServiceIfNeeded();
                            });
                });
    }


    private void startSendingIfTargetHasActiveSession() {
        String uid = auth.getCurrentUser().getUid();

        db.collection("sessions")
                .whereEqualTo("status", "active")
                .whereEqualTo("targetUid", uid)
                .limit(1)
                .get()
                .addOnSuccessListener(q -> {
                    if (q.isEmpty()) {
                        stopSending();
                        return;
                    }

                    String sessionId = q.getDocuments().get(0).getId();
                    startSending(sessionId, uid);
                });
    }

    private void startSending(String sessionId, String uid) {
        stopSending(); // żeby nie odpalać kilku timerów

        timer = new java.util.Timer();
        timer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                locationSender.sendOnce(sessionId, uid);
            }
        }, 0, 60_000);
    }

    private void stopSending() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }


}

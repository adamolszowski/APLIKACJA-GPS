package com.example.aplikacjagps;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricPrompt;

import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.Locale;
import java.util.HashMap;
import java.util.Map;

public class MonitoredSessionActivity extends AppCompatActivity {

    private static final long MIN_INTERVAL_MS = 5_000L;
    private static final long DEFAULT_GPS_MS = 60_000L;
    private static final long DEFAULT_BIO_MS = 60_000L;
    private static final long BIO_WINDOW_MS = 3_000L;

    private FirebaseAuth auth;
    private FirebaseFirestore db;

    private TextView tvSessionStatus, tvMonitorPublicId, tvGpsInterval, tvBioInterval, tvLatLng, tvBioCountdown;
    private MaterialButton btnBack;

    private android.view.View root;

    private String sessionId;
    private String monitorUid;

    private long gpsIntervalMs = DEFAULT_GPS_MS;
    private long bioIntervalMs = DEFAULT_BIO_MS;

    private final Handler handler = new Handler(Looper.getMainLooper());

    // RTDB
    private DatabaseReference locationRef;
    private ValueEventListener locationListener;

    // biometria
    private boolean windowOpen = false;
    private long windowEndAt = 0L;
    private Runnable openWindowRunnable;
    private Runnable countdownRunnable;
    private BiometricPrompt currentPrompt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_monitored_session);

        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        root = findViewById(R.id.rootMonitored);

        tvSessionStatus = findViewById(R.id.tvSessionStatus);
        tvMonitorPublicId = findViewById(R.id.tvMonitorPublicId);
        tvGpsInterval = findViewById(R.id.tvGpsInterval);
        tvBioInterval = findViewById(R.id.tvBioInterval);
        tvLatLng = findViewById(R.id.tvLatLng);
        tvBioCountdown = findViewById(R.id.tvBioCountdown);
        btnBack = findViewById(R.id.btnBack);

        btnBack.setOnClickListener(v -> finish());
    }

    @Override
    protected void onStart() {
        super.onStart();

        if (auth.getCurrentUser() == null) {
            finish();
            return;
        }

        loadActiveSessionForMe();
    }

    private void loadActiveSessionForMe() {
        String uid = auth.getCurrentUser().getUid();

        tvSessionStatus.setText("Status: szukam aktywnej sesji...");

        db.collection("sessions")
                .whereEqualTo("status", "active")
                .whereEqualTo("targetUid", uid)
                .limit(1)
                .get()
                .addOnSuccessListener(q -> {
                    if (q.isEmpty()) {
                        tvSessionStatus.setText("Status: brak aktywnej sesji");
                        tvMonitorPublicId.setText("Monitoruje: -");
                        tvGpsInterval.setText("GPS: -");
                        tvBioInterval.setText("Biometria: -");
                        tvLatLng.setText("LAT/LNG: -");
                        tvBioCountdown.setText("Biometria: -");
                        stopAll();
                        return;
                    }

                    DocumentReference ref = q.getDocuments().get(0).getReference();
                    sessionId = ref.getId();

                    monitorUid = q.getDocuments().get(0).getString("monitorUid");

                    Long gpsSec = q.getDocuments().get(0).getLong("gpsIntervalSec");
                    Long bioSec = q.getDocuments().get(0).getLong("biometricIntervalSec");

                    gpsIntervalMs = (gpsSec == null ? DEFAULT_GPS_MS : Math.max(MIN_INTERVAL_MS, gpsSec * 1000L));
                    bioIntervalMs = (bioSec == null ? DEFAULT_BIO_MS : Math.max(MIN_INTERVAL_MS, bioSec * 1000L));

                    tvSessionStatus.setText("Status: sesja aktywna");
                    tvGpsInterval.setText("GPS: " + (gpsIntervalMs / 1000L) + "s");
                    tvBioInterval.setText("Biometria: " + (bioIntervalMs / 1000L) + "s (okno 3s)");

                    loadMonitorPublicId();
                    attachLocationListener();
                    scheduleNextBioWindow(bioIntervalMs);
                })
                .addOnFailureListener(e -> {
                    tvSessionStatus.setText("Status: błąd Firestore");
                    stopAll();
                });
    }

    private void loadMonitorPublicId() {
        if (monitorUid == null || monitorUid.isEmpty()) {
            tvMonitorPublicId.setText("Monitoruje: -");
            return;
        }

        db.collection("users").document(monitorUid)
                .get()
                .addOnSuccessListener(doc -> {
                    String publicId = doc.getString("publicId");
                    tvMonitorPublicId.setText("Monitoruje: " + (publicId != null ? publicId : monitorUid));
                })
                .addOnFailureListener(e -> tvMonitorPublicId.setText("Monitoruje: (błąd odczytu)"));
    }

    private void attachLocationListener() {
        detachLocationListener();

        if (sessionId == null) return;

        locationRef = FirebaseDatabase.getInstance()
                .getReference("locations")
                .child(sessionId)
                .child("latest");

        locationListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    tvLatLng.setText("LAT/LNG: brak danych");
                    return;
                }

                Double lat = snapshot.child("lat").getValue(Double.class);
                Double lng = snapshot.child("lng").getValue(Double.class);

                if (lat == null || lng == null) {
                    tvLatLng.setText("LAT/LNG: brak danych");
                    return;
                }

                tvLatLng.setText(String.format(Locale.US, "LAT/LNG: %.5f, %.5f", lat, lng));
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                tvLatLng.setText("LAT/LNG: błąd RTDB");
            }
        };

        locationRef.addValueEventListener(locationListener);
    }

    private void detachLocationListener() {
        if (locationRef != null && locationListener != null) {
            locationRef.removeEventListener(locationListener);
        }
        locationRef = null;
        locationListener = null;
    }

    // =========================
    // BIOMETRIA: okno 3 sekundy
    // =========================

    private void scheduleNextBioWindow(long delayMs) {
        cancelBioRunnables();

        openWindowRunnable = this::openBioWindow;
        handler.postDelayed(openWindowRunnable, delayMs);

        tvBioCountdown.setText("Biometria: następna próba za " + (delayMs / 1000L) + "s");
    }

    private void openBioWindow() {
        windowOpen = true;
        windowEndAt = System.currentTimeMillis() + BIO_WINDOW_MS;

        // tło na czerwono
        root.setBackgroundColor(Color.parseColor("#FFCDD2"));
        tvBioCountdown.setText("Biometria: OKNO OTWARTE (3s)");

        // start prompt od razu (wymuszenie)
        startBiometricPrompt();

        // licznik
        countdownRunnable = new Runnable() {
            @Override
            public void run() {
                long left = windowEndAt - System.currentTimeMillis();
                if (left <= 0) {
                    onBioWindowTimeout();
                    return;
                }
                tvBioCountdown.setText(String.format(Locale.US, "Biometria: OKNO (%.1fs)", left / 1000.0f));
                handler.postDelayed(this, 100);
            }
        };
        handler.post(countdownRunnable);
    }

    private void onBioWindowTimeout() {
        closeBioWindow(false);
        writeBioResult("miss");
        scheduleNextBioWindow(bioIntervalMs);
    }

    private void closeBioWindow(boolean success) {
        windowOpen = false;

        // przywróć tło
        root.setBackgroundColor(Color.WHITE);
        // zatrzymaj licznik
        if (countdownRunnable != null) handler.removeCallbacks(countdownRunnable);
        countdownRunnable = null;

        // anuluj prompt, jeśli dalej wisi
        if (currentPrompt != null) {
            try { currentPrompt.cancelAuthentication(); } catch (Exception ignored) {}
            currentPrompt = null;
        }

        tvBioCountdown.setText(success ? "Biometria: OK" : "Biometria: nieudana / timeout");
    }

    private void startBiometricPrompt() {
        if (!BiometricAuthHelper.canUseBiometrics(this)) {
            Toast.makeText(this, "Brak biometrii / brak odcisku", Toast.LENGTH_SHORT).show();
            return;
        }

        currentPrompt = BiometricAuthHelper.authenticateCancelable(
                this,
                "Potwierdź obecność",
                "Wymagane w trakcie sesji",
                "Masz 3 sekundy na autoryzację",
                () -> {
                    if (!windowOpen) return;

                    closeBioWindow(true);
                    writeBioResult("ok");
                    scheduleNextBioWindow(bioIntervalMs);
                },
                () -> {
                    // błąd/anulowanie – user może próbować ponownie aż do timeoutu
                }
        );
    }

    private void writeBioResult(String result) {
        if (sessionId == null) return;

        // Nie nadpisujemy biometricLastOkAt przy niepowodzeniu.
        // Dzięki temu monitorujący zawsze widzi: "ostatnio potwierdzone X temu".
        Map<String, Object> upd = new HashMap<>();
        upd.put("biometricLastResult", result);
        upd.put("biometricLastAttemptAt", FieldValue.serverTimestamp());

        if ("ok".equals(result)) {
            upd.put("biometricLastOkAt", FieldValue.serverTimestamp());
        }

        db.collection("sessions")
                .document(sessionId)
                .update(upd);
    } void cancelBioRunnables() {
        if (openWindowRunnable != null) handler.removeCallbacks(openWindowRunnable);
        if (countdownRunnable != null) handler.removeCallbacks(countdownRunnable);
        openWindowRunnable = null;
        countdownRunnable = null;
    }

    private void stopAll() {
        detachLocationListener();
        cancelBioRunnables();

        if (currentPrompt != null) {
            try { currentPrompt.cancelAuthentication(); } catch (Exception ignored) {}
            currentPrompt = null;
        }

        windowOpen = false;
        root.setBackgroundColor(Color.WHITE);
    }

    @Override
    protected void onStop() {
        super.onStop();

        detachLocationListener();
        cancelBioRunnables();

        if (currentPrompt != null) {
            try { currentPrompt.cancelAuthentication(); } catch (Exception ignored) {}
            currentPrompt = null;
        }

        windowOpen = false;
        root.setBackgroundColor(Color.WHITE);
    }
}

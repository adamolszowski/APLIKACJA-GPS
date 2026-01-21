package com.example.aplikacjagps;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.Locale;

public class MonitorActivity extends AppCompatActivity implements OnMapReadyCallback {

    private FirebaseAuth auth;
    private FirebaseFirestore db;

    private TextView tvStatus, tvLatLng, tvTargetPublicId;
    private TextView tvBiometricStatus, tvSessionParams;

    private ValueEventListener locationListener;
    private DatabaseReference locationRef;

    private ListenerRegistration sessionListener;
    private String sessionIdActive = null;

    private GoogleMap gMap;
    private Marker targetMarker;

    private boolean cameraInitialized = false;
    private static final float INITIAL_ZOOM = 16f;
    private static final long MARKER_ANIM_MS = 800;
    private static final int CAMERA_ANIM_MS = 700;

    private LatLng pendingPos = null;

    // Sesja: parametry + ostatnie OK biometrii
    private long gpsIntervalSec = 60L;
    private long biometricIntervalSec = 60L;
    private long biometricWindowSec = 3L;
    private Long lastBioOkMs = null;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable biometricTicker = new Runnable() {
        @Override
        public void run() {
            updateBiometricUi();
            uiHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_monitor);

        tvStatus = findViewById(R.id.tvStatus);
        tvLatLng = findViewById(R.id.tvLatLng);
        tvTargetPublicId = findViewById(R.id.tvTargetPublicId);
        tvBiometricStatus = findViewById(R.id.tvBiometricStatus);
        tvSessionParams = findViewById(R.id.tvSessionParams);

        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        setupMapFragment();
    }

    @Override
    protected void onStart() {
        super.onStart();
        findAndListenActiveSession();
        startBiometricTicker();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopBiometricTicker();
        detachRtdbListener();
        detachSessionListener();
    }

    private void setupMapFragment() {
        // Obsługa obu wariantów layoutu:
        // 1) <fragment android:id="@+id/map" .../>  -> findFragmentById
        // 2) FrameLayout/FragmentContainerView id=map -> podpinamy dynamicznie fragment (TAG: MAP)
        SupportMapFragment mapFragment =
                (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);

        if (mapFragment == null) {
            mapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentByTag("MAP");
        }

        if (mapFragment == null) {
            mapFragment = SupportMapFragment.newInstance();
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.map, mapFragment, "MAP")
                    .commitNow();
        }

        mapFragment.getMapAsync(this);
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        this.gMap = googleMap;

        // ✅ kontrolki przybliżania/oddalania
        gMap.getUiSettings().setZoomControlsEnabled(true);

        if (pendingPos != null) updateMapPosition(pendingPos);
    }

    private void findAndListenActiveSession() {
        if (auth.getCurrentUser() == null) {
            tvStatus.setText("Status: Brak zalogowanego użytkownika.");
            tvTargetPublicId.setText("Publiczne ID: -");
            tvLatLng.setText("LAT/LNG: -");
            tvBiometricStatus.setText("Biometria: -");
            tvSessionParams.setText("Parametry: -");
            sessionIdActive = null;
            return;
        }

        String myUid = auth.getCurrentUser().getUid();
        tvStatus.setText("Status: Szukam aktywnej sesji...");

        db.collection("sessions")
                .whereEqualTo("status", "active")
                .whereEqualTo("monitorUid", myUid)
                .limit(1)
                .get()
                .addOnSuccessListener(qs -> {
                    if (qs.isEmpty()) {
                        clearUiNoSession();
                        return;
                    }

                    DocumentSnapshot sessionDoc = qs.getDocuments().get(0);
                    sessionIdActive = sessionDoc.getId();
                    String targetUid = sessionDoc.getString("targetUid");

                    tvStatus.setText("Status: Sesja aktywna");

                    if (targetUid != null && !targetUid.isEmpty()) {
                        db.collection("users").document(targetUid)
                                .get()
                                .addOnSuccessListener(userDoc -> {
                                    String publicId = userDoc.getString("publicId");
                                    tvTargetPublicId.setText("Publiczne ID: " + (publicId != null ? publicId : "-"));
                                })
                                .addOnFailureListener(e ->
                                        tvTargetPublicId.setText("Publiczne ID: (błąd odczytu)")
                                );
                    } else {
                        tvTargetPublicId.setText("Publiczne ID: -");
                    }

                    targetMarker = null;
                    cameraInitialized = false;
                    pendingPos = null;

                    listenRtdb(sessionIdActive);
                    listenSessionDoc(sessionIdActive);
                })
                .addOnFailureListener(e ->
                        tvStatus.setText("Status: Błąd Firestore: " + e.getMessage())
                );
    }

    private void clearUiNoSession() {
        tvStatus.setText("Status: Brak aktywnej sesji.");
        tvTargetPublicId.setText("Publiczne ID: -");
        tvLatLng.setText("LAT/LNG: -");
        tvBiometricStatus.setText("Biometria: -");
        tvSessionParams.setText("Parametry: -");
        sessionIdActive = null;
        lastBioOkMs = null;
        detachRtdbListener();
        detachSessionListener();
    }

    private void listenSessionDoc(String sessionId) {
        detachSessionListener();

        sessionListener = db.collection("sessions")
                .document(sessionId)
                .addSnapshotListener((snap, err) -> {
                    if (err != null || snap == null || !snap.exists()) return;

                    String status = snap.getString("status");
                    if (status != null && !"active".equals(status)) {
                        tvStatus.setText("Status: Sesja zakończona (" + status + ")");
                        clearUiNoSession();
                        return;
                    }

                    Long gpsSec = snap.getLong("gpsIntervalSec");
                    Long bioSec = snap.getLong("biometricIntervalSec");
                    Long winSec = snap.getLong("biometricWindowSec");

                    gpsIntervalSec = clampMin5(gpsSec, 60L);
                    biometricIntervalSec = clampMin5(bioSec, 60L);
                    biometricWindowSec = (winSec == null) ? 3L : Math.max(0L, winSec);

                    tvSessionParams.setText("Parametry: GPS " + gpsIntervalSec + "s | Biometria " + biometricIntervalSec + "s | Okno " + biometricWindowSec + "s");

                    Object okObj = snap.get("biometricLastOkAt");
                    lastBioOkMs = parseFirestoreTimeToMs(okObj);
                    updateBiometricUi();
                });
    }

    private long clampMin5(Long v, long def) {
        long x = (v == null ? def : v);
        return Math.max(5L, x);
    }

    private Long parseFirestoreTimeToMs(Object v) {
        if (v == null) return null;

        if (v instanceof com.google.firebase.Timestamp) {
            return ((com.google.firebase.Timestamp) v).toDate().getTime();
        }
        if (v instanceof Long) {
            long x = (Long) v;
            return (x < 1000000000000L) ? (x * 1000L) : x; // sec -> ms
        }
        if (v instanceof Double) {
            long x = ((Double) v).longValue();
            return (x < 1000000000000L) ? (x * 1000L) : x;
        }
        return null;
    }


    private void updateBiometricUi() {
        if (tvBiometricStatus == null) return;

        if (lastBioOkMs == null) {
            tvBiometricStatus.setText("Biometria: BRAK POTWIERDZENIA (ostatnio: nigdy)");
            return;
        }

        long now = System.currentTimeMillis();
        long ageSec = Math.max(0L, (now - lastBioOkMs) / 1000L);

        boolean ok = ageSec <= (biometricIntervalSec + biometricWindowSec);

        if (ok) {
            tvBiometricStatus.setText("Biometria: OK (ostatnio: " + formatAgo(ageSec) + " temu)");
        } else {
            tvBiometricStatus.setText("Biometria: BRAK POTWIERDZENIA (ostatnio: " + formatAgo(ageSec) + " temu)");
        }
    }

    private String formatAgo(long sec) {
        if (sec < 60) return sec + "s";
        long min = sec / 60;
        long s = sec % 60;
        if (min < 60) return min + "m " + s + "s";
        long h = min / 60;
        long m = min % 60;
        return h + "h " + m + "m";
    }

    private void startBiometricTicker() {
        uiHandler.removeCallbacks(biometricTicker);
        uiHandler.post(biometricTicker);
    }

    private void stopBiometricTicker() {
        uiHandler.removeCallbacks(biometricTicker);
    }

    private void listenRtdb(String sessionId) {
        detachRtdbListener();

        locationRef = FirebaseDatabase.getInstance()
                .getReference("locations")
                .child(sessionId)
                .child("latest");

        locationListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Double lat = snapshot.child("lat").getValue(Double.class);
                Double lng = snapshot.child("lng").getValue(Double.class);

                if (lat == null || lng == null) return;

                LatLng pos = new LatLng(lat, lng);
                tvLatLng.setText(String.format(Locale.ROOT, "LAT/LNG: %.6f, %.6f", lat, lng));

                if (gMap == null) {
                    pendingPos = pos;
                    return;
                }

                updateMapPosition(pos);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                tvStatus.setText("Status: Błąd RTDB: " + error.getMessage());
            }
        };

        locationRef.addValueEventListener(locationListener);
    }

    private void updateMapPosition(@NonNull LatLng pos) {
        pendingPos = null;

        if (targetMarker == null) {
            targetMarker = gMap.addMarker(new MarkerOptions().position(pos).title("Monitorowany"));
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pos, INITIAL_ZOOM));
            cameraInitialized = true;
            return;
        }

        animateMarkerTo(targetMarker, pos, MARKER_ANIM_MS);

        if (cameraInitialized) {
            gMap.animateCamera(
                    CameraUpdateFactory.newLatLng(pos),
                    CAMERA_ANIM_MS,
                    null
            );
        } else {
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pos, INITIAL_ZOOM));
            cameraInitialized = true;
        }
    }

    private void animateMarkerTo(@NonNull Marker marker, @NonNull LatLng finalPosition, long durationMs) {
        LatLng start = marker.getPosition();
        long startTime = SystemClock.uptimeMillis();

        Interpolator interpolator = new LinearInterpolator();
        Handler handler = new Handler(Looper.getMainLooper());

        handler.post(new Runnable() {
            @Override
            public void run() {
                float t = (float) (SystemClock.uptimeMillis() - startTime) / (float) durationMs;
                float v = interpolator.getInterpolation(Math.min(1f, t));

                double lat = (finalPosition.latitude - start.latitude) * v + start.latitude;
                double lng = (finalPosition.longitude - start.longitude) * v + start.longitude;

                marker.setPosition(new LatLng(lat, lng));

                if (t < 1f) handler.postDelayed(this, 16);
            }
        });
    }

    private void detachRtdbListener() {
        if (locationRef != null && locationListener != null) {
            locationRef.removeEventListener(locationListener);
        }
        locationRef = null;
        locationListener = null;
    }

    private void detachSessionListener() {
        if (sessionListener != null) {
            sessionListener.remove();
        }
        sessionListener = null;
    }
}

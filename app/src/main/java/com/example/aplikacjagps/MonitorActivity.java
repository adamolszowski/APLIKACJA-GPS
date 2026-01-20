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

    private TextView tvStatus, tvLatLng, tvTargetPublicId, tvBiometricStatus, tvSessionParams;

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
        findAndListenActiveSession();
    }

    private void setupMapFragment() {
        SupportMapFragment mapFragment =
                (SupportMapFragment) getSupportFragmentManager().findFragmentByTag("MAP");

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
        detachRtdbListener();
        detachSessionListener();
    }

    private void listenSessionDoc(String sessionId) {
        detachSessionListener();

        sessionListener = db.collection("sessions")
                .document(sessionId)
                .addSnapshotListener((snap, err) -> {
                    if (err != null || snap == null || !snap.exists()) return;

                    Long gpsSec = snap.getLong("gpsIntervalSec");
                    Long bioSec = snap.getLong("biometricIntervalSec");
                    Long winSec = snap.getLong("biometricWindowSec");

                    long g = (gpsSec == null ? 60 : gpsSec);
                    long b = (bioSec == null ? 60 : bioSec);
                    long w = (winSec == null ? 3 : winSec);

                    tvSessionParams.setText("Parametry: GPS " + g + "s | Biometria " + b + "s | Okno " + w + "s");

                    String lastResult = snap.getString("biometricLastResult");
                    Timestamp lastOkAt = snap.getTimestamp("biometricLastOkAt");

                    if (lastResult == null) lastResult = "n/a";

                    if ("ok".equals(lastResult)) {
                        if (lastOkAt != null) {
                            long diffSec = Math.max(0, (System.currentTimeMillis() - lastOkAt.toDate().getTime()) / 1000L);
                            tvBiometricStatus.setText("Biometria: OK (" + diffSec + "s temu)");
                        } else {
                            tvBiometricStatus.setText("Biometria: OK");
                        }
                    } else if ("miss".equals(lastResult)) {
                        tvBiometricStatus.setText("Biometria: BRAK POTWIERDZENIA");
                    } else {
                        tvBiometricStatus.setText("Biometria: " + lastResult);
                    }
                });
    }

    private void detachSessionListener() {
        if (sessionListener != null) {
            sessionListener.remove();
            sessionListener = null;
        }
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

                pendingPos = new LatLng(lat, lng);
                updateMapPosition(pendingPos);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                tvLatLng.setText("LAT/LNG: błąd RTDB");
            }
        };

        locationRef.addValueEventListener(locationListener);
    }

    private void updateMapPosition(@NonNull LatLng pos) {
        if (gMap == null) return;

        if (targetMarker == null) {
            targetMarker = gMap.addMarker(new MarkerOptions()
                    .position(pos)
                    .title("Monitorowany"));

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

    @Override
    protected void onStop() {
        super.onStop();
        detachRtdbListener();
        detachSessionListener();
    }
}

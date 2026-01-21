package com.example.aplikacjagps;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

public class LocationForegroundService extends Service {

    private static final String CHANNEL_ID = "location_channel";
    private static final int NOTIF_ID = 101;

    private static final long MIN_INTERVAL_MS = 5_000L;
    private static final long DEFAULT_INTERVAL_MS = 60_000L;

    private FirebaseAuth auth;
    private FirebaseFirestore db;
    private LocationSender sender;

    private Handler handler;
    private Runnable tick;

    private String activeSessionId = null;
    private boolean isTarget = false;

    private long gpsIntervalMs = DEFAULT_INTERVAL_MS;

    private ListenerRegistration sessionListener;
    private boolean started = false;

    @Override
    public void onCreate() {
        super.onCreate();
        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        sender = new LocationSender(getApplicationContext());
        handler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification("Sesja monitorowania aktywna", "Aplikacja działa w tle"));


        if (!started) {
            started = true;
            loadActiveSessionAndStart();
        }

        return START_STICKY;
    }

    private void loadActiveSessionAndStart() {
        if (auth.getCurrentUser() == null) {
            stopSelf();
            return;
        }

        String uid = auth.getCurrentUser().getUid();

        db.collection("sessions")
                .whereEqualTo("status", "active")
                .whereEqualTo("targetUid", uid)
                .limit(1)
                .get()
                .addOnSuccessListener(q1 -> {
                    if (!q1.isEmpty()) {
                        activeSessionId = q1.getDocuments().get(0).getId();
                        isTarget = true;
                        attachSessionListenerAndStartLoop();
                        return;
                    }

                    db.collection("sessions")
                            .whereEqualTo("status", "active")
                            .whereEqualTo("monitorUid", uid)
                            .limit(1)
                            .get()
                            .addOnSuccessListener(q2 -> {
                                if (!q2.isEmpty()) {
                                    activeSessionId = q2.getDocuments().get(0).getId();
                                    isTarget = false;
                                    attachSessionListenerAndStartLoop();
                                } else {
                                    stopSelf();
                                }
                            });
                });
    }

    private void attachSessionListenerAndStartLoop() {
        detachSessionListener();

        if (activeSessionId == null) {
            stopSelf();
            return;
        }

        DocumentReference ref = db.collection("sessions").document(activeSessionId);

        sessionListener = ref.addSnapshotListener((snap, err) -> {
            if (err != null || snap == null || !snap.exists()) return;

            String status = snap.getString("status");
            if (!"active".equals(status)) {
                stopSelf();
                return;
            }

            Long gpsSec = snap.getLong("gpsIntervalSec");
            long newMs = (gpsSec == null ? DEFAULT_INTERVAL_MS : Math.max(MIN_INTERVAL_MS, gpsSec * 1000L));

            if (newMs != gpsIntervalMs) {
                gpsIntervalMs = newMs;
                startLoop();
            }
        });

        startLoop();
    }

    private void startLoop() {
        stopLoop();

        tick = new Runnable() {
            @Override
            public void run() {
                if (auth.getCurrentUser() == null || activeSessionId == null) {
                    stopSelf();
                    return;
                }

                if (isTarget) {
                    String uid = auth.getCurrentUser().getUid();
                    sender.sendOnce(activeSessionId, uid);
                    handler.postDelayed(this, gpsIntervalMs);
                } else {

                    handler.postDelayed(this, 30_000L);
                }
            }
        };

        handler.post(tick);
    }

    private void stopLoop() {
        if (handler != null && tick != null) handler.removeCallbacks(tick);
        tick = null;
    }

    private void detachSessionListener() {
        if (sessionListener != null) {
            sessionListener.remove();
            sessionListener = null;
        }
    }

    @Override
    public void onDestroy() {
        stopLoop();
        detachSessionListener();
        started = false;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    private Notification buildNotification(String title, String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "Location Tracking",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }
}

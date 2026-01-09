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
import com.google.firebase.firestore.FirebaseFirestore;

public class LocationForegroundService extends Service {

    private static final String CHANNEL_ID = "location_channel";
    private static final int NOTIF_ID = 101;

    private FirebaseAuth auth;
    private FirebaseFirestore db;

    private LocationSender sender;

    private Handler handler;
    private Runnable tick;

    private String activeSessionId = null;
    private boolean isTarget = false;

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

        loadActiveSessionAndStart();
        return START_STICKY;
    }

    private void loadActiveSessionAndStart() {
        if (auth.getCurrentUser() == null) {
            stopSelf();
            return;
        }

        String uid = auth.getCurrentUser().getUid();

        // 1) Czy user jest targetem?
        db.collection("sessions")
                .whereEqualTo("status", "active")
                .whereEqualTo("targetUid", uid)
                .limit(1)
                .get()
                .addOnSuccessListener(q1 -> {
                    if (!q1.isEmpty()) {
                        activeSessionId = q1.getDocuments().get(0).getId();
                        isTarget = true;
                        startLoop();
                        return;
                    }

                    // 2) Czy user jest monitorem?
                    db.collection("sessions")
                            .whereEqualTo("status", "active")
                            .whereEqualTo("monitorUid", uid)
                            .limit(1)
                            .get()
                            .addOnSuccessListener(q2 -> {
                                if (!q2.isEmpty()) {
                                    activeSessionId = q2.getDocuments().get(0).getId();
                                    isTarget = false; // monitor nie musi wysyłać lokalizacji
                                    startLoop();
                                } else {
                                    stopSelf();
                                }
                            });
                });
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

                String uid = auth.getCurrentUser().getUid();

                // Tylko target wysyła lokalizację (żeby nie nadpisywać "latest")
                if (isTarget) {
                    sender.sendOnce(activeSessionId, uid);
                }

                handler.postDelayed(this, 60_000);
            }
        };

        handler.post(tick);
    }

    private void stopLoop() {
        if (handler != null && tick != null) handler.removeCallbacks(tick);
    }

    @Override
    public void onDestroy() {
        stopLoop();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

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

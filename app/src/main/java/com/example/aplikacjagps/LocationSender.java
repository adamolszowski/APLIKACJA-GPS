package com.example.aplikacjagps;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Looper;
import android.os.SystemClock;

import androidx.core.app.ActivityCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.util.HashMap;
import java.util.Map;

public class LocationSender {

    private final Context context;
    private final FusedLocationProviderClient fusedClient;

    public LocationSender(Context context) {
        this.context = context.getApplicationContext();
        this.fusedClient = LocationServices.getFusedLocationProviderClient(this.context);
    }

    public void sendOnce(String sessionId, String uid) {
        boolean fine = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;

        if (!fine && !coarse) return;

        LocationRequest req = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                0
        )
                .setWaitForAccurateLocation(true)
                .setMaxUpdates(1)
                .build();

        LocationCallback cb = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                fusedClient.removeLocationUpdates(this);

                if (result == null) return;
                Location loc = result.getLastLocation();
                if (loc == null) return;

                pushToRtdb(sessionId, uid, loc);
            }
        };

        fusedClient.requestLocationUpdates(req, cb, Looper.getMainLooper());
    }

    private void pushToRtdb(String sessionId, String uid, Location location) {
        DatabaseReference ref = FirebaseDatabase.getInstance()
                .getReference("locations")
                .child(sessionId)
                .child("latest");

        Map<String, Object> data = new HashMap<>();
        data.put("uid", uid);
        data.put("lat", location.getLatitude());
        data.put("lng", location.getLongitude());
        data.put("accuracy", location.getAccuracy());
        data.put("timeMs", System.currentTimeMillis());
        data.put("elapsedRealtimeNanos", SystemClock.elapsedRealtimeNanos());

        ref.setValue(data);
    }
}

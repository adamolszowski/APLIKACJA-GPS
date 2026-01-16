package com.example.aplikacjagps;

import android.content.Context;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import java.util.concurrent.Executor;


public class BiometricAuthHelper {

    private BiometricAuthHelper() {
        // no instances
    }

    public static boolean canUseBiometrics(@NonNull Context context) {
        BiometricManager manager = BiometricManager.from(context);
        int result = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        return result == BiometricManager.BIOMETRIC_SUCCESS;
    }

    public static void authenticate(
            @NonNull AppCompatActivity activity,
            @NonNull String title,
            @NonNull String subtitle,
            @NonNull String description,
            @NonNull Runnable onSuccess
    ) {
        BiometricManager manager = BiometricManager.from(activity);
        int can = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);

        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(activity, "Biometria niedostępna lub brak dodanego odcisku palca", Toast.LENGTH_LONG).show();
            return;
        }

        Executor executor = ContextCompat.getMainExecutor(activity);

        BiometricPrompt prompt = new BiometricPrompt(activity, executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                onSuccess.run();
            }

            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                Toast.makeText(activity, errString, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
                Toast.makeText(activity, "Nieprawidłowy odcisk palca", Toast.LENGTH_SHORT).show();
            }
        });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setDescription(description)

                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("Anuluj")
                .build();

        prompt.authenticate(promptInfo);
    }
}

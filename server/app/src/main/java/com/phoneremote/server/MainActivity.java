package com.phoneremote.server;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.projection.MediaProjectionManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class MainActivity extends AppCompatActivity {
    private static final String KEYSTORE_ALIAS = "PhoneRemoteKey";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private final Executor backgroundExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ConnectivityManager.NetworkCallback networkCallback;
    private String cachedIpAddress = null;
    private static final int REQUEST_CODE_SCREEN_CAPTURE = 101;
    
    private TextView ipAddressTextView;
    private Button startServerButton;
    private Button stopServerButton;
    private Button screenCaptureButton;
    private CheckBox enableAuthCheckbox;
    private EditText usernameField;
    private EditText passwordField;
    private CheckBox enableFileTransferCheckbox;
    
    private boolean isServerRunning = false;
    private boolean isScreenCaptureRunning = false;
    
    private SharedPreferences prefs;
    private static final String PREF_NAME = "PhoneRemotePrefs";
    private static final String PREF_USERNAME = "username";
    private static final String PREF_PASSWORD = "password";
    private static final String PREF_AUTH_ENABLED = "auth_enabled";
    private static final String PREF_FILE_TRANSFER_ENABLED = "file_transfer_enabled";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setupNetworkCallback();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Initialize SharedPreferences
        prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);

        // Initialize UI components
        ipAddressTextView = findViewById(R.id.ip_address);
        startServerButton = findViewById(R.id.start_server);
        stopServerButton = findViewById(R.id.stop_server);
        screenCaptureButton = findViewById(R.id.screen_capture_button);
        enableAuthCheckbox = findViewById(R.id.enable_auth_checkbox);
        usernameField = findViewById(R.id.username_field);
        passwordField = findViewById(R.id.password_field);
        enableFileTransferCheckbox = findViewById(R.id.enable_file_transfer_checkbox);
        
        // Load saved preferences
        loadPreferences();

        startServerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Save authentication settings before starting server
                savePreferences();
                
                // Start the server service with the saved settings
                Intent serviceIntent = new Intent(MainActivity.this, RemoteServerService.class);
                serviceIntent.putExtra("enableAuth", enableAuthCheckbox.isChecked());
                serviceIntent.putExtra("username", usernameField.getText().toString());
                serviceIntent.putExtra("password", passwordField.getText().toString());
                serviceIntent.putExtra("enableFileTransfer", enableFileTransferCheckbox.isChecked());
                
                startService(serviceIntent);
                isServerRunning = true;
                updateButtons();
            }
        });

        stopServerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Stop both services
                stopService(new Intent(MainActivity.this, RemoteServerService.class));
                stopService(new Intent(MainActivity.this, ScreenCaptureService.class));
                isServerRunning = false;
                isScreenCaptureRunning = false;
                updateButtons();
            }
        });
        
        screenCaptureButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!isScreenCaptureRunning) {
                    startScreenCapture();
                } else {
                    stopScreenCapture();
                }
            }
        });
        
        enableAuthCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            usernameField.setEnabled(isChecked);
            passwordField.setEnabled(isChecked);
        });

        // Display the device's IP address
        displayIpAddress();
    }
    
    private void loadPreferences() {
        try {
            enableAuthCheckbox.setChecked(prefs.getBoolean(PREF_AUTH_ENABLED, false));
            usernameField.setText(prefs.getString(PREF_USERNAME, "admin"));
            String encryptedPassword = prefs.getString(PREF_PASSWORD, null);
            if (encryptedPassword != null) {
                passwordField.setText(decryptPassword(encryptedPassword));
            } else {
                passwordField.setText("");
            }
            enableFileTransferCheckbox.setChecked(prefs.getBoolean(PREF_FILE_TRANSFER_ENABLED, false));
            
            usernameField.setEnabled(enableAuthCheckbox.isChecked());
            passwordField.setEnabled(enableAuthCheckbox.isChecked());
        } catch (Exception e) {
            Toast.makeText(this, "Failed to load settings", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void savePreferences() {
        if (enableAuthCheckbox.isChecked() && !isPasswordValid(passwordField.getText().toString())) {
            Toast.makeText(this, "Password must be at least 8 characters with numbers and letters", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            SharedPreferences.Editor editor = prefs.edit();
            editor.putBoolean(PREF_AUTH_ENABLED, enableAuthCheckbox.isChecked());
            editor.putString(PREF_USERNAME, usernameField.getText().toString());
            editor.putString(PREF_PASSWORD, encryptPassword(passwordField.getText().toString()));
            editor.putBoolean(PREF_FILE_TRANSFER_ENABLED, enableFileTransferCheckbox.isChecked());
            editor.apply();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to save settings", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateButtons() {
        startServerButton.setEnabled(!isServerRunning);
        stopServerButton.setEnabled(isServerRunning);
        screenCaptureButton.setEnabled(isServerRunning);
        
        if (isScreenCaptureRunning) {
            screenCaptureButton.setText("Stop Screen Sharing");
        } else {
            screenCaptureButton.setText("Start Screen Sharing");
        }
        
        // Disable auth settings when server is running
        enableAuthCheckbox.setEnabled(!isServerRunning);
        usernameField.setEnabled(!isServerRunning && enableAuthCheckbox.isChecked());
        passwordField.setEnabled(!isServerRunning && enableAuthCheckbox.isChecked());
        enableFileTransferCheckbox.setEnabled(!isServerRunning);
    }
    
    private void startScreenCapture() {
        MediaProjectionManager projectionManager = 
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        
        Intent captureIntent = projectionManager.createScreenCaptureIntent();
        startActivityForResult(captureIntent, REQUEST_CODE_SCREEN_CAPTURE);
    }
    
    private void stopScreenCapture() {
        stopService(new Intent(MainActivity.this, ScreenCaptureService.class));
        isScreenCaptureRunning = false;
        updateButtons();
        Toast.makeText(this, "Screen sharing stopped", Toast.LENGTH_SHORT).show();
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                // Start the screen capture service
                Intent intent = new Intent(this, ScreenCaptureService.class);
                intent.putExtra("resultCode", resultCode);
                intent.putExtra("data", data);
                startService(intent);
                
                isScreenCaptureRunning = true;
                updateButtons();
                Toast.makeText(this, "Screen sharing started", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void displayIpAddress() {
        if (cachedIpAddress != null) {
            ipAddressTextView.setText("Device IP: " + cachedIpAddress + ":8080");
            return;
        }

        backgroundExecutor.execute(() -> {
            try {
                for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                    NetworkInterface intf = en.nextElement();
                    for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                        InetAddress inetAddress = enumIpAddr.nextElement();
                        if (!inetAddress.isLoopbackAddress() && !inetAddress.getHostAddress().contains(":")) {
                            cachedIpAddress = inetAddress.getHostAddress();
                            mainHandler.post(() -> 
                                ipAddressTextView.setText("Device IP: " + cachedIpAddress + ":8080"));
                            return;
                        }
                    }
                }
                mainHandler.post(() -> ipAddressTextView.setText("Unable to get IP address"));
            } catch (Exception e) {
                mainHandler.post(() -> ipAddressTextView.setText("Unable to get IP address"));
            }
        });
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Save preferences when activity is destroyed
        savePreferences();
    }
}

package com.phoneremote.server;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public class MainActivity extends AppCompatActivity {
    private TextView ipAddressTextView;
    private Button startServerButton;
    private Button stopServerButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ipAddressTextView = findViewById(R.id.ip_address);
        startServerButton = findViewById(R.id.start_server);
        stopServerButton = findViewById(R.id.stop_server);

        startServerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startService(new Intent(MainActivity.this, RemoteServerService.class));
                updateButtons(true);
            }
        });

        stopServerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopService(new Intent(MainActivity.this, RemoteServerService.class));
                updateButtons(false);
            }
        });

        // Display the device's IP address
        displayIpAddress();
    }

    private void updateButtons(boolean serverRunning) {
        startServerButton.setEnabled(!serverRunning);
        stopServerButton.setEnabled(serverRunning);
    }

    private void displayIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && !inetAddress.getHostAddress().contains(":")) {
                        ipAddressTextView.setText("Device IP: " + inetAddress.getHostAddress() + ":8080");
                        return;
                    }
                }
            }
        } catch (Exception e) {
            ipAddressTextView.setText("Unable to get IP address");
        }
    }
}

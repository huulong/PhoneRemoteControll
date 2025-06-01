package com.phoneremote.server;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import fi.iki.elonen.NanoHTTPD;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class RemoteServerService extends Service {
    private static final String TAG = "RemoteServerService";
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "RemoteServerChannel";
    
    private RemoteServer server;
    private PhoneController phoneController;

    @Override
    public void onCreate() {
        super.onCreate();
        phoneController = new PhoneController(this);
        startForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startServer();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopServer();
        super.onDestroy();
    }

    private void startServer() {
        if (server == null) {
            server = new RemoteServer();
            try {
                server.start();
                Log.i(TAG, "Server started on port 8080");
            } catch (IOException e) {
                Log.e(TAG, "Failed to start server", e);
            }
        }
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
            Log.i(TAG, "Server stopped");
        }
    }

    private void startForeground() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Remote Server Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            notificationManager.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Phone Remote Control")
                .setContentText("Remote server is running")
                .setSmallIcon(R.drawable.ic_notification)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    private class RemoteServer extends NanoHTTPD {
        public RemoteServer() {
            super(8080);
        }

        @Override
        public Response serve(IHTTPSession session) {
            String uri = session.getUri();
            Map<String, String> params = session.getParms();
            Method method = session.getMethod();

            Log.d(TAG, "Received " + method + " request: " + uri);

            if (method == Method.GET && uri.equals("/")) {
                return newFixedLengthResponse(getMainPage());
            } else if (method == Method.GET && uri.equals("/control.js")) {
                return newFixedLengthResponse(Response.Status.OK, "application/javascript", getControlJS());
            } else if (method == Method.GET && uri.equals("/style.css")) {
                return newFixedLengthResponse(Response.Status.OK, "text/css", getStyleCSS());
            } else if (method == Method.POST && uri.equals("/api/command")) {
                return handleCommand(params);
            }

            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found");
        }

        private Response handleCommand(Map<String, String> params) {
            String command = params.get("command");
            String value = params.get("value");
            
            if (command == null) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing command parameter");
            }
            
            boolean success = false;
            
            switch (command) {
                case "swipe":
                    success = phoneController.swipe(value);
                    break;
                case "tap":
                    String[] coords = value != null ? value.split(",") : null;
                    if (coords != null && coords.length == 2) {
                        try {
                            int x = Integer.parseInt(coords[0]);
                            int y = Integer.parseInt(coords[1]);
                            success = phoneController.tap(x, y);
                        } catch (NumberFormatException e) {
                            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid coordinates");
                        }
                    }
                    break;
                case "back":
                    success = phoneController.pressBack();
                    break;
                case "home":
                    success = phoneController.pressHome();
                    break;
                case "recents":
                    success = phoneController.pressRecents();
                    break;
                case "volume":
                    success = phoneController.adjustVolume(value);
                    break;
                default:
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Unknown command");
            }
            
            Map<String, String> response = new HashMap<>();
            response.put("success", String.valueOf(success));
            
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"success\":" + success + "}");
        }
        
        private String getMainPage() {
            return "<!DOCTYPE html>\n" +
                   "<html>\n" +
                   "<head>\n" +
                   "    <meta charset=\"UTF-8\">\n" +
                   "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                   "    <title>Phone Remote Control</title>\n" +
                   "    <link rel=\"stylesheet\" href=\"/style.css\">\n" +
                   "</head>\n" +
                   "<body>\n" +
                   "    <div class=\"container\">\n" +
                   "        <h1>Phone Remote Control</h1>\n" +
                   "        <div class=\"touch-area\" id=\"touchArea\">\n" +
                   "            <div class=\"phone-screen\"></div>\n" +
                   "        </div>\n" +
                   "        <div class=\"controls\">\n" +
                   "            <button id=\"backBtn\" class=\"control-btn\">Back</button>\n" +
                   "            <button id=\"homeBtn\" class=\"control-btn\">Home</button>\n" +
                   "            <button id=\"recentsBtn\" class=\"control-btn\">Recents</button>\n" +
                   "        </div>\n" +
                   "        <div class=\"volume-controls\">\n" +
                   "            <button id=\"volUpBtn\" class=\"control-btn\">Vol+</button>\n" +
                   "            <button id=\"volDownBtn\" class=\"control-btn\">Vol-</button>\n" +
                   "        </div>\n" +
                   "    </div>\n" +
                   "    <script src=\"/control.js\"></script>\n" +
                   "</body>\n" +
                   "</html>";
        }
        
        private String getControlJS() {
            return "document.addEventListener('DOMContentLoaded', function() {\n" +
                   "    const touchArea = document.getElementById('touchArea');\n" +
                   "    const backBtn = document.getElementById('backBtn');\n" +
                   "    const homeBtn = document.getElementById('homeBtn');\n" +
                   "    const recentsBtn = document.getElementById('recentsBtn');\n" +
                   "    const volUpBtn = document.getElementById('volUpBtn');\n" +
                   "    const volDownBtn = document.getElementById('volDownBtn');\n" +
                   "    \n" +
                   "    let startX, startY;\n" +
                   "    \n" +
                   "    // Handle touch/mouse events for the touch area\n" +
                   "    touchArea.addEventListener('mousedown', function(e) {\n" +
                   "        startX = e.offsetX;\n" +
                   "        startY = e.offsetY;\n" +
                   "    });\n" +
                   "    \n" +
                   "    touchArea.addEventListener('mouseup', function(e) {\n" +
                   "        const endX = e.offsetX;\n" +
                   "        const endY = e.offsetY;\n" +
                   "        \n" +
                   "        // Calculate distance moved\n" +
                   "        const distX = endX - startX;\n" +
                   "        const distY = endY - startY;\n" +
                   "        const distance = Math.sqrt(distX * distX + distY * distY);\n" +
                   "        \n" +
                   "        if (distance < 10) {\n" +
                   "            // It's a tap\n" +
                   "            sendCommand('tap', endX + ',' + endY);\n" +
                   "        } else {\n" +
                   "            // It's a swipe\n" +
                   "            let direction;\n" +
                   "            if (Math.abs(distX) > Math.abs(distY)) {\n" +
                   "                direction = distX > 0 ? 'right' : 'left';\n" +
                   "            } else {\n" +
                   "                direction = distY > 0 ? 'down' : 'up';\n" +
                   "            }\n" +
                   "            sendCommand('swipe', direction);\n" +
                   "        }\n" +
                   "    });\n" +
                   "    \n" +
                   "    // Handle button clicks\n" +
                   "    backBtn.addEventListener('click', function() {\n" +
                   "        sendCommand('back');\n" +
                   "    });\n" +
                   "    \n" +
                   "    homeBtn.addEventListener('click', function() {\n" +
                   "        sendCommand('home');\n" +
                   "    });\n" +
                   "    \n" +
                   "    recentsBtn.addEventListener('click', function() {\n" +
                   "        sendCommand('recents');\n" +
                   "    });\n" +
                   "    \n" +
                   "    volUpBtn.addEventListener('click', function() {\n" +
                   "        sendCommand('volume', 'up');\n" +
                   "    });\n" +
                   "    \n" +
                   "    volDownBtn.addEventListener('click', function() {\n" +
                   "        sendCommand('volume', 'down');\n" +
                   "    });\n" +
                   "    \n" +
                   "    // Function to send commands to the server\n" +
                   "    function sendCommand(command, value = '') {\n" +
                   "        const formData = new FormData();\n" +
                   "        formData.append('command', command);\n" +
                   "        if (value) {\n" +
                   "            formData.append('value', value);\n" +
                   "        }\n" +
                   "        \n" +
                   "        fetch('/api/command', {\n" +
                   "            method: 'POST',\n" +
                   "            body: formData\n" +
                   "        })\n" +
                   "        .then(response => response.json())\n" +
                   "        .then(data => {\n" +
                   "            console.log('Command result:', data);\n" +
                   "        })\n" +
                   "        .catch(error => {\n" +
                   "            console.error('Error sending command:', error);\n" +
                   "        });\n" +
                   "    }\n" +
                   "    \n" +
                   "    // Add touch event support\n" +
                   "    touchArea.addEventListener('touchstart', function(e) {\n" +
                   "        const touch = e.touches[0];\n" +
                   "        const rect = touchArea.getBoundingClientRect();\n" +
                   "        startX = touch.clientX - rect.left;\n" +
                   "        startY = touch.clientY - rect.top;\n" +
                   "        e.preventDefault();\n" +
                   "    });\n" +
                   "    \n" +
                   "    touchArea.addEventListener('touchend', function(e) {\n" +
                   "        const touch = e.changedTouches[0];\n" +
                   "        const rect = touchArea.getBoundingClientRect();\n" +
                   "        const endX = touch.clientX - rect.left;\n" +
                   "        const endY = touch.clientY - rect.top;\n" +
                   "        \n" +
                   "        const distX = endX - startX;\n" +
                   "        const distY = endY - startY;\n" +
                   "        const distance = Math.sqrt(distX * distX + distY * distY);\n" +
                   "        \n" +
                   "        if (distance < 10) {\n" +
                   "            sendCommand('tap', endX + ',' + endY);\n" +
                   "        } else {\n" +
                   "            let direction;\n" +
                   "            if (Math.abs(distX) > Math.abs(distY)) {\n" +
                   "                direction = distX > 0 ? 'right' : 'left';\n" +
                   "            } else {\n" +
                   "                direction = distY > 0 ? 'down' : 'up';\n" +
                   "            }\n" +
                   "            sendCommand('swipe', direction);\n" +
                   "        }\n" +
                   "        e.preventDefault();\n" +
                   "    });\n" +
                   "});\n";
        }
        
        private String getStyleCSS() {
            return "* {\n" +
                   "    box-sizing: border-box;\n" +
                   "    margin: 0;\n" +
                   "    padding: 0;\n" +
                   "}\n" +
                   "\n" +
                   "body {\n" +
                   "    font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;\n" +
                   "    background-color: #f5f5f5;\n" +
                   "    color: #333;\n" +
                   "    line-height: 1.6;\n" +
                   "}\n" +
                   "\n" +
                   ".container {\n" +
                   "    max-width: 800px;\n" +
                   "    margin: 0 auto;\n" +
                   "    padding: 20px;\n" +
                   "}\n" +
                   "\n" +
                   "h1 {\n" +
                   "    text-align: center;\n" +
                   "    margin-bottom: 20px;\n" +
                   "    color: #2c3e50;\n" +
                   "}\n" +
                   "\n" +
                   ".touch-area {\n" +
                   "    width: 100%;\n" +
                   "    height: 400px;\n" +
                   "    background-color: #fff;\n" +
                   "    border: 2px solid #ddd;\n" +
                   "    border-radius: 10px;\n" +
                   "    margin-bottom: 20px;\n" +
                   "    position: relative;\n" +
                   "    overflow: hidden;\n" +
                   "}\n" +
                   "\n" +
                   ".phone-screen {\n" +
                   "    position: absolute;\n" +
                   "    top: 0;\n" +
                   "    left: 0;\n" +
                   "    width: 100%;\n" +
                   "    height: 100%;\n" +
                   "    background-color: #333;\n" +
                   "    opacity: 0.1;\n" +
                   "}\n" +
                   "\n" +
                   ".controls, .volume-controls {\n" +
                   "    display: flex;\n" +
                   "    justify-content: center;\n" +
                   "    gap: 15px;\n" +
                   "    margin-bottom: 20px;\n" +
                   "}\n" +
                   "\n" +
                   ".control-btn {\n" +
                   "    padding: 10px 20px;\n" +
                   "    background-color: #3498db;\n" +
                   "    color: white;\n" +
                   "    border: none;\n" +
                   "    border-radius: 5px;\n" +
                   "    cursor: pointer;\n" +
                   "    font-size: 16px;\n" +
                   "    transition: background-color 0.3s;\n" +
                   "}\n" +
                   "\n" +
                   ".control-btn:hover {\n" +
                   "    background-color: #2980b9;\n" +
                   "}\n" +
                   "\n" +
                   "@media (max-width: 600px) {\n" +
                   "    .touch-area {\n" +
                   "        height: 300px;\n" +
                   "    }\n" +
                   "    \n" +
                   "    .control-btn {\n" +
                   "        padding: 8px 15px;\n" +
                   "        font-size: 14px;\n" +
                   "    }\n" +
                   "}\n";
        }
    }
}

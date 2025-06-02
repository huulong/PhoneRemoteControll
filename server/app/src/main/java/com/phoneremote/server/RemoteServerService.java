package com.phoneremote.server;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import androidx.core.app.NotificationCompat;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.WebSocketFrame;
import fi.iki.elonen.WebSocket;
import fi.iki.elonen.WebSocketResponseHandler;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RemoteServerService extends Service implements ScreenCaptureService.OnScreenCaptureListener {
    private static final String TAG = "RemoteServerService";
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "RemoteServerChannel";
    
    private RemoteServer server;
    private PhoneController phoneController;
    
    // Authentication settings
    private boolean authEnabled = false;
    private String username = "";
    private String password = "";
    
    // File transfer settings
    private boolean fileTransferEnabled = false;
    private static final String DOWNLOAD_DIR = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
    
    // Screen sharing
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private byte[] latestScreenCapture = null;
    private Map<WebSocket, Boolean> screenShareClients = new ConcurrentHashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        phoneController = new PhoneController(this);
        startForeground();
        
        // Register as listener for screen captures
        if (ScreenCaptureService.getInstance() != null) {
            ScreenCaptureService.getInstance().setOnScreenCaptureListener(this);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            // Get authentication settings from intent
            authEnabled = intent.getBooleanExtra("enableAuth", false);
            if (authEnabled) {
                username = intent.getStringExtra("username");
                password = intent.getStringExtra("password");
                Log.d(TAG, "Authentication enabled with username: " + username);
            }
            
            // Get file transfer settings
            fileTransferEnabled = intent.getBooleanExtra("enableFileTransfer", false);
            if (fileTransferEnabled) {
                Log.d(TAG, "File transfer enabled");
            }
        }
        
        startServer();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        // Unregister as listener for screen captures
        if (ScreenCaptureService.getInstance() != null) {
            ScreenCaptureService.getInstance().setOnScreenCaptureListener(null);
        }
        
        // Close all active WebSocket connections
        for (WebSocket socket : screenShareClients.keySet()) {
            try {
                socket.close(WebSocketFrame.CloseCode.NormalClosure, "Service shutting down", false);
            } catch (Exception e) {
                Log.e(TAG, "Error closing WebSocket", e);
            }
        }
        screenShareClients.clear();
        
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
    
    @Override
    public void onScreenCaptureAvailable(byte[] jpegData) {
        // Store the latest screen capture data
        this.latestScreenCapture = jpegData;
        
        // Send the screen capture to all connected WebSocket clients
        for (WebSocket socket : screenShareClients.keySet()) {
            try {
                if (socket.isOpen()) {
                    socket.send(jpegData);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sending screen capture over WebSocket", e);
                // Close problematic connections
                try {
                    socket.close(WebSocketFrame.CloseCode.AbnormalClosure, "Error sending data", false);
                    screenShareClients.remove(socket);
                } catch (Exception closeError) {
                    Log.e(TAG, "Error closing WebSocket", closeError);
                }
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
        private WebSocketResponseHandler webSocketHandler;
        
        public RemoteServer() {
            super(8080);
            
            webSocketHandler = new WebSocketResponseHandler(this) {
                @Override
                public WebSocket openWebSocket(IHTTPSession handshake) {
                    // Check authentication if enabled
                    if (authEnabled) {
                        String auth = handshake.getHeaders().get("authorization");
                        if (auth == null || !validateBasicAuth(auth)) {
                            return null; // Reject connection if auth fails
                        }
                    }
                    
                    // Create a new WebSocket for screen sharing
                    if ("/screen".equals(handshake.getUri())) {
                        return createScreenShareWebSocket();
                    }
                    
                    return null; // Reject other WebSocket connections
                }
            };
        }
        
        private WebSocket createScreenShareWebSocket() {
            return new WebSocket() {
                @Override
                public void onOpen() {
                    Log.d(TAG, "WebSocket connection opened for screen sharing");
                    screenShareClients.put(this, true);
                    
                    // Send the latest screen capture immediately if available
                    if (latestScreenCapture != null) {
                        try {
                            this.send(latestScreenCapture);
                        } catch (Exception e) {
                            Log.e(TAG, "Error sending initial screen capture", e);
                        }
                    }
                }
                
                @Override
                public void onClose(WebSocketFrame.CloseCode code, String reason, boolean initiatedByRemote) {
                    Log.d(TAG, "WebSocket connection closed: " + reason);
                    screenShareClients.remove(this);
                }
                
                @Override
                public void onMessage(WebSocketFrame message) {
                    // We don't expect any messages from client, but could implement commands here
                    Log.d(TAG, "Received WebSocket message: " + message.getTextPayload());
                }
                
                @Override
                public void onPong(WebSocketFrame pong) {
                    // Keep connection alive
                }
                
                @Override
                public void onException(IOException exception) {
                    Log.e(TAG, "WebSocket error", exception);
                    screenShareClients.remove(this);
                }
            };
        }

        @Override
        public Response serve(IHTTPSession session) {
            // Handle WebSocket upgrade requests
            if (session.getHeaders().get("connection") != null && 
                session.getHeaders().get("connection").toLowerCase().contains("upgrade") &&
                "websocket".equalsIgnoreCase(session.getHeaders().get("upgrade"))) {
                return webSocketHandler.serve(session);
            }
            
            String uri = session.getUri();
            Map<String, String> params = session.getParms();
            Method method = session.getMethod();
            
            Log.d(TAG, "Received " + method + " request: " + uri);
            
            // Check authentication if enabled
            if (authEnabled) {
                String auth = session.getHeaders().get("authorization");
                if (auth == null || !validateBasicAuth(auth)) {
                    Response response = newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized");
                    response.addHeader("WWW-Authenticate", "Basic realm=\"Phone Remote Control\"");
                    return response;
                }
            }
            
            // Serve files and handle API requests
            if (method == Method.GET) {
                if (uri.equals("/")) {
                    return newFixedLengthResponse(getMainPage());
                } else if (uri.equals("/control.js")) {
                    return newFixedLengthResponse(Response.Status.OK, "application/javascript", getControlJS());
                } else if (uri.equals("/style.css")) {
                    return newFixedLengthResponse(Response.Status.OK, "text/css", getStyleCSS());
                } else if (uri.startsWith("/files") && fileTransferEnabled) {
                    return handleFileOperation(uri, session);
                } else if (uri.equals("/screen.jpg") && latestScreenCapture != null) {
                    // Serve the latest screen capture for browsers that don't support WebSockets
                    return newFixedLengthResponse(Response.Status.OK, "image/jpeg", new ByteArrayInputStream(latestScreenCapture), latestScreenCapture.length);
                }
            } else if (method == Method.POST) {
                if (uri.equals("/api/command")) {
                    return handleCommand(params);
                } else if (uri.startsWith("/api/upload") && fileTransferEnabled) {
                    try {
                        // Parse multipart form data for file uploads
                        Map<String, String> files = new HashMap<>();
                        session.parseBody(files);
                        String tempFilePath = files.get("file");
                        
                        if (tempFilePath != null) {
                            // Get the original filename from parameters
                            String fileName = params.get("fileName");
                            if (fileName == null) fileName = "uploaded_file_" + System.currentTimeMillis();
                            
                            // Move the temporary file to the Downloads directory
                            File tempFile = new File(tempFilePath);
                            File destFile = new File(DOWNLOAD_DIR, fileName);
                            
                            // Copy the file
                            try (FileInputStream fis = new FileInputStream(tempFile);
                                 FileOutputStream fos = new FileOutputStream(destFile)) {
                                byte[] buffer = new byte[8192];
                                int length;
                                while ((length = fis.read(buffer)) > 0) {
                                    fos.write(buffer, 0, length);
                                }
                            }
                            
                            return newFixedLengthResponse("File uploaded successfully");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error handling file upload", e);
                        return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error uploading file");
                    }
                }
            }
            
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found");
        }
        
        private boolean validateBasicAuth(String authHeader) {
            if (authHeader != null && authHeader.startsWith("Basic ")) {
                String base64Credentials = authHeader.substring("Basic ".length());
                String credentials = new String(Base64.decode(base64Credentials, Base64.DEFAULT));
                final String[] values = credentials.split(":", 2);
                
                if (values.length == 2) {
                    String providedUsername = values[0];
                    String providedPassword = values[1];
                    
                    return username.equals(providedUsername) && password.equals(providedPassword);
                }
            }
            return false;
        }
        
        private Response handleFileOperation(String uri, IHTTPSession session) {
            if ("/files/list".equals(uri)) {
                // List files in the download directory
                File downloadDir = new File(DOWNLOAD_DIR);
                File[] files = downloadDir.listFiles();
                
                StringBuilder json = new StringBuilder("{\"files\":[")
                
                if (files != null) {
                    boolean first = true;
                    for (File file : files) {
                        if (file.isFile()) {
                            if (!first) json.append(",");
                            json.append("{\"name\":\"" + file.getName() + "\",");
                            json.append("\"size\":" + file.length() + ",");
                            json.append("\"date\":" + file.lastModified() + "}");
                            first = false;
                        }
                    }
                }
                
                json.append("]}");
                return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString());
            } else if (uri.startsWith("/files/download/")) {
                // Download a specific file
                String fileName = uri.substring("/files/download/".length());
                File file = new File(DOWNLOAD_DIR, fileName);
                
                if (file.exists() && file.isFile()) {
                    try {
                        FileInputStream fis = new FileInputStream(file);
                        Response response = newFixedLengthResponse(Response.Status.OK, getMimeTypeForFile(fileName), fis, file.length());
                        response.addHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
                        return response;
                    } catch (IOException e) {
                        Log.e(TAG, "Error serving file", e);
                    }
                }
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found");
            }
            
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid file operation");
        }
        
        private String getMimeTypeForFile(String fileName) {
            if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) return "image/jpeg";
            if (fileName.endsWith(".png")) return "image/png";
            if (fileName.endsWith(".pdf")) return "application/pdf";
            if (fileName.endsWith(".txt")) return "text/plain";
            if (fileName.endsWith(".html") || fileName.endsWith(".htm")) return "text/html";
            // Default mime type for binary files
            return "application/octet-stream";
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

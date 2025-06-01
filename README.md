# Phone Remote Control

This application allows you to remotely control an Android device through any web browser on the same network. It consists of two parts:

1. **Server App** - An Android application that runs on the device you want to control
2. **Web Client** - A web interface served by the Android app that you access from your browser

## Features

- Remote tap and swipe gestures
- Navigation controls (Back, Home, Recent apps)
- Volume control
- Real-time interaction through a simple web interface

## How to Use

### Setting up the Server (Android Device)

1. Build and install the Android app on the device you want to control
2. Launch the app
3. Press "Start Server" to begin hosting the control interface
4. Note the IP address and port shown on the screen (e.g., 192.168.1.100:8080)

### Controlling from Client (Web Browser)

1. Open a web browser on any device connected to the same network
2. Enter the IP address and port displayed on the Android device
3. Use the web interface to control the Android device:
   - Click/tap on the touch area to simulate touches
   - Drag to simulate swipes
   - Use the buttons for Back, Home, Recent apps, and volume controls

## Technical Notes

- The server uses NanoHTTPD to create a lightweight web server on the Android device
- For full functionality (like simulating taps and swipes), the app requires special permissions:
  - Either connect the device via ADB and grant permissions through shell commands
  - Or use the app on a rooted device
- Without these special permissions, some functions like simulating taps and swipes may not work

## Security Considerations

- The server does not implement authentication, so anyone on the same network can control the device
- Only use this app on trusted networks
- Stop the server when not in use

## Development

This project includes:

1. Android server app in the `/server` directory
2. Web client interface served directly by the Android app

To build the Android app, use Android Studio or Gradle from the command line in the `/server` directory.

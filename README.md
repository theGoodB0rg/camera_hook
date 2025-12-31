# CameraInterceptor

**CameraInterceptor** is a powerful Xposed/LSPosed module designed to intercept camera API requests on Android devices. It allows users to replace the live camera feed with a static image selected from their gallery, enabling image injection for testing, privacy, or development purposes.

## 🚀 Features

*   **Universal Camera Hook**: Intercepts multiple camera APIs including:
    *   `android.hardware.Camera` (Legacy)
    *   `android.hardware.camera2` (Modern)
    *   `androidx.camera` (CameraX)
*   **Image Injection**: Replace the real camera stream with any image from your device storage.
*   **Custom Image Picker**: Built-in UI to easily select the image you want to inject.
*   **Seamless Integration**: Works system-wide across apps that use the camera.
*   **Configurable**: Toggle the module on/off via settings.

## 📋 Prerequisites

*   **Android Device**: Running Android 8.0 (Oreo) or higher (`minSdk 26`).
*   **Root Access**: Required for Xposed framework.
*   **Xposed Framework**: LSPosed (recommended) or EdXposed installed and active.

## 🛠️ Installation

1.  **Download the APK**: Download the latest debug APK from the `releases` section or build from source.
2.  **Install the APK**: Install the `app-debug.apk` on your rooted Android device.
3.  **Activate Module**:
    *   Open your Xposed Manager (e.g., LSPosed).
    *   Enable the **Camera Interceptor** module.
    *   Select the target applications you want to intercept (e.g., System Framework, specific camera apps).
    *   **Reboot** your device (or restart the target apps/SystemUI) to apply changes.

## 💻 Usage

1.  Open the **Camera Interceptor** app from your app drawer.
2.  Grant the necessary permissions (Storage, Camera).
3.  Tap **Select Injection Image** to choose an image from your gallery.
4.  Ensure the "Enable Module" switch is turned **ON**.
5.  Open any app that uses the camera. The camera feed should now display your selected image instead of the live view.

## 🔧 Building from Source

To build this project locally:

1.  Clone the repository:
    ```bash
    git clone https://github.com/theGoodB0rg/camera_hook.git
    ```
2.  Open the project in **Android Studio**.
3.  Sync Gradle files.
4.  Build the project using `Build > Build Bundle(s) / APK(s) > Build APK(s)`.

## ⚠️ Disclaimer

This software is provided for **educational and testing purposes only**. The developers are not responsible for any misuse of this tool. Please use responsibly and respect the privacy and terms of service of third-party applications.

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## 📄 License

This project is open source.

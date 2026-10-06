# PrivPrint Shop Station for Windows

This is the native Compose Desktop shop/station application. It uses the Windows station worker for secure device registration, protected private-key storage, cloud queue handling, and printer spooler integration. The worker runs in the background; the visible interface is the installed desktop window, not a browser page.

The desktop shop workspace follows the Android shop app's light slate-and-blue design and includes shop-operator sign-in/registration, the permanent customer QR, the cloud print queue, Windows printer synchronization, station key/connection status, and the station audit log. Authorized jobs are printed by the worker when automatic printing is enabled; the dashboard switch persists that setting in the station configuration.

## Build

1. Build the station worker from the repository root. It creates `windows_agent\dist\PrivPrintStationWorker.exe`:

   ```powershell
   .\windows_agent\build_windows_exe.ps1
   ```

2. Build a Windows installer (JDK 17 required):

   ```powershell
   .\gradlew.bat :windowsApp:packageMsi
   ```

   Use `:windowsApp:packageExe` for an EXE installer. Installer outputs are under `windowsApp\build\compose\binaries\main`.

3. Install PrivPrint Shop Station, create/sign in to a shop operator account, then choose **Connect this Windows station**. Keep the application running while it receives print jobs.

The worker stores station credentials and its encryption private key using Windows DPAPI under `%LOCALAPPDATA%\PrivPrintStation`. Never share that directory or its contents.

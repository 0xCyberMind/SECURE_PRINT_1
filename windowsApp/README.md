# PrivPrint Shop Station for Windows

This is the native Compose Desktop shop/station application. It connects directly to the PrivPrint cloud over HTTPS/WSS, discovers printers through the Windows printing APIs, and does not bundle or start a local HTTP service or Python worker. Station credentials and its RSA private key are protected with Windows DPAPI for the signed-in Windows user.

## Build

1. Build the MSI from the repository root:

   ```powershell
   .\gradlew.bat :windowsApp:packageMsi
   ```

   The installer is written under `windowsApp\build\compose\binaries\main\msi`.

The worker stores existing station credentials and its encryption private key using Windows DPAPI under `%LOCALAPPDATA%\PrivPrintStation`. Never share that directory or its contents.

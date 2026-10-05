# PrivPrint Windows Station Agent

PrivPrint Shop Station is the native Compose Desktop app for a Xerox shop. The installer bundles the existing secure print station worker as a background component; when started by the native app it does not open a browser dashboard.

## Build a standalone executable

Run `windows_agent\build_windows_exe.ps1` from the VS Code PowerShell terminal. The build computer needs Python and PyInstaller; the target computer does not need Python installed.

Build the station worker with `windows_agent\build_windows_exe.ps1`, then build the native application installer with `.\gradlew.bat :windowsApp:packageMsi` (or `:windowsApp:packageExe`). The installer is written under `windowsApp\build\compose\binaries\main`. The shop operator creates an account and shop or signs in, then connects this station from the native app. Connecting registers the station's encryption public key before reporting a successful connection. New operators can create an account and shop during setup; newly created shops are active immediately.

The default server is `https://secure-print-1.onrender.com/`. Non-secret configuration and DPAPI-protected device credentials are stored under `%LOCALAPPDATA%\PrivPrintStation`. Access tokens and device keys are not saved in the readable configuration file. The station's RSA private key is stored with those credentials using Windows DPAPI; its public key is registered with the backend during connection and retried automatically if registration is temporarily unavailable. The native app reports when the encryption key is not registered. Phone uploads wrap each document's AES key for registered station public keys, and the station downloads only authorized ciphertext before decrypting it in memory. The example JSON is for advanced/manual configuration; copy it to `%LOCALAPPDATA%\PrivPrintStation\shop_station_config.json` only if needed. Do not share station credentials or configuration files.

Run the installed PrivPrint Shop Station application and follow its account setup. Keep the app running while using the station. Automatic printing is enabled after the operator authenticates the device. Station credentials and private-key data remain in the Windows-protected `%LOCALAPPDATA%\PrivPrintStation` store.

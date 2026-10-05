# PrivPrint Windows Station Agent

PrivPrint Windows Station is the local Windows app for a Xerox shop. It runs the print station agent and a local dashboard at `http://localhost:8888`.

## Build a standalone executable

Run `windows_agent\build_windows_exe.ps1` from the VS Code PowerShell terminal. The build computer needs Python and PyInstaller; the target computer does not need Python installed.

The executable and sample configuration are written under `windows_agent\dist\`. On first launch, the app creates its local settings directory and opens the dashboard in your browser. The shop operator signs in with email and password, selects an owned shop, and registers this Windows station. Connecting the station registers its encryption public key before the station reports a successful connection. New operators can create an account and shop from the setup screen; newly created shops are active immediately. No manual shop ID or token editing is required.

The default server is `https://secure-print-1.onrender.com/`. Non-secret configuration and DPAPI-protected device credentials are stored under `%LOCALAPPDATA%\PrivPrintStation`. Access tokens and device keys are not saved in the readable configuration file. The station's RSA private key is stored with those credentials using Windows DPAPI; its public key is registered with the backend during connection and retried automatically if registration is temporarily unavailable. The dashboard reports when the encryption key is not registered. Phone uploads wrap each document's AES key for registered station public keys, and the station downloads only authorized ciphertext before decrypting it in memory. The example JSON is for advanced/manual configuration; copy it to `%LOCALAPPDATA%\PrivPrintStation\shop_station_config.json` only if needed. Do not share station credentials or configuration files.

Run `PrivPrintWindowsStation.exe` and open `http://localhost:8888` in a browser. Keep the app running while using the station. Automatic printing is enabled after the operator authenticates the device.

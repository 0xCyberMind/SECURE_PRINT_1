# PrivPrint Windows Station Agent

PrivPrint Windows Station is the local Windows app for a Xerox shop. It runs the print station agent and a local dashboard at `http://localhost:8888`.

## Build a standalone executable

Run `windows_agent\build_windows_exe.ps1` from the VS Code PowerShell terminal. The build computer needs Python and PyInstaller; the target computer does not need Python installed.

The executable and sample configuration are written under `windows_agent\dist\`. On first launch, the app creates its local settings directory and opens the dashboard in your browser. The shop operator requests and verifies an SMS OTP, selects an owned shop, and registers this Windows station. Use a separate shop-operator phone number if the phone is already registered as a customer. No manual shop ID or token editing is required.

The default server is `https://secure-print-1.onrender.com/`. Non-secret configuration and DPAPI-protected device credentials are stored under `%LOCALAPPDATA%\PrivPrintStation`. Access tokens and device keys are not saved in the readable configuration file. The example JSON is for advanced/manual configuration; copy it to `%LOCALAPPDATA%\PrivPrintStation\shop_station_config.json` only if needed. Do not share station credentials or configuration files.

Run `PrivPrintWindowsStation.exe` and open `http://localhost:8888` in a browser. Keep the app running while using the station. Automatic printing is enabled after the operator authenticates the device.

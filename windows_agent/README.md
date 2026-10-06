# PrivPrint Windows Station Agent

The Windows station worker is a legacy standalone component. The native Compose Desktop installer no longer includes or uses it; the desktop app connects directly to the cloud and uses Windows printing APIs.

## Build a standalone executable

The standalone worker can be built with `windows_agent\build_windows_exe.ps1` for legacy deployments. This executable is not part of the desktop MSI.

Do not run a worker and desktop app together for the same station; the desktop app now owns cloud authentication, printer synchronization, and job processing.

The default production server is `https://secure-print-1.onrender.com/`; the development server can be selected explicitly. Non-secret configuration and DPAPI-protected device credentials are stored under `%LOCALAPPDATA%\PrivPrintStation`. Access tokens and device keys are not saved in the readable configuration file. The station's RSA private key is stored with those credentials using Windows DPAPI; its public key is registered with the backend during connection and retried automatically if registration is temporarily unavailable. The native app reports when the encryption key is not registered. Phone uploads wrap each document's AES key for registered station public keys, and the station downloads only authorized ciphertext before decrypting it in memory. The example JSON is for advanced/manual configuration; copy it to `%LOCALAPPDATA%\PrivPrintStation\shop_station_config.json` only if needed. Do not share station credentials or configuration files.

The desktop app stores its station credentials and encryption key in a Windows DPAPI-protected file under `%LOCALAPPDATA%\PrivPrintStation`.

## Printer setup and queue behavior

Install the shop's printer and driver in Windows, confirm it is online, and set it as the Windows default printer. Customer jobs that do not specify a printer are routed to that default. Document-output queues such as Microsoft Print to PDF, XPS, OneNote, and Fax are not treated as shop printers. If no usable default is installed, the job is marked failed with an explanation instead of being reported as successfully printed by a simulated printer. The desktop queue refreshes automatically while open; failed jobs show the station's failure reason.

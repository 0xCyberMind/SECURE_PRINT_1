import os
import json
import logging
import sys
from typing import Dict, Any
from pydantic import BaseModel, Field
from fastapi import FastAPI, HTTPException
from fastapi.responses import HTMLResponse, JSONResponse
from windows_agent.agent_service import WindowsAgentService

logger = logging.getLogger("WindowsDashboard")

dashboard_app = FastAPI(title="PrivPrint Windows Shop Station Dashboard", version="1.0.0")

# Reference to the running agent service
agent_service: WindowsAgentService = None

def init_dashboard(service: WindowsAgentService):
    global agent_service
    agent_service = service

DASHBOARD_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>PrivPrint — Windows Shop Station</title>
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap" rel="stylesheet">
    <style>
        :root {
            --primary: #1976d2;
            --primary-dark: #1565c0;
            --bg-dark: #0f172a;
            --card-bg: #1e293b;
            --border-color: #334155;
            --text-main: #f8fafc;
            --text-muted: #94a3b8;
            --success: #10b981;
            --warning: #f59e0b;
            --danger: #ef4444;
        }

        * { box-sizing: border-box; margin: 0; padding: 0; font-family: 'Inter', sans-serif; }
        body { background-color: var(--bg-dark); color: var(--text-main); display: flex; height: 100vh; overflow: hidden; }

        /* Sidebar Navigation */
        .sidebar { width: 260px; background-color: #090d16; border-right: 1px solid var(--border-color); display: flex; flex-direction: column; }
        .sidebar-header { padding: 24px 20px; border-bottom: 1px solid var(--border-color); display: flex; align-items: center; gap: 12px; }
        .sidebar-header h2 { font-size: 1.1rem; font-weight: 700; color: #38bdf8; letter-spacing: -0.5px; }
        .nav-links { list-style: none; padding: 16px 12px; flex: 1; }
        .nav-item { padding: 12px 16px; border-radius: 8px; margin-bottom: 4px; cursor: pointer; color: var(--text-muted); font-size: 0.9rem; font-weight: 500; display: flex; align-items: center; gap: 12px; transition: all 0.2s; }
        .nav-item:hover, .nav-item.active { background-color: var(--card-bg); color: var(--text-main); }
        .nav-item.active { border-left: 4px solid var(--primary); font-weight: 600; }

        /* Main Content Panel */
        .main-content { flex: 1; display: flex; flex-direction: column; overflow: hidden; }
        .top-bar { height: 64px; border-bottom: 1px solid var(--border-color); display: flex; align-items: center; justify-content: space-between; padding: 0 32px; background-color: rgba(30, 41, 59, 0.4); }
        .status-badge { display: flex; align-items: center; gap: 8px; font-size: 0.85rem; font-weight: 600; padding: 6px 14px; border-radius: 20px; background: rgba(16, 185, 129, 0.15); color: var(--success); border: 1px solid rgba(16, 185, 129, 0.3); }
        .status-dot { width: 8px; height: 8px; border-radius: 50%; background-color: var(--success); }

        .content-body { flex: 1; padding: 32px; overflow-y: auto; }
        .tab-content { display: none; }
        .tab-content.active { display: block; }

        /* Cards & Metrics Grid */
        .metrics-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 20px; margin-bottom: 32px; }
        .metric-card { background-color: var(--card-bg); border: 1px solid var(--border-color); border-radius: 12px; padding: 20px; }
        .metric-title { font-size: 0.8rem; text-transform: uppercase; color: var(--text-muted); font-weight: 600; margin-bottom: 8px; }
        .metric-value { font-size: 1.8rem; font-weight: 700; color: var(--text-main); }

        /* Table Design */
        .table-container { background-color: var(--card-bg); border: 1px solid var(--border-color); border-radius: 12px; overflow: hidden; }
        table { width: 100%; border-collapse: collapse; text-align: left; font-size: 0.9rem; }
        th { background-color: rgba(15, 23, 42, 0.6); padding: 14px 20px; font-weight: 600; color: var(--text-muted); border-bottom: 1px solid var(--border-color); }
        td { padding: 16px 20px; border-bottom: 1px solid var(--border-color); color: var(--text-main); }
        tr:last-child td { border-bottom: none; }

        /* Form Inputs */
        .form-group { margin-bottom: 20px; }
        label { display: block; font-size: 0.85rem; color: var(--text-muted); margin-bottom: 8px; font-weight: 500; }
        input[type="text"], select { width: 100%; max-width: 480px; padding: 10px 14px; background-color: #0f172a; border: 1px solid var(--border-color); border-radius: 8px; color: #fff; font-size: 0.9rem; }
        .btn { padding: 10px 20px; background-color: var(--primary); color: #fff; border: none; border-radius: 8px; font-weight: 600; cursor: pointer; transition: background 0.2s; }
        .btn:hover { background-color: var(--primary-dark); }
        .auth-gate { position: fixed; inset: 0; z-index: 10; display: grid; place-items: center; padding: 24px; background: var(--bg-dark); }
        .auth-card { width: min(100%, 480px); padding: 32px; background: var(--card-bg); border: 1px solid var(--border-color); border-radius: 16px; }
        .auth-card h1 { margin-bottom: 10px; color: #38bdf8; font-size: 1.4rem; }
        .auth-card p { margin-bottom: 22px; color: var(--text-muted); line-height: 1.5; }
        .auth-card input, .auth-card select { width: 100%; max-width: none; margin-bottom: 16px; }
        .auth-card .btn { width: 100%; }
        .auth-message { min-height: 22px; margin-top: 14px; color: #fca5a5; }
        [hidden] { display: none !important; }
    </style>
</head>
<body>
    <section id="auth-gate" class="auth-gate" hidden>
        <div class="auth-card">
            <h1>Set up your Xerox station</h1>
            <p>Sign in with your shop-operator email and password. This device will be securely registered to one of your shops.</p>
            <div id="auth-login-step">
                <label for="operator-email">Operator email</label>
                <input id="operator-email" type="email" autocomplete="username" placeholder="you@example.com" maxlength="255">
                <label for="operator-password">Password</label>
                <input id="operator-password" type="password" autocomplete="current-password">
                <button id="login-button" class="btn" onclick="loginOperator()">Sign in</button>
                <button id="show-register-button" class="btn" type="button" onclick="showRegisterForm()">Create shop account</button>
            </div>
            <div id="auth-register-step" hidden>
                <label for="register-name">Your name</label>
                <input id="register-name" type="text" autocomplete="name" maxlength="255">
                <label for="register-email">Email</label>
                <input id="register-email" type="email" autocomplete="email" maxlength="255">
                <label for="register-password">Password (at least 8 characters)</label>
                <input id="register-password" type="password" autocomplete="new-password" minlength="8">
                <label for="register-shop-name">Shop name</label>
                <input id="register-shop-name" type="text" maxlength="255">
                <label for="register-shop-address">Shop address</label>
                <input id="register-shop-address" type="text" minlength="5">
                <button id="register-button" class="btn" onclick="registerOperator()">Create account and shop</button>
                <button class="btn" type="button" onclick="showLoginForm()">Back to sign in</button>
            </div>
            <div id="auth-shop-step" hidden>
                <label for="operator-shop">Choose your shop</label>
                <select id="operator-shop"></select>
                <button id="connect-shop-button" class="btn" onclick="connectStation()">Connect this station</button>
            </div>
            <div id="auth-message" class="auth-message" role="alert"></div>
        </div>
    </section>
    <div class="sidebar">
        <div class="sidebar-header">
            <h2>🖨️ PRIVPRINT</h2>
        </div>
        <ul class="nav-links">
            <li class="nav-item active" onclick="switchTab('overview', this)">📊 Overview</li>
            <li class="nav-item" onclick="switchTab('queue', this)">📋 Print Queue</li>
            <li class="nav-item" onclick="switchTab('printers', this)">🖨️ Local Printers</li>
            <li class="nav-item" onclick="switchTab('cloud', this)">☁️ Device & Cloud</li>
            <li class="nav-item" onclick="switchTab('history', this)">📜 Print History</li>
            <li class="nav-item" onclick="switchTab('audit', this)">🛡️ Security & Audit</li>
            <li class="nav-item" onclick="switchTab('settings', this)">⚙️ Settings</li>
        </ul>
    </div>

    <div class="main-content">
        <div class="top-bar">
            <h3 id="page-title">Station Overview</h3>
            <div class="status-badge">
                <div class="status-dot"></div>
                <span id="conn-state">Not connected</span>
            </div>
        </div>

        <div class="content-body">
            <!-- 1. OVERVIEW -->
            <div id="overview" class="tab-content active">
                <div class="metrics-grid">
                    <div class="metric-card">
                        <div class="metric-title">Active Shop ID</div>
                        <div class="metric-value" id="m-shop-id">SHOP-101</div>
                    </div>
                    <div class="metric-card">
                        <div class="metric-title">Discovered Printers</div>
                        <div class="metric-value" id="m-printers-count">3</div>
                    </div>
                    <div class="metric-card">
                        <div class="metric-title">Active Queue</div>
                        <div class="metric-value" id="m-queue-count">0</div>
                    </div>
                    <div class="metric-card">
                        <div class="metric-title">Jobs Completed Today</div>
                        <div class="metric-value" id="m-completed-count">12</div>
                    </div>
                </div>

                <div class="table-container">
                    <div style="padding: 20px; border-bottom: 1px solid var(--border-color); font-weight: 600;">Active Hardware Spooler Status</div>
                    <table>
                        <thead>
                            <tr><th>Printer Name</th><th>Model</th><th>Status</th><th>Paper Tray</th><th>Toner Level</th></tr>
                        </thead>
                        <tbody id="overview-printers-body">
                            <tr><td colspan="5" style="text-align: center; color: var(--text-muted);">Loading local printers...</td></tr>
                        </tbody>
                    </table>
                </div>
            </div>

            <!-- 2. PRINT QUEUE -->
            <div id="queue" class="tab-content">
                <div class="table-container">
                    <div style="padding: 20px; border-bottom: 1px solid var(--border-color); font-weight: 600;">Incoming Authorized Print Queue</div>
                    <table>
                        <thead>
                            <tr><th>Job ID</th><th>Document</th><th>Pages</th><th>Copies</th><th>Status</th><th>Action</th></tr>
                        </thead>
                        <tbody id="queue-table-body">
                            <tr><td colspan="6" style="text-align: center; color: var(--text-muted);">No active jobs in spooler queue.</td></tr>
                        </tbody>
                    </table>
                </div>
            </div>

            <!-- 3. LOCAL PRINTERS -->
            <div id="printers" class="tab-content">
                <div class="table-container">
                    <div style="padding: 20px; border-bottom: 1px solid var(--border-color); font-weight: 600;">Windows Hardware Printers & Drivers</div>
                    <table>
                        <thead>
                            <tr><th>ID</th><th>Device Name</th><th>Capabilities</th><th>Paper Tray</th><th>Toner</th><th>State</th></tr>
                        </thead>
                        <tbody id="printers-table-body"></tbody>
                    </table>
                </div>
            </div>

            <!-- 4. DEVICE & CLOUD -->
            <div id="cloud" class="tab-content">
                <div class="metric-card" style="margin-bottom: 20px;">
                    <h4 style="margin-bottom: 12px; color: #38bdf8;">Cloud Connection & WSS Stream</h4>
                    <p style="margin-bottom: 6px;"><strong>Endpoint:</strong> <span id="c-endpoint"></span></p>
                    <p style="margin-bottom: 6px;"><strong>Device ID:</strong> <span id="c-device-id">Not registered</span></p>
                    <p style="margin-bottom: 6px;"><strong>Role:</strong> PRINT_DEVICE</p>
                    <p style="margin-bottom: 6px;"><strong>WSS Channel:</strong> <span id="c-channel">shop:SHOP-101</span></p>
                    <p><strong>Heartbeat Interval:</strong> 15 seconds</p>
                </div>
            </div>

            <!-- 5. HISTORY -->
            <div id="history" class="tab-content">
                <div class="table-container">
                    <div style="padding: 20px; border-bottom: 1px solid var(--border-color); font-weight: 600;">Printed Jobs Audit Trail</div>
                    <table>
                        <thead>
                            <tr><th>Job ID</th><th>Document</th><th>Pages</th><th>Copies Printed</th><th>Status</th></tr>
                        </thead>
                        <tbody id="history-table-body"></tbody>
                    </table>
                </div>
            </div>

            <!-- 6. SECURITY & AUDIT -->
            <div id="audit" class="tab-content">
                <div class="table-container">
                    <div style="padding: 20px; border-bottom: 1px solid var(--border-color); font-weight: 600;">Zero-Knowledge Cryptographic Audit Log</div>
                    <table>
                        <thead>
                            <tr><th>Severity</th><th>Event</th><th>Details</th></tr>
                        </thead>
                        <tbody id="audit-table-body"></tbody>
                    </table>
                </div>
            </div>

            <!-- 7. SETTINGS -->
            <div id="settings" class="tab-content">
                <div class="metric-card" style="max-width: 600px;">
                    <h4 style="margin-bottom: 20px;">Station Configuration</h4>
                    <div class="form-group">
                        <label>PrivPrint Server Base URL</label>
                        <input type="text" id="s-server-url" value="https://secure-print-1.onrender.com/" readonly>
                    </div>
                    <div class="form-group">
                        <label>Assigned Xerox Shop ID</label>
                        <input type="text" id="s-shop-id" value="" readonly>
                    </div>
                    <div class="form-group">
                        <label>Station Device Name</label>
                        <input type="text" id="s-device-name" value="Windows Xerox Station Agent" readonly>
                    </div>
                </div>
            </div>
        </div>
    </div>

    <script>
        function switchTab(tabId, el) {
            document.querySelectorAll('.tab-content').forEach(t => t.classList.remove('active'));
            document.querySelectorAll('.nav-item').forEach(i => i.classList.remove('active'));
            document.getElementById(tabId).classList.add('active');
            el.classList.add('active');
            document.getElementById('page-title').innerText = el.innerText;
        }

        async function apiPost(path, payload) {
            const response = await fetch(path, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(payload)
            });
            const data = await response.json();
            if (!response.ok) throw new Error(data.detail || 'Request failed. Please try again.');
            return data;
        }

        function showAuthError(message) {
            document.getElementById('auth-message').innerText = message;
        }

        function showRegisterForm() {
            document.getElementById('auth-login-step').hidden = true;
            document.getElementById('auth-register-step').hidden = false;
            showAuthError('');
        }

        function showLoginForm() {
            document.getElementById('auth-register-step').hidden = true;
            document.getElementById('auth-login-step').hidden = false;
            showAuthError('');
        }

        async function showShops(result) {
            const selector = document.getElementById('operator-shop');
            selector.replaceChildren();
            for (const shop of result.shops) {
                const option = document.createElement('option');
                option.value = shop.id;
                option.textContent = shop.name;
                selector.appendChild(option);
            }
            document.getElementById('auth-shop-step').hidden = false;
        }

        async function loginOperator() {
            const button = document.getElementById('login-button');
            button.disabled = true;
            showAuthError('');
            try {
                const result = await apiPost('/api/auth/login', {
                    email: document.getElementById('operator-email').value,
                    password: document.getElementById('operator-password').value
                });
                await showShops(result);
            } catch (error) {
                showAuthError(error.message);
            } finally {
                button.disabled = false;
            }
        }

        async function registerOperator() {
            const button = document.getElementById('register-button');
            button.disabled = true;
            showAuthError('');
            try {
                const result = await apiPost('/api/auth/register', {
                    full_name: document.getElementById('register-name').value,
                    email: document.getElementById('register-email').value,
                    password: document.getElementById('register-password').value,
                    shop_name: document.getElementById('register-shop-name').value,
                    shop_address: document.getElementById('register-shop-address').value
                });
                await showShops(result);
            } catch (error) {
                showAuthError(error.message);
            } finally {
                button.disabled = false;
            }
        }

        async function connectStation() {
            const button = document.getElementById('connect-shop-button');
            button.disabled = true;
            showAuthError('');
            try {
                await apiPost('/api/auth/connect', {shop_id: document.getElementById('operator-shop').value});
                document.getElementById('auth-gate').hidden = true;
                await fetchStatus();
            } catch (error) {
                showAuthError(error.message);
            } finally {
                button.disabled = false;
            }
        }

        async function fetchStatus() {
            try {
                const res = await fetch('/api/status');
                const data = await res.json();
                document.getElementById('auth-gate').hidden = Boolean(data.authenticated);
                if (!data.authenticated) return;

                document.getElementById('m-shop-id').innerText = data.shop_id || 'SHOP-101';
                document.getElementById('m-printers-count').innerText = data.printers.length;
                document.getElementById('m-queue-count').innerText = Object.keys(data.active_jobs).length;
                document.getElementById('m-completed-count').innerText = data.job_history.length;
                document.getElementById('conn-state').innerText = !data.encryption_key_registered
                    ? 'Encryption key pending'
                    : (data.is_wss_connected ? 'WSS Connected' : 'Disconnected');
                document.getElementById('c-endpoint').innerText = data.server_base_url;
                document.getElementById('c-device-id').innerText = data.device_id || 'Not registered';
                document.getElementById('s-server-url').value = data.server_base_url;
                document.getElementById('s-shop-id').value = data.shop_id || '';

                // Render printers
                const pRows = data.printers.map(p => `
                    <tr>
                        <td><strong>${p.name}</strong></td>
                        <td>${p.model}</td>
                        <td><span style="color: #10b981; font-weight: 600;">${p.status}</span></td>
                        <td>${p.paper_tray_status} (${p.paper_size})</td>
                        <td>${p.toner_level_percent}%</td>
                    </tr>
                `).join('');
                document.getElementById('overview-printers-body').innerHTML = pRows;
                document.getElementById('printers-table-body').innerHTML = pRows.map((r, i) => `<tr><td>PRN-${i+1}</td>${r.substring(4)}`);

                // Render history
                const hRows = data.job_history.map(j => `
                    <tr>
                        <td><strong>${j.jobId || j.id}</strong></td>
                        <td>${j.documentName || 'Document.pdf'}</td>
                        <td>${j.pageCount || 1}</td>
                        <td>${j.copiesPrinted || 1}</td>
                        <td><span style="color:#10b981;">COMPLETED</span></td>
                    </tr>
                `).join('');
                document.getElementById('history-table-body').innerHTML = hRows.length ? hRows : '<tr><td colspan="5" style="text-align:center;">No history recorded yet.</td></tr>';

                // Render audit
                const aRows = data.audit_log.map(a => `
                    <tr>
                        <td><span style="padding: 2px 8px; border-radius: 4px; background: rgba(56,189,248,0.2); color: #38bdf8; font-size: 0.75rem; font-weight:700;">${a.severity}</span></td>
                        <td><strong>${a.eventType}</strong></td>
                        <td>${a.details}</td>
                    </tr>
                `).join('');
                document.getElementById('audit-table-body').innerHTML = aRows.length ? aRows : '<tr><td colspan="3" style="text-align:center;">No audit events.</td></tr>';

            } catch(e) {
                document.getElementById('auth-gate').hidden = false;
                showAuthError('Cannot connect to the local station service. Restart the Windows station app.');
            }
        }

        setInterval(fetchStatus, 3000);
        fetchStatus();
    </script>
</body>
</html>
"""

@dashboard_app.get("/", response_class=HTMLResponse)
async def get_dashboard_html():
    return HTMLResponse(content=DASHBOARD_HTML)

@dashboard_app.get("/api/status")
async def get_status_api():
    if not agent_service:
        return JSONResponse({"status": "starting"})

    printers = agent_service.spooler.discover_local_printers()
    return JSONResponse({
        "authenticated": bool(agent_service.config.access_token and agent_service.config.shop_id),
        "shop_id": agent_service.config.shop_id,
        "auto_print_enabled": agent_service.config.auto_print_enabled,
        "server_base_url": agent_service.config.server_base_url,
        "device_id": agent_service.config.device_id,
        "worker_executable": os.path.abspath(sys.executable),
        "encryption_key_registered": agent_service.encryption_key_registered,
        "is_wss_connected": agent_service.realtime_client.is_connected,
        "connection_state": agent_service.connection_state.value,
        "connection_error": agent_service.realtime_client.last_error,
        "printers": printers,
        "active_jobs": agent_service.active_jobs,
        "job_history": agent_service.job_history,
        "audit_log": agent_service.audit_log
    })

@dashboard_app.get("/api/shop")
async def get_shop_api():
    if not agent_service:
        raise HTTPException(status_code=503, detail="Windows station service is starting.")
    if not agent_service.config.access_token or not agent_service.config.shop_id:
        raise HTTPException(status_code=401, detail="Connect this station to a Xerox shop first.")
    try:
        return await agent_service.api_client.get_shop_details(agent_service.config.shop_id)
    except Exception as exc:
        agent_service.log_audit("SHOP_DETAILS_ERROR", str(exc), severity="ERROR")
        raise HTTPException(status_code=502, detail=f"Could not load Xerox shop details: {exc}") from exc


@dashboard_app.get("/api/queue")
async def get_queue_api():
    if not agent_service:
        raise HTTPException(status_code=503, detail="Windows station service is starting.")
    if not agent_service.config.access_token or not agent_service.config.shop_id:
        raise HTTPException(status_code=401, detail="Connect this station to a Xerox shop first.")
    try:
        return await agent_service.api_client.get_print_queue(agent_service.config.shop_id)
    except Exception as exc:
        agent_service.log_audit("QUEUE_FETCH_ERROR", str(exc), severity="ERROR")
        raise HTTPException(status_code=502, detail=f"Could not load the shop print queue: {exc}") from exc


@dashboard_app.post("/api/printers/refresh")
async def refresh_printers_api():
    if not agent_service:
        raise HTTPException(status_code=503, detail="Windows station service is starting.")
    if not agent_service.config.access_token or not agent_service.config.shop_id:
        raise HTTPException(status_code=401, detail="Connect this station to a Xerox shop first.")
    try:
        printers = agent_service.spooler.discover_local_printers()
        synced = await agent_service.api_client.sync_printers(
            agent_service.config.shop_id,
            [{**printer, "shop_id": agent_service.config.shop_id} for printer in printers],
        )
        agent_service.log_audit("PRINTERS_SYNCED", f"Synced {len(printers)} Windows printers to the cloud backend")
        return {"printers": printers, "synced_count": len(synced)}
    except Exception as exc:
        agent_service.log_audit("PRINTER_SYNC_ERROR", str(exc), severity="ERROR")
        raise HTTPException(status_code=502, detail=f"Could not sync Windows printers to the cloud: {exc}") from exc

class AutoPrintRequest(BaseModel):
    enabled: bool


@dashboard_app.post("/api/settings/auto-print")
async def set_auto_print_api(payload: AutoPrintRequest):
    if not agent_service:
        raise HTTPException(status_code=503, detail="Windows station service is starting.")
    if not agent_service.config.access_token or not agent_service.config.shop_id:
        raise HTTPException(status_code=401, detail="Connect this station to a Xerox shop first.")
    previous_setting = agent_service.config.auto_print_enabled
    agent_service.config.auto_print_enabled = payload.enabled
    try:
        agent_service.config.save()
    except OSError as exc:
        agent_service.config.auto_print_enabled = previous_setting
        agent_service.log_audit("AUTO_PRINT_UPDATE_ERROR", str(exc), severity="ERROR")
        raise HTTPException(
            status_code=500,
            detail=f"Could not save the automatic printing setting: {exc}",
        ) from exc
    agent_service.log_audit(
        "AUTO_PRINT_UPDATED",
        f"Automatic printing {'enabled' if payload.enabled else 'paused'} by shop operator",
    )
    return {"auto_print_enabled": agent_service.config.auto_print_enabled}


@dashboard_app.post("/api/realtime/reconnect")
async def reconnect_realtime_api():
    if not agent_service:
        raise HTTPException(status_code=503, detail="Windows station service is starting.")
    try:
        await agent_service.reconnect_realtime()
        return {"status": "reconnecting"}
    except Exception as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


class OperatorLoginRequest(BaseModel):
    email: str = Field(..., min_length=3, max_length=255)
    password: str = Field(..., min_length=1, max_length=1024)


class OperatorRegisterRequest(OperatorLoginRequest):
    full_name: str = Field(..., min_length=1, max_length=255)
    shop_name: str = Field(..., min_length=2, max_length=255)
    shop_address: str = Field(..., min_length=5)


class ConnectShopRequest(BaseModel):
    shop_id: str = Field(..., min_length=1, max_length=64)


@dashboard_app.post("/api/auth/login")
async def login_operator(payload: OperatorLoginRequest):
    if not agent_service:
        raise HTTPException(status_code=503, detail="Windows station service is starting.")
    try:
        shops = await agent_service.login_operator(payload.email, payload.password)
        return {"shops": shops}
    except Exception as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@dashboard_app.post("/api/auth/register")
async def register_operator(payload: OperatorRegisterRequest):
    if not agent_service:
        raise HTTPException(status_code=503, detail="Windows station service is starting.")
    try:
        shops = await agent_service.register_operator(
            payload.email,
            payload.password,
            payload.full_name,
            payload.shop_name,
            payload.shop_address,
        )
        return {"shops": shops}
    except Exception as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@dashboard_app.post("/api/auth/connect")
async def connect_operator_shop(payload: ConnectShopRequest):
    if not agent_service:
        raise HTTPException(status_code=503, detail="Windows station service is starting.")
    try:
        await agent_service.connect_operator_shop(payload.shop_id)
        return {"status": "connected"}
    except Exception as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

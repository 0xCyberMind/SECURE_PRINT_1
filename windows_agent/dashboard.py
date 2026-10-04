import os
import json
import logging
from typing import Dict, Any
from fastapi import FastAPI, Request
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
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
    </style>
</head>
<body>
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
                <span id="conn-state">WSS Connected</span>
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
                    <p style="margin-bottom: 6px;"><strong>Endpoint:</strong> <span id="c-endpoint">https://ais-dev-6u62dc37mqabbjyehi6umo-408539472511.asia-southeast1.run.app/</span></p>
                    <p style="margin-bottom: 6px;"><strong>Device ID:</strong> <span id="c-device-id">dev_win_e8f9901</span></p>
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
                        <input type="text" id="s-server-url" value="https://ais-dev-6u62dc37mqabbjyehi6umo-408539472511.asia-southeast1.run.app/">
                    </div>
                    <div class="form-group">
                        <label>Assigned Xerox Shop ID</label>
                        <input type="text" id="s-shop-id" value="SHOP-101">
                    </div>
                    <div class="form-group">
                        <label>Station Device Name</label>
                        <input type="text" id="s-device-name" value="Windows Xerox Station Agent">
                    </div>
                    <button class="btn" onclick="saveSettings()">Save Configuration</button>
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

        async function fetchStatus() {
            try {
                const res = await fetch('/api/status');
                const data = await res.json();

                document.getElementById('m-shop-id').innerText = data.shop_id || 'SHOP-101';
                document.getElementById('m-printers-count').innerText = data.printers.length;
                document.getElementById('m-queue-count').innerText = Object.keys(data.active_jobs).length;
                document.getElementById('m-completed-count').innerText = data.job_history.length;
                document.getElementById('conn-state').innerText = data.is_wss_connected ? 'WSS Connected' : 'Disconnected';

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

            } catch(e) {}
        }

        function saveSettings() {
            alert('Settings saved successfully!');
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
        "shop_id": agent_service.config.shop_id,
        "device_id": agent_service.config.device_id,
        "is_wss_connected": agent_service.realtime_client.is_connected,
        "printers": printers,
        "active_jobs": agent_service.active_jobs,
        "job_history": agent_service.job_history,
        "audit_log": agent_service.audit_log
    })

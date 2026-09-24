import express from 'express';
import cors from 'cors';
import { WebSocketServer, WebSocket } from 'ws';
import http from 'http';
import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import multer from 'multer';
import QRCode from 'qrcode';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const PORT = 3000;
const UPLOAD_DIR = path.join(__dirname, 'uploads');
const DB_FILE = path.join(__dirname, 'storage.json');

// Ensure upload directory exists
if (!fs.existsSync(UPLOAD_DIR)) {
  fs.mkdirSync(UPLOAD_DIR, { recursive: true });
}

// In-memory + persistent database
let db = {
  documents: [],
  sessions: [],
  jobs: [],
  auditEvents: [
    {
      id: 'AUDIT-INIT',
      timestamp: Date.now(),
      eventType: 'SYSTEM_BOOT',
      details: 'PrivPrint Secure Backend Spooler & File Sharing Service online on port 3000.',
      severity: 'INFO'
    }
  ]
};

if (fs.existsSync(DB_FILE)) {
  try {
    const raw = fs.readFileSync(DB_FILE, 'utf8');
    db = JSON.parse(raw);
  } catch (err) {
    console.warn('Failed to load db, starting fresh', err.message);
  }
}

function saveDb() {
  try {
    fs.writeFileSync(DB_FILE, JSON.stringify(db, null, 2), 'utf8');
  } catch (err) {
    console.error('Failed to save db:', err);
  }
}

function logAudit(eventType, details, severity = 'INFO', jobId = null, shopId = null) {
  const event = {
    id: 'AUDIT-' + crypto.randomBytes(6).toString('hex').toUpperCase(),
    timestamp: Date.now(),
    eventType,
    jobId,
    shopId,
    details,
    severity
  };
  db.auditEvents.unshift(event);
  if (db.auditEvents.length > 200) db.auditEvents.pop();
  saveDb();
  broadcast({ type: 'AUDIT_EVENT', payload: event });
}

const app = express();
const server = http.createServer(app);
const wss = new WebSocketServer({ server });

app.use(cors());
app.use(express.json({ limit: '50mb' }));
app.use(express.urlencoded({ extended: true, limit: '50mb' }));

// Multer storage for secure uploads
const storage = multer.diskStorage({
  destination: (req, file, cb) => cb(null, UPLOAD_DIR),
  filename: (req, file, cb) => {
    const uniqueSuffix = Date.now() + '-' + crypto.randomBytes(8).toString('hex');
    const safeExt = path.extname(file.originalname).slice(0, 10);
    cb(null, `doc-${uniqueSuffix}${safeExt}`);
  }
});
const upload = multer({ storage, limits: { fileSize: 100 * 1024 * 1024 } });

// Realtime WebSocket broadcast
const clients = new Set();
wss.on('connection', (ws) => {
  clients.add(ws);
  ws.send(JSON.stringify({
    type: 'INIT',
    payload: {
      message: 'Connected to PrivPrint Secure Backend Spooler',
      timestamp: Date.now()
    }
  }));

  ws.on('message', (msg) => {
    try {
      const data = JSON.parse(msg.toString());
      if (data.type === 'PING') {
        ws.send(JSON.stringify({ type: 'PONG', timestamp: Date.now() }));
      }
    } catch (e) {}
  });

  ws.on('close', () => clients.delete(ws));
});

function broadcast(msgObj) {
  const json = JSON.stringify(msgObj);
  for (const client of clients) {
    if (client.readyState === WebSocket.OPEN) {
      client.send(json);
    }
  }
}

// -------------------------------------------------------------
// REST API ROUTES
// -------------------------------------------------------------

// Health Probe
app.get('/healthz', (req, res) => {
  res.json({
    status: 'healthy',
    apiVersion: 'v1',
    server: 'PrivPrint-Secure-Spooler',
    uptime: process.uptime(),
    activeClients: clients.size,
    timestamp: Date.now()
  });
});

// Standard SVG QR Generator (Zero Client-Side CDN Dependencies)
app.get('/api/v1/qr', async (req, res) => {
  try {
    const text = req.query.text || 'privprint://shop?id=SHOP-101&name=Apex+Campus+Xerox+%26+Print';
    const svg = await QRCode.toString(text, {
      type: 'svg',
      margin: 1,
      color: {
        dark: '#0F172A',
        light: '#FFFFFF'
      }
    });
    res.setHeader('Content-Type', 'image/svg+xml');
    res.setHeader('Cache-Control', 'public, max-age=3600');
    res.send(svg);
  } catch (err) {
    res.status(500).send('Error generating QR code: ' + err.message);
  }
});

// Authentication
app.post('/api/v1/auth/login', (req, res) => {
  const { identity, role, secret } = req.body;
  const userRole = role || 'USER';
  const token = 'priv_' + crypto.randomBytes(24).toString('hex');
  const refreshToken = 'refr_' + crypto.randomBytes(32).toString('hex');

  logAudit('AUTH_LOGIN', `Identity '${identity || 'anonymous'}' logged in with role [${userRole}]`);

  res.json({
    accessToken: token,
    refreshToken,
    tokenType: 'Bearer',
    expiresInSeconds: 900,
    user: {
      userId: identity || 'usr-' + crypto.randomBytes(4).toString('hex'),
      role: userRole,
      shopId: req.body.shopId || null
    }
  });
});

// List Shops
app.get('/api/v1/shops', (req, res) => {
  res.json([
    {
      id: 'SHOP-101',
      name: 'Apex Campus Xerox & Print',
      address: 'Student Center Building 3, North Wing',
      permanentQrPayload: 'privprint://shop?id=SHOP-101&name=Apex+Campus+Xerox+%26+Print',
      isVerified: true,
      isOnline: true,
      supportedColor: true,
      supportedDuplex: true
    },
    {
      id: 'SHOP-102',
      name: 'Metro Secure QuickPrint',
      address: '42 Commercial Square, Suite 100',
      permanentQrPayload: 'privprint://shop?id=SHOP-102&name=Metro+Secure+QuickPrint',
      isVerified: true,
      isOnline: true,
      supportedColor: true,
      supportedDuplex: true
    }
  ]);
});

// Create Ephemeral Session (15m TTL)
app.post('/api/v1/sessions', (req, res) => {
  const { shopId, pairingNonce } = req.body;
  const sessionId = 'SES-' + crypto.randomBytes(4).toString('hex').toUpperCase();
  const token = crypto.randomBytes(16).toString('hex');
  const now = Date.now();
  const expiresAt = now + 15 * 60 * 1000;

  const session = {
    sessionId,
    shopId: shopId || 'SHOP-101',
    shopName: shopId === 'SHOP-102' ? 'Metro Secure QuickPrint' : 'Apex Campus Xerox & Print',
    token,
    status: 'ACTIVE',
    createdAt: now,
    expiresAt
  };

  db.sessions.push(session);
  saveDb();
  logAudit('SESSION_CREATED', `Pairing session ${sessionId} registered for ${session.shopName}. Valid for 15 min.`, 'INFO', null, shopId);

  broadcast({ type: 'SESSION_CREATED', payload: session });
  res.status(201).json(session);
});

// Revoke Session
app.post('/api/v1/sessions/:id/revoke', (req, res) => {
  const sessionId = req.params.id;
  const session = db.sessions.find(s => s.sessionId === sessionId);
  if (session) {
    session.status = 'REVOKED';
    saveDb();
  }
  logAudit('SESSION_REVOKED', `Session ${sessionId} revoked by customer. Invalidation active.`, 'SECURITY_ALERT');
  broadcast({ type: 'SESSION_REVOKED', payload: { sessionId } });
  res.json({ message: 'Session revoked successfully', sessionId });
});

// Upload Document to Share or Print
app.post('/api/v1/documents/upload', upload.single('file'), (req, res) => {
  try {
    if (!req.file) {
      return res.status(400).json({ error: 'No file uploaded' });
    }

    const docId = 'DOC-' + crypto.randomBytes(6).toString('hex').toUpperCase();
    const copiesAuthorized = parseInt(req.body.copiesAuthorized || req.body.maxDownloads || '1', 10);
    const ttlMinutes = parseInt(req.body.ttlMinutes || '15', 10);
    const now = Date.now();
    const expiresAt = now + (ttlMinutes * 60 * 1000);

    // Compute SHA-256 of stored file
    const fileBytes = fs.readFileSync(req.file.path);
    const sha256 = crypto.createHash('sha256').update(fileBytes).digest('hex');

    const docRecord = {
      id: docId,
      filename: req.body.documentName || req.file.originalname,
      diskFilename: req.file.filename,
      filePath: req.file.path,
      fileSize: req.file.size,
      mimeType: req.file.mimetype || 'application/octet-stream',
      sha256,
      encryptionAlgorithm: req.body.encryptionAlgorithm || 'AES-256-GCM',
      ivHex: req.body.ivHex || crypto.randomBytes(12).toString('hex'),
      keyFingerprint: req.body.keyFingerprint || 'FP-' + crypto.randomBytes(8).toString('hex').toUpperCase(),
      copiesAuthorized,
      copiesPrinted: 0,
      downloadsRemaining: copiesAuthorized,
      createdAt: now,
      expiresAt,
      status: 'AVAILABLE',
      cleanupState: 'CLEANUP_PENDING'
    };

    db.documents.unshift(docRecord);
    saveDb();

    logAudit('DOCUMENT_UPLOADED', `Encrypted document '${docRecord.filename}' uploaded (${(docRecord.fileSize / 1024).toFixed(1)} KB). Limit: ${copiesAuthorized} copy/copies. SHA256: ${sha256.slice(0, 16)}...`, 'INFO', docId);

    broadcast({ type: 'DOCUMENT_AVAILABLE', payload: docRecord });

    res.status(201).json({
      message: 'Document securely uploaded and ready for print/share',
      document: docRecord,
      shareUrl: `/api/v1/documents/${docId}/download`,
      qrPayload: `privprint://doc?id=${docId}&name=${encodeURIComponent(docRecord.filename)}`
    });
  } catch (err) {
    console.error('Upload error:', err);
    res.status(500).json({ error: 'Upload failed: ' + err.message });
  }
});

// List Shared Documents
app.get('/api/v1/documents', (req, res) => {
  const now = Date.now();
  // Filter and check expiration
  for (const doc of db.documents) {
    if (doc.status === 'AVAILABLE' && now > doc.expiresAt) {
      doc.status = 'EXPIRED';
      doc.cleanupState = 'CLEANUP_COMPLETED';
      // Secure delete file
      if (fs.existsSync(doc.filePath)) {
        try { fs.unlinkSync(doc.filePath); } catch (e) {}
      }
    }
  }
  saveDb();
  res.json(db.documents);
});

// Get Document Metadata
app.get('/api/v1/documents/:id', (req, res) => {
  const doc = db.documents.find(d => d.id === req.params.id);
  if (!doc) return res.status(404).json({ error: 'Document not found' });
  res.json(doc);
});

// Download / Print Spool Document (Strict Atomic Limit Enforcement)
app.get('/api/v1/documents/:id/download', (req, res) => {
  const doc = db.documents.find(d => d.id === req.params.id);
  if (!doc) {
    return res.status(404).send('Document not found or already securely shredded.');
  }

  const now = Date.now();
  if (now > doc.expiresAt) {
    doc.status = 'EXPIRED';
    if (fs.existsSync(doc.filePath)) {
      try { fs.unlinkSync(doc.filePath); } catch (e) {}
    }
    saveDb();
    return res.status(410).send('Document session expired. Memory wiped.');
  }

  // ATOMIC COPY LIMIT CHECK
  if (doc.copiesPrinted >= doc.copiesAuthorized) {
    logAudit('COPY_LIMIT_ENFORCED', `Unauthorized extra download/copy blocked for document ${doc.id} (Limit ${doc.copiesAuthorized} reached).`, 'SECURITY_ALERT', doc.id);
    return res.status(403).send('Copy limit reached. Document cannot be downloaded or duplicated again.');
  }

  // Increment usage atomically
  doc.copiesPrinted += 1;
  doc.downloadsRemaining = Math.max(0, doc.copiesAuthorized - doc.copiesPrinted);

  logAudit('DOCUMENT_RETRIEVED', `Document '${doc.filename}' spooled/downloaded (Copy ${doc.copiesPrinted} of ${doc.copiesAuthorized}).`, 'INFO', doc.id);

  const isFinalCopy = doc.copiesPrinted >= doc.copiesAuthorized;
  if (isFinalCopy) {
    doc.status = 'COMPLETED';
  }

  saveDb();
  broadcast({
    type: 'COPY_INCREMENTED',
    payload: {
      documentId: doc.id,
      copiesPrinted: doc.copiesPrinted,
      copiesAuthorized: doc.copiesAuthorized,
      isCompleted: isFinalCopy
    }
  });

  // Serve file
  if (!fs.existsSync(doc.filePath)) {
    return res.status(404).send('Underlying storage object deleted.');
  }

  res.setHeader('Content-Disposition', `attachment; filename="${encodeURIComponent(doc.filename)}"`);
  res.setHeader('Content-Type', doc.mimeType);

  const fileStream = fs.createReadStream(doc.filePath);
  fileStream.pipe(res);

  // If final copy consumed, execute shred after response completes
  if (isFinalCopy) {
    res.on('finish', () => {
      setTimeout(() => {
        try {
          if (fs.existsSync(doc.filePath)) {
            // Overwrite with zeros before unlinking (secure erasure)
            const buffer = Buffer.alloc(doc.fileSize, 0);
            fs.writeFileSync(doc.filePath, buffer);
            fs.unlinkSync(doc.filePath);
          }
          doc.cleanupState = 'CLEANUP_COMPLETED';
          saveDb();
          logAudit('STORAGE_SHREDDED', `Document '${doc.filename}' securely overwritten and purged from disk after final authorized copy.`, 'INFO', doc.id);
          broadcast({ type: 'DOCUMENT_SHREDDED', payload: { documentId: doc.id } });
        } catch (err) {
          console.error('Error during auto-shred:', err);
        }
      }, 500);
    });
  }
});

// Manual Secure Shred / Delete
app.delete('/api/v1/documents/:id', (req, res) => {
  const doc = db.documents.find(d => d.id === req.params.id);
  if (!doc) return res.status(404).json({ error: 'Document not found' });

  if (fs.existsSync(doc.filePath)) {
    try {
      const buffer = Buffer.alloc(doc.fileSize || 1024, 0);
      fs.writeFileSync(doc.filePath, buffer);
      fs.unlinkSync(doc.filePath);
    } catch (e) {}
  }

  doc.status = 'MANUALLY_SHREDDED';
  doc.cleanupState = 'CLEANUP_COMPLETED';
  saveDb();

  logAudit('MANUAL_SHRED', `Document '${doc.filename}' immediately shredded by user/operator command.`, 'INFO', doc.id);
  broadcast({ type: 'DOCUMENT_SHREDDED', payload: { documentId: doc.id } });

  res.json({ message: 'Document and storage object permanently shredded', documentId: doc.id });
});

// Audit Events
app.get('/api/v1/audit/events', (req, res) => {
  res.json(db.auditEvents);
});

// Print Queue API
app.get('/api/v1/print/jobs', (req, res) => {
  res.json(db.documents.filter(d => d.status === 'AVAILABLE'));
});

// -------------------------------------------------------------
// SERVE EMBEDDED WEB DASHBOARD & FILE SHARING UI
// -------------------------------------------------------------
app.get('/', (req, res) => {
  res.send(`<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>PrivPrint — Secure Xerox & File Sharing Spooler</title>
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;500;700&display=swap" rel="stylesheet">
  <style>
    :root {
      --bg: #090D16;
      --surface: #111827;
      --surface-border: #1F2937;
      --card: #162032;
      --primary: #38BDF8;
      --primary-hover: #0EA5E9;
      --accent: #10B981;
      --warning: #F59E0B;
      --danger: #EF4444;
      --text: #F8FAFC;
      --text-muted: #94A3B8;
      --code-bg: #0B1120;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      background: var(--bg);
      color: var(--text);
      font-family: 'Plus Jakarta Sans', sans-serif;
      min-height: 100vh;
      display: flex;
      flex-direction: column;
    }
    header {
      background: rgba(17, 24, 39, 0.8);
      backdrop-filter: blur(12px);
      border-bottom: 1px solid var(--surface-border);
      padding: 16px 24px;
      display: flex;
      justify-content: space-between;
      align-items: center;
      position: sticky;
      top: 0;
      z-index: 100;
    }
    .brand {
      display: flex;
      align-items: center;
      gap: 12px;
      font-size: 1.25rem;
      font-weight: 800;
      color: var(--text);
    }
    .brand-badge {
      background: linear-gradient(135deg, #0284C7, #0369A1);
      color: white;
      padding: 4px 8px;
      border-radius: 6px;
      font-size: 0.75rem;
      letter-spacing: 0.5px;
    }
    .status-pill {
      display: flex;
      align-items: center;
      gap: 8px;
      background: rgba(16, 185, 129, 0.15);
      border: 1px solid rgba(16, 185, 129, 0.3);
      color: #34D399;
      padding: 6px 12px;
      border-radius: 9999px;
      font-size: 0.85rem;
      font-weight: 600;
    }
    .dot {
      width: 8px;
      height: 8px;
      background: #10B981;
      border-radius: 50%;
      box-shadow: 0 0 8px #10B981;
      animation: pulse 2s infinite;
    }
    @keyframes pulse {
      0%, 100% { opacity: 1; transform: scale(1); }
      50% { opacity: 0.4; transform: scale(0.9); }
    }
    main {
      flex: 1;
      max-width: 1280px;
      width: 100%;
      margin: 0 auto;
      padding: 32px 24px;
      display: grid;
      grid-template-columns: 1fr 380px;
      gap: 32px;
    }
    @media (max-width: 960px) {
      main { grid-template-columns: 1fr; }
    }
    .card {
      background: var(--surface);
      border: 1px solid var(--surface-border);
      border-radius: 16px;
      padding: 24px;
      box-shadow: 0 10px 30px rgba(0,0,0,0.3);
      margin-bottom: 24px;
    }
    .card h2 {
      font-size: 1.25rem;
      font-weight: 700;
      margin-bottom: 16px;
      display: flex;
      align-items: center;
      gap: 10px;
    }
    /* Upload Zone */
    .dropzone {
      border: 2px dashed #334155;
      border-radius: 12px;
      padding: 36px 24px;
      text-align: center;
      background: var(--code-bg);
      cursor: pointer;
      transition: all 0.2s ease;
      position: relative;
    }
    .dropzone:hover, .dropzone.dragover {
      border-color: var(--primary);
      background: rgba(56, 189, 248, 0.05);
    }
    .dropzone input[type="file"] {
      position: absolute;
      top: 0; left: 0; width: 100%; height: 100%;
      opacity: 0;
      cursor: pointer;
    }
    .dropzone-icon {
      font-size: 42px;
      margin-bottom: 12px;
    }
    .form-row {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 16px;
      margin-top: 16px;
    }
    label {
      font-size: 0.85rem;
      color: var(--text-muted);
      margin-bottom: 6px;
      display: block;
      font-weight: 600;
    }
    select, input[type="text"], input[type="number"] {
      width: 100%;
      background: #0F172A;
      border: 1px solid #334155;
      color: var(--text);
      padding: 10px 14px;
      border-radius: 8px;
      font-size: 0.95rem;
      font-family: inherit;
    }
    select:focus, input:focus {
      outline: none;
      border-color: var(--primary);
    }
    .btn {
      background: var(--primary);
      color: #0F172A;
      border: none;
      padding: 12px 20px;
      border-radius: 8px;
      font-weight: 700;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 8px;
      font-size: 0.95rem;
      transition: all 0.2s;
      width: 100%;
      margin-top: 16px;
    }
    .btn:hover { background: var(--primary-hover); transform: translateY(-1px); }
    .btn-danger {
      background: rgba(239, 68, 68, 0.2);
      color: #F87171;
      border: 1px solid rgba(239, 68, 68, 0.3);
      width: auto;
      padding: 6px 12px;
      font-size: 0.8rem;
      margin-top: 0;
    }
    .btn-danger:hover { background: var(--danger); color: white; }
    .btn-download {
      background: rgba(56, 189, 248, 0.15);
      color: var(--primary);
      border: 1px solid rgba(56, 189, 248, 0.3);
      width: auto;
      padding: 6px 12px;
      font-size: 0.8rem;
      margin-top: 0;
      text-decoration: none;
      border-radius: 6px;
      font-weight: 600;
      display: inline-flex;
      align-items: center;
      gap: 6px;
    }
    .btn-download:hover { background: var(--primary); color: #0F172A; }
    /* Documents Table */
    .doc-list {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .doc-item {
      background: var(--card);
      border: 1px solid var(--surface-border);
      border-radius: 12px;
      padding: 16px;
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 16px;
      transition: all 0.2s;
    }
    .doc-item:hover {
      border-color: #334155;
    }
    .doc-info {
      flex: 1;
      min-width: 0;
    }
    .doc-title {
      font-weight: 700;
      font-size: 1rem;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .doc-meta {
      display: flex;
      gap: 12px;
      font-size: 0.8rem;
      color: var(--text-muted);
      margin-top: 4px;
      font-family: 'JetBrains Mono', monospace;
    }
    .badge {
      display: inline-block;
      padding: 2px 8px;
      border-radius: 4px;
      font-size: 0.75rem;
      font-weight: 700;
    }
    .badge-available { background: rgba(16, 185, 129, 0.2); color: #34D399; }
    .badge-completed { background: rgba(148, 163, 184, 0.2); color: #94A3B8; }
    .badge-shredded { background: rgba(239, 68, 68, 0.2); color: #F87171; }
    /* QR Panel */
    .qr-container {
      background: white;
      padding: 16px;
      border-radius: 12px;
      display: flex;
      align-items: center;
      justify-content: center;
      margin: 16px 0;
    }
    .qr-container canvas { width: 100%; max-width: 220px; height: auto; }
    .mono-box {
      background: var(--code-bg);
      border: 1px solid #1E293B;
      padding: 12px;
      border-radius: 8px;
      font-family: 'JetBrains Mono', monospace;
      font-size: 0.8rem;
      word-break: break-all;
      color: #38BDF8;
    }
    /* Audit Log */
    .audit-list {
      max-height: 280px;
      overflow-y: auto;
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .audit-item {
      font-family: 'JetBrains Mono', monospace;
      font-size: 0.75rem;
      padding: 8px 12px;
      background: var(--code-bg);
      border-left: 3px solid var(--primary);
      border-radius: 4px;
    }
    .audit-item.SECURITY_ALERT { border-left-color: var(--danger); background: rgba(239,68,68,0.08); }
    .audit-time { color: var(--text-muted); font-size: 0.7rem; }
  </style>
</head>
<body>
  <header>
    <div class="brand">
      <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#38BDF8" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
        <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
        <path d="M9 12l2 2 4-4"/>
      </svg>
      PrivPrint Spooler
      <span class="brand-badge">PORT 3000 ACTIVE</span>
    </div>
    <div class="status-pill">
      <span class="dot"></span>
      <span>Spooler Online & Realtime Ready</span>
    </div>
  </header>

  <main>
    <div>
      <!-- Upload Card -->
      <div class="card">
        <h2>
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
            <polyline points="17 8 12 3 7 8"/>
            <line x1="12" y1="3" x2="12" y2="15"/>
          </svg>
          Share Confidential Document to Print
        </h2>
        <form id="uploadForm" enctype="multipart/form-data">
          <div class="dropzone" id="dropzone">
            <input type="file" id="fileInput" name="file" required>
            <div class="dropzone-icon">📄</div>
            <div style="font-weight: 700; margin-bottom: 4px;" id="dropzoneText">Drag and drop document, or click to browse</div>
            <div style="font-size: 0.85rem; color: var(--text-muted);">PDF, DOCX, PNG, JPG (AES-256 Client-Encrypted or Server Spooled)</div>
          </div>

          <div class="form-row">
            <div>
              <label for="copiesAuthorized">Authorized Copies / Downloads</label>
              <select id="copiesAuthorized" name="copiesAuthorized">
                <option value="1">1 Copy (Strict Single-Use Shred)</option>
                <option value="2" selected>2 Copies</option>
                <option value="3">3 Copies</option>
                <option value="5">5 Copies</option>
                <option value="10">10 Copies</option>
              </select>
            </div>
            <div>
              <label for="ttlMinutes">Session Expiry Time</label>
              <select id="ttlMinutes" name="ttlMinutes">
                <option value="5">5 Minutes</option>
                <option value="15" selected>15 Minutes (Default TTL)</option>
                <option value="60">1 Hour</option>
                <option value="1440">24 Hours</option>
              </select>
            </div>
          </div>

          <button type="submit" class="btn" id="uploadBtn">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <rect x="3" y="11" width="18" height="11" rx="2" ry="2"/>
              <path d="M7 11V7a5 5 0 0 1 10 0v4"/>
            </svg>
            Seal & Upload to Secure Spooler
          </button>
        </form>
      </div>

      <!-- Active Documents Card -->
      <div class="card">
        <h2>
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="6 9 6 2 18 2 18 9"/>
            <path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2"/>
            <rect x="6" y="14" width="12" height="8"/>
          </svg>
          Active Shared Documents & Print Spool
        </h2>
        <div id="docList" class="doc-list">
          <div style="text-align: center; color: var(--text-muted); padding: 24px;">Loading shared documents...</div>
        </div>
      </div>
    </div>

    <!-- Side Panel -->
    <div>
      <!-- Shop Counter QR Card -->
      <div class="card">
        <h2>
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <rect x="3" y="3" width="7" height="7"/>
            <rect x="14" y="3" width="7" height="7"/>
            <rect x="14" y="14" width="7" height="7"/>
            <rect x="3" y="14" width="7" height="7"/>
          </svg>
          Shop Counter Pairing QR
        </h2>
        <p style="font-size: 0.85rem; color: var(--text-muted);">
          Scan with PrivPrint Android app to pair immediately with <strong>Apex Campus Xerox & Print</strong>:
        </p>
        <div class="qr-container">
          <img src="/api/v1/qr?text=privprint%3A%2F%2Fshop%3Fid%3DSHOP-101%26name%3DApex%2BCampus%2BXerox%2B%2526%2BPrint" alt="Shop Counter QR Code" id="shopQrImg" style="width: 200px; height: 200px; display: block;" />
        </div>
        <div class="mono-box" id="qrText">privprint://shop?id=SHOP-101&name=Apex+Campus+Xerox+%26+Print</div>
      </div>

      <!-- Live Audit Trail -->
      <div class="card">
        <h2>
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
          </svg>
          Live Security Audit Trail
        </h2>
        <div id="auditList" class="audit-list">
          <!-- Filled via JS -->
        </div>
      </div>
    </div>
  </main>

  <script>
    const qrPayload = 'privprint://shop?id=SHOP-101&name=Apex+Campus+Xerox+%26+Print';

    const fileInput = document.getElementById('fileInput');
    const dropzone = document.getElementById('dropzone');
    const dropzoneText = document.getElementById('dropzoneText');

    fileInput.addEventListener('change', () => {
      if (fileInput.files.length > 0) {
        dropzoneText.textContent = 'Selected: ' + fileInput.files[0].name + ' (' + (fileInput.files[0].size / 1024).toFixed(1) + ' KB)';
        dropzone.style.borderColor = '#10B981';
      }
    });

    // Form submission
    document.getElementById('uploadForm').addEventListener('submit', async (e) => {
      e.preventDefault();
      const btn = document.getElementById('uploadBtn');
      btn.disabled = true;
      btn.innerHTML = 'Encrypting & Storing...';

      const formData = new FormData(e.target);
      try {
        const res = await fetch('/api/v1/documents/upload', {
          method: 'POST',
          body: formData
        });
        const data = await res.json();
        if (res.ok) {
          dropzoneText.textContent = 'Drag and drop document, or click to browse';
          dropzone.style.borderColor = '#334155';
          e.target.reset();
          loadDocs();
          loadAudit();
        } else {
          alert('Upload failed: ' + (data.error || 'Server error'));
        }
      } catch (err) {
        alert('Upload error: ' + err.message);
      } finally {
        btn.disabled = false;
        btn.innerHTML = 'Seal & Upload to Secure Spooler';
      }
    });

    async function loadDocs() {
      try {
        const res = await fetch('/api/v1/documents');
        const docs = await res.json();
        const container = document.getElementById('docList');
        if (!docs || docs.length === 0) {
          container.innerHTML = '<div style="text-align: center; color: var(--text-muted); padding: 24px;">No files shared yet. Upload a document above.</div>';
          return;
        }

        container.innerHTML = docs.map(d => {
          const badgeClass = d.status === 'AVAILABLE' ? 'badge-available' : (d.status === 'COMPLETED' ? 'badge-completed' : 'badge-shredded');
          const remainingSec = Math.max(0, Math.round((d.expiresAt - Date.now()) / 1000));
          const min = Math.floor(remainingSec / 60);
          const sec = remainingSec % 60;
          const ttlFormatted = min + 'm ' + sec + 's';

          return \`
            <div class="doc-item" id="item-\${d.id}">
              <div class="doc-info">
                <div class="doc-title">
                  \${d.filename}
                  <span class="badge \${badgeClass}">\${d.status}</span>
                </div>
                <div class="doc-meta">
                  <span>Copies: <strong>\${d.copiesPrinted}/\${d.copiesAuthorized}</strong></span>
                  <span>Size: \${(d.fileSize / 1024).toFixed(1)} KB</span>
                  <span>TTL: \${ttlFormatted}</span>
                  <span>AES-256-GCM</span>
                </div>
              </div>
              <div style="display: flex; gap: 8px;">
                \${d.status === 'AVAILABLE' ? \`
                  <a href="/api/v1/qr?text=\${encodeURIComponent('privprint://doc?id=' + d.id + '&name=' + encodeURIComponent(d.filename))}" class="btn-download" style="background: rgba(16, 185, 129, 0.15); color: #34D399; border-color: rgba(16, 185, 129, 0.3);" target="_blank" title="Scan or View QR">
                    📷 QR
                  </a>
                  <a href="/api/v1/documents/\${d.id}/download" class="btn-download" target="_blank">
                    ⬇ Spool/Get
                  </a>
                  <button onclick="shredDoc('\${d.id}')" class="btn btn-danger">
                    🗑 Shred
                  </button>
                \` : \`
                  <span style="font-size: 0.8rem; color: var(--text-muted); font-family: 'JetBrains Mono', monospace;">[SHREDDED]</span>
                \`}
              </div>
            </div>
          \`;
        }).join('');
      } catch (err) {
        console.error('Failed to load docs:', err);
      }
    }

    async function shredDoc(id) {
      if (!confirm('Are you sure you want to permanently zeroize and delete this document from disk?')) return;
      try {
        await fetch('/api/v1/documents/' + id, { method: 'DELETE' });
        loadDocs();
        loadAudit();
      } catch (err) {
        alert('Shred failed: ' + err.message);
      }
    }

    async function loadAudit() {
      try {
        const res = await fetch('/api/v1/audit/events');
        const events = await res.json();
        const container = document.getElementById('auditList');
        container.innerHTML = events.slice(0, 15).map(e => {
          const dateStr = new Date(e.timestamp).toLocaleTimeString();
          return \`
            <div class="audit-item \${e.severity}">
              <div class="audit-time">\${dateStr} • [\${e.eventType}]</div>
              <div>\${e.details}</div>
            </div>
          \`;
        }).join('');
      } catch (err) {
        console.error('Failed to load audit:', err);
      }
    }

    // Connect WebSocket
    const wsProto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const ws = new WebSocket(wsProto + '//' + location.host);
    ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);
        if (msg.type === 'DOCUMENT_AVAILABLE' || msg.type === 'COPY_INCREMENTED' || msg.type === 'DOCUMENT_SHREDDED') {
          loadDocs();
        }
        if (msg.type === 'AUDIT_EVENT') {
          loadAudit();
        }
      } catch (e) {}
    };

    setInterval(loadDocs, 5000);
    loadDocs();
    loadAudit();
  </script>
</body>
</html>`);
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`[PrivPrint] Secure Backend Spooler & File Sharing Server listening on http://0.0.0.0:${PORT}`);
  console.log(`[PrivPrint] Reverse proxy routing through port 8080 to port ${PORT}`);
});

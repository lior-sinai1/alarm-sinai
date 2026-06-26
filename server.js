'use strict';

const express = require('express');
const ModbusRTU = require('modbus-serial');
const admin = require('firebase-admin');
const fs = require('fs');
const path = require('path');

const app = express();
app.use(express.json());

// ─── Config ───────────────────────────────────────────────────────────────────
const PLC_HOST            = '192.168.1.50';
const PLC_PORT            = 502;
const PLC_ID              = 1;
const PORT                = 3000;
const TOKENS_FILE         = path.join(__dirname, 'fcm-tokens.json');
const SERVICE_ACCOUNT_FILE = path.join(__dirname, 'firebase-service-account.json');

// ─── Bypass map: sensor app-key → bypass coil (M50–M75) ──────────────────────
const BYPASS_MAP = {
  182: 50, 201: 51, 202: 52, 203: 53, 204: 54,
  205: 55, 206: 56, 207: 57, 208: 58, 209: 59,
  210: 60, 211: 61, 212: 62, 213: 63, 214: 64,
  215: 65, 216: 66, 217: 67, 218: 68, 219: 69,
  222: 75,
};

// ─── Real-time sensor coil map: app-key → actual PLC coil (M183 / M301-M322) ─
// PLC stores LDN %Ix.x → %Mxxx (1 = sensor OPEN/triggered, real-time, non-latching)
const SENSOR_COIL_MAP = {
  182: 183,                                                    // M183 = NOT I0.1 (entry door)
  201: 301, 202: 302, 203: 303, 204: 304, 205: 305,           // M301-M305
  206: 306, 207: 307, 208: 308, 209: 309, 210: 310,           // M306-M310
  211: 311, 212: 312, 213: 313, 214: 314, 215: 315,           // M311-M315
  216: 316, 217: 317, 218: 318, 219: 319, 222: 322,           // M316-M322
};

// ─── Sensor map: Modbus coil address → display name ───────────────────────────
const SENSOR_MAP = {
  182: 'דלת כניסה',
  201: 'חלון מטבח',
  202: 'דלת מטבח',
  203: 'ויטרינה סלון',
  204: 'הגנת צופר',
  205: 'חלון הורים מזרחי ימין',
  206: 'חלון הורים מזרחי שמאלי',
  207: 'חלון הורים צפוני חזית',
  208: 'סלון',
  209: 'פרגולה',
  210: 'חלון רחצה הורים',
  211: 'חדר הורים',
  212: 'ממ"ד',
  213: "רחבת חדרים קומה א'",
  214: "דלת מרפסת קומה א'",
  215: 'חדר נוף חלון מערבי',
  216: 'חדר נוף ויטרינה',
  217: 'חדר נוף חלון מזרחי',
  218: 'חלון חדר כביסה',
  219: "מרפסת קומה א'",
  222: 'חלון סלון',
};

// ─── Firebase init ────────────────────────────────────────────────────────────
let fcmEnabled = false;
if (fs.existsSync(SERVICE_ACCOUNT_FILE)) {
  try {
    const serviceAccount = require(SERVICE_ACCOUNT_FILE);
    admin.initializeApp({ credential: admin.credential.cert(serviceAccount) });
    fcmEnabled = true;
    console.log('Firebase initialized');
  } catch (e) {
    console.warn('Firebase init failed:', e.message);
  }
} else {
  console.warn('firebase-service-account.json not found — push notifications disabled');
}

// ─── FCM token persistence ────────────────────────────────────────────────────
function loadTokens() {
  try {
    if (fs.existsSync(TOKENS_FILE))
      return JSON.parse(fs.readFileSync(TOKENS_FILE, 'utf8'));
  } catch (_) {}
  return [];
}

function saveTokens(tokens) {
  fs.writeFileSync(TOKENS_FILE, JSON.stringify(tokens), 'utf8');
}

let fcmTokens = loadTokens();

// ─── Modbus client ────────────────────────────────────────────────────────────
const client = new ModbusRTU();
let connected = false;
let reconnecting = false;

// Prevents simultaneous poll + write on the same TCP connection
let modbusBusy = false;
async function withModbus(fn) {
  while (modbusBusy) await new Promise(r => setTimeout(r, 20));
  modbusBusy = true;
  try { return await fn(); } finally { modbusBusy = false; }
}

async function connectModbus() {
  if (reconnecting) return;
  reconnecting = true;
  try {
    if (client.isOpen) client.close();
    await client.connectTCP(PLC_HOST, { port: PLC_PORT });
    client.setID(PLC_ID);
    connected = true;
    console.log('Modbus connected to', PLC_HOST);
  } catch (err) {
    connected = false;
    console.error('Modbus connect failed:', err.message, '— retry in 5s');
    setTimeout(connectModbus, 5000);
  } finally {
    reconnecting = false;
  }
}

client.on('close', () => {
  if (connected) {
    connected = false;
    cachedStatus = { ...cachedStatus, connected: false };
    console.warn('Modbus disconnected — reconnecting...');
    setTimeout(connectModbus, 2000);
  }
});

// ─── Status cache & polling ───────────────────────────────────────────────────
let cachedStatus = { ok: false, connected: false, m175: 0, m19: 0, mw1: 0, sensors: {}, bypasses: {} };
let prevM175 = null;
let prevM19  = null;

async function readStatus() {
  const armed    = await client.readCoils(175, 1);
  const alarm    = await client.readCoils(19, 1);
  const timerReg = await client.readHoldingRegisters(1, 2);
  // Real-time sensor state: M183 (entry door) and M301-M322
  const entryDoor   = await client.readCoils(183, 1);
  const sensorBlock = await client.readCoils(301, 22);

  const sensors = {};
  for (const addr of Object.keys(SENSOR_MAP)) {
    const plcCoil = SENSOR_COIL_MAP[Number(addr)];
    if (plcCoil === 183) {
      sensors[addr] = entryDoor.data[0] ? 1 : 0;
    } else {
      sensors[addr] = sensorBlock.data[plcCoil - 301] ? 1 : 0;
    }
  }

  // Bypass coils M50–M75 (read 26 coils; M70–M74 unused)
  const bypassBlock = await client.readCoils(50, 26);
  const bypasses = {};
  for (const [sensorAddr, bypassCoil] of Object.entries(BYPASS_MAP)) {
    bypasses[sensorAddr] = bypassBlock.data[bypassCoil - 50] ? 1 : 0;
  }

  return {
    ok: true,
    connected: true,
    m175: armed.data[0] ? 1 : 0,
    m19:  alarm.data[0] ? 1 : 0,
    mw1:  timerReg.data[0],
    mw2:  timerReg.data[1],
    sensors,
    bypasses,
  };
}

async function pollOnce() {
  if (!connected) return;
  try {
    const status = await withModbus(() => readStatus());
    await detectChanges(status);
    cachedStatus = status;
  } catch (err) {
    console.error('Poll error:', err.message);
    connected = false;
    cachedStatus = { ...cachedStatus, connected: false };
    setTimeout(connectModbus, 2000);
  }
}

function startPolling() {
  setInterval(pollOnce, 1000);
}

// ─── Push notifications ───────────────────────────────────────────────────────
async function detectChanges(status) {
  if (prevM19 === 0 && status.m19 === 1)
    await sendPush('אזעקה!', 'פריצה — מערכת האזעקה הופעלה!', 'alarm');

  if (prevM175 === 0 && status.m175 === 1)
    await sendPush('מערכת דרוכה', 'המערכת נדרכה בהצלחה', 'arm');

  if (prevM175 === 1 && status.m175 === 0)
    await sendPush('מערכת מנוטרלת', 'המערכת נוטרלה', 'disarm');

  prevM175 = status.m175;
  prevM19  = status.m19;
}

async function sendPush(title, body, type) {
  if (!fcmEnabled || fcmTokens.length === 0) return;
  const invalidTokens = [];
  for (const token of [...fcmTokens]) {
    try {
      await admin.messaging().send({
        token,
        data: { title, body, type },
        android: { priority: 'high' },
      });
    } catch (err) {
      console.error('FCM error [%s]:', token.slice(-8), err.message);
      if (err.code === 'messaging/registration-token-not-registered')
        invalidTokens.push(token);
    }
  }
  if (invalidTokens.length > 0) {
    fcmTokens = fcmTokens.filter(t => !invalidTokens.includes(t));
    saveTokens(fcmTokens);
  }
}

// ─── Modbus pulse (300 ms ON → OFF) ──────────────────────────────────────────
async function pulse(coil) {
  await withModbus(async () => {
    await client.writeCoil(coil, true);
    await new Promise(r => setTimeout(r, 300));
    await client.writeCoil(coil, false);
  });
}

// ─── HTTP routes ──────────────────────────────────────────────────────────────
app.get('/health', (_req, res) => {
  res.json({ ok: true, connected });
});

app.get('/status', (_req, res) => {
  res.json(cachedStatus);
});

// POST /arm  { zone: 9 | 10 | 11 | 12 }
app.post('/arm', async (req, res) => {
  const zone = Number(req.body?.zone);
  if (![9, 10, 11, 12].includes(zone))
    return res.status(400).json({ ok: false, error: 'zone must be 9/10/11/12' });
  if (!connected)
    return res.status(503).json({ ok: false, error: 'PLC not connected' });
  try {
    await pulse(zone);
    res.json({ ok: true });
  } catch (err) {
    res.status(500).json({ ok: false, error: err.message });
  }
});

// POST /disarm  — pulse M13
app.post('/disarm', async (_req, res) => {
  if (!connected)
    return res.status(503).json({ ok: false, error: 'PLC not connected' });
  try {
    await pulse(13);
    res.json({ ok: true });
  } catch (err) {
    res.status(500).json({ ok: false, error: err.message });
  }
});

// POST /write-coil  { coil: 50, value: true }
app.post('/write-coil', async (req, res) => {
  const coil  = Number(req.body?.coil);
  const value = Boolean(req.body?.value);
  const validBypassCoils = new Set(Object.values(BYPASS_MAP));
  if (!validBypassCoils.has(coil))
    return res.status(400).json({ ok: false, error: 'coil not in allowed bypass range' });
  if (!connected)
    return res.status(503).json({ ok: false, error: 'PLC not connected' });
  try {
    await withModbus(() => client.writeCoil(coil, value));
    res.json({ ok: true });
  } catch (err) {
    res.status(500).json({ ok: false, error: err.message });
  }
});

// POST /register-token  { token: "..." }
app.post('/register-token', (req, res) => {
  const { token } = req.body ?? {};
  if (!token)
    return res.status(400).json({ ok: false, error: 'Missing token' });
  if (!fcmTokens.includes(token)) {
    fcmTokens.push(token);
    saveTokens(fcmTokens);
  }
  res.json({ ok: true });
});

// ─── Start ────────────────────────────────────────────────────────────────────
app.listen(PORT, () => {
  console.log(`Alarm server listening on port ${PORT}`);
  connectModbus().then(startPolling);
});

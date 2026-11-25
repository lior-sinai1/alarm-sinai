'use strict';

// ─── Cameras: bridge between the app's "מצלמות" tab and Frigate ──────────────
//
//   app ──X-API-Key──> server.js /cameras/* ──(login cookie)──> Frigate add-on :8971
//
// Frigate (Home Assistant add-on on this host) does the video work: recording,
// object detection, face recognition, zones. This module keeps the settings the
// app edits, writes them into Frigate's config, proxies snapshots / live video /
// clips, watches Frigate's events and raises alerts (push and/or siren coil M20).
//
// Settings live on the encrypted disk (CAMERAS_DATA_DIR, default /srv/secure/alarm,
// created by deploy/cameras/00-prepare-secure-disk.sh). Camera passwords never
// leave this server; face images stay inside Frigate and are never sent to FCM —
// the push carries only an event id, and the app fetches the picture through here.
//
// Env:  FRIGATE_URL (default https://127.0.0.1:8971), FRIGATE_USER, FRIGATE_PASSWORD,
//       CAMERAS_DATA_DIR, FRIGATE_DETECTOR_DEVICE (GPU | CPU, default GPU)

const fs     = require('fs');
const path   = require('path');
const os     = require('os');
const net    = require('net');
const dgram  = require('dgram');
const crypto = require('crypto');
const http   = require('http');
const https  = require('https');
const express = require('express');

const DATA_DIR         = process.env.CAMERAS_DATA_DIR || '/srv/secure/alarm';
const SETTINGS_FILE    = path.join(DATA_DIR, 'cameras.json');
const FRIGATE_URL      = new URL(process.env.FRIGATE_URL || 'https://127.0.0.1:8971');
const FRIGATE_USER     = process.env.FRIGATE_USER || 'alarm';
const FRIGATE_PASSWORD = process.env.FRIGATE_PASSWORD || '';
const DETECTOR_DEVICE  = process.env.FRIGATE_DETECTOR_DEVICE === 'CPU' ? 'CPU' : 'GPU';

const SIREN_COIL        = 20;      // %M20 — camera siren request, held until stop / disarm
const EVENT_POLL_MS     = 2000;
const FACE_WAIT_MS      = 4000;    // time face recognition gets before a person counts as a stranger
const ALERT_COOLDOWN_MS = 60000;   // per camera
const SIREN_PUSH_GRACE  = 15000;   // M19 rising this soon after we set M20 → skip the generic alarm push
const ZONE_NAME         = 'alarm_zone';
const LINE_HALF_WIDTH   = 0.012;   // a "line" becomes a thin polygon zone (relative units)
const OBJECTS           = ['person', 'car', 'dog', 'cat'];

const ALERT_TYPES   = ['stranger', 'person', 'none'];
const ANALYSIS      = ['smart', 'motion'];
const RESPONSES     = ['push', 'siren', 'both'];
const ROTATIONS     = [0, 90, 180, 270];
// Default RTSP paths for XMEye-based cameras (Gadinan and similar). Editable per camera.
const DEFAULT_RTSP_MAIN = 'rtsp://{ip}:554/user={user}&password={password}&channel=1&stream=0.sdp?';
const DEFAULT_RTSP_SUB  = 'rtsp://{ip}:554/user={user}&password={password}&channel=1&stream=1.sdp?';

// ─── Settings ────────────────────────────────────────────────────────────────
const DEFAULT_SETTINGS = {
  cameras: [],
  detection: { manual: false, armLinked: true, schedule: { enabled: false, ranges: [] } },
  response: 'push',
};

let settings = structuredClone(DEFAULT_SETTINGS);
let storageError = null;   // set when the encrypted data dir is not usable

function loadSettings() {
  try {
    fs.accessSync(DATA_DIR, fs.constants.W_OK);
  } catch (e) {
    storageError = `camera storage ${DATA_DIR} not writable (${e.code}) — is the encrypted disk mounted?`;
    console.error(storageError);
    return;
  }
  storageError = null;
  try {
    if (fs.existsSync(SETTINGS_FILE))
      settings = { ...structuredClone(DEFAULT_SETTINGS), ...JSON.parse(fs.readFileSync(SETTINGS_FILE, 'utf8')) };
  } catch (e) {
    console.error('cameras.json unreadable, starting empty:', e.message);
  }
}

function saveSettings() {
  if (storageError) throw new Error(storageError);
  const tmp = SETTINGS_FILE + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(settings, null, 2), { mode: 0o600 });
  fs.renameSync(tmp, SETTINGS_FILE);
}

// What the app may see: everything except the camera password.
function publicCamera(c) {
  const { password, ...rest } = c;
  return { ...rest, hasPassword: !!password };
}

// ─── Validation ──────────────────────────────────────────────────────────────
class BadRequest extends Error {}
const fail = (msg) => { throw new BadRequest(msg); };

const isIPv4 = (s) => typeof s === 'string' && net.isIPv4(s);
const isTime = (s) => typeof s === 'string' && /^([01]\d|2[0-3]):[0-5]\d$/.test(s);
const toMinutes = (t) => Number(t.slice(0, 2)) * 60 + Number(t.slice(3));

function validatePoints(zone) {
  if (zone === null || zone === undefined) return null;
  if (typeof zone !== 'object') fail('zone must be an object or null');
  if (!['polygon', 'line'].includes(zone.type)) fail('zone.type must be polygon or line');
  const pts = zone.points;
  if (!Array.isArray(pts)) fail('zone.points must be an array');
  if (zone.type === 'line' && pts.length !== 2) fail('a line needs exactly 2 points');
  if (zone.type === 'polygon' && (pts.length < 3 || pts.length > 20)) fail('a polygon needs 3-20 points');
  const clean = pts.map((p) => {
    if (!Array.isArray(p) || p.length !== 2) fail('each point is [x, y]');
    const [x, y] = p.map(Number);
    if (!(x >= 0 && x <= 1 && y >= 0 && y <= 1)) fail('point coordinates are relative (0..1)');
    return [Math.round(x * 1000) / 1000, Math.round(y * 1000) / 1000];
  });
  return { type: zone.type, points: clean };
}

// Merges a create/update body into `base` (existing camera or defaults).
function validateCamera(body, base) {
  const c = { ...base };
  const has = (k) => body[k] !== undefined;

  if (has('name')) {
    if (typeof body.name !== 'string' || !body.name.trim() || body.name.length > 40) fail('name: 1-40 chars');
    c.name = body.name.trim();
  }
  if (has('ip')) { if (!isIPv4(body.ip)) fail('ip must be IPv4'); c.ip = body.ip; }
  if (has('username')) {
    if (typeof body.username !== 'string' || body.username.length > 64) fail('username: up to 64 chars');
    c.username = body.username;
  }
  // Empty / missing password on update keeps the stored one.
  if (has('password') && body.password !== '') {
    if (typeof body.password !== 'string' || body.password.length > 128) fail('password: up to 128 chars');
    c.password = body.password;
  }
  for (const k of ['rtspMain', 'rtspSub']) {
    if (has(k)) {
      if (typeof body[k] !== 'string' || !/^rtsp:\/\/\S+$/.test(body[k]) || body[k].length > 300)
        fail(`${k} must be an rtsp:// URL template`);
      c[k] = body[k];
    }
  }
  if (has('rotation')) { if (!ROTATIONS.includes(body.rotation)) fail('rotation: 0/90/180/270'); c.rotation = body.rotation; }
  if (has('tilt')) {
    const t = Number(body.tilt);
    if (!(t >= -45 && t <= 45)) fail('tilt: -45..45'); c.tilt = Math.round(t * 10) / 10;
  }
  if (has('faceRecognition')) { if (typeof body.faceRecognition !== 'boolean') fail('faceRecognition: boolean'); c.faceRecognition = body.faceRecognition; }
  if (has('alertType')) { if (!ALERT_TYPES.includes(body.alertType)) fail('alertType: stranger/person/none'); c.alertType = body.alertType; }
  if (has('analysis')) { if (!ANALYSIS.includes(body.analysis)) fail('analysis: smart/motion'); c.analysis = body.analysis; }
  if (has('retentionDays')) {
    const d = Number(body.retentionDays);
    if (!Number.isInteger(d) || d < 1 || d > 365) fail('retentionDays: 1-365'); c.retentionDays = d;
  }
  if (has('zone')) c.zone = validatePoints(body.zone);

  if (!c.name || !c.ip) fail('name and ip are required');
  if (c.alertType !== 'none' && c.analysis !== 'smart') fail('alerts need analysis "smart"');
  if (c.alertType === 'stranger' && !c.faceRecognition) fail('"stranger" alerts need face recognition enabled');
  return c;
}

function validateGlobal(body) {
  const out = {};
  if (body.response !== undefined) {
    if (!RESPONSES.includes(body.response)) fail('response: push/siren/both');
    out.response = body.response;
  }
  if (body.detection !== undefined) {
    const d = body.detection;
    if (typeof d !== 'object' || d === null) fail('detection must be an object');
    const det = { ...settings.detection };
    if (d.manual !== undefined)    { if (typeof d.manual !== 'boolean') fail('detection.manual: boolean'); det.manual = d.manual; }
    if (d.armLinked !== undefined) { if (typeof d.armLinked !== 'boolean') fail('detection.armLinked: boolean'); det.armLinked = d.armLinked; }
    if (d.schedule !== undefined) {
      const s = d.schedule;
      if (typeof s !== 'object' || s === null || typeof s.enabled !== 'boolean' || !Array.isArray(s.ranges) || s.ranges.length > 10)
        fail('detection.schedule: { enabled, ranges[≤10] }');
      det.schedule = {
        enabled: s.enabled,
        ranges: s.ranges.map((r) => {
          if (!Array.isArray(r.days) || !r.days.length || !r.days.every((x) => Number.isInteger(x) && x >= 0 && x <= 6))
            fail('range.days: 0 (Sun) .. 6 (Sat)');
          if (!isTime(r.start) || !isTime(r.end) || r.start === r.end) fail('range.start/end: "HH:MM", different');
          return { days: [...new Set(r.days)].sort(), start: r.start, end: r.end };
        }),
      };
    }
    out.detection = det;
  }
  return out;
}

// ─── Detection on/off ────────────────────────────────────────────────────────
// A range that ends before it starts runs overnight: it belongs to its start day.
function scheduleActive(schedule, now = new Date()) {
  if (!schedule?.enabled) return false;
  const day = now.getDay();
  const yesterday = (day + 6) % 7;
  const mins = now.getHours() * 60 + now.getMinutes();
  return schedule.ranges.some((r) => {
    const s = toMinutes(r.start), e = toMinutes(r.end);
    if (s < e) return r.days.includes(day) && mins >= s && mins < e;
    return (r.days.includes(day) && mins >= s) || (r.days.includes(yesterday) && mins < e);
  });
}

// ─── Frigate HTTP client ─────────────────────────────────────────────────────
// Frigate's :8971 uses a self-signed certificate; we only skip verification for loopback.
const isLoopback = ['127.0.0.1', 'localhost', '::1'].includes(FRIGATE_URL.hostname);
const frigateMod = FRIGATE_URL.protocol === 'https:' ? https : http;
const frigateAgent = new frigateMod.Agent({ keepAlive: true, ...(frigateMod === https ? { rejectUnauthorized: !isLoopback } : {}) });
let frigateCookie = null;

function rawRequest(method, p, { headers = {}, body, timeout = 15000 } = {}) {
  return new Promise((resolve, reject) => {
    const req = frigateMod.request({
      host: FRIGATE_URL.hostname,
      port: FRIGATE_URL.port || (frigateMod === https ? 443 : 80),
      method, path: p, agent: frigateAgent,
      headers: { ...headers, ...(frigateCookie ? { Cookie: frigateCookie } : {}) },
    }, resolve);
    req.on('error', reject);
    req.setTimeout(timeout, () => req.destroy(new Error('frigate timeout')));
    req.end(body);
  });
}

function readBody(res) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    res.on('data', (c) => chunks.push(c));
    res.on('end', () => resolve(Buffer.concat(chunks)));
    res.on('error', reject);
  });
}

async function frigateLogin() {
  if (!FRIGATE_PASSWORD) throw new Error('FRIGATE_PASSWORD not set');
  frigateCookie = null;
  const body = JSON.stringify({ user: FRIGATE_USER, password: FRIGATE_PASSWORD });
  const res = await rawRequest('POST', '/api/login', {
    headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) }, body,
  });
  res.resume();
  if (res.statusCode >= 300) throw new Error(`frigate login failed (${res.statusCode})`);
  const token = (res.headers['set-cookie'] || []).map((c) => c.split(';')[0]).find((c) => c.startsWith('frigate_token='));
  if (!token) throw new Error('frigate login: no token cookie');
  frigateCookie = token;
}

// Returns the raw response (stream). Logs in on first use and once more on 401.
async function frigateRequest(method, p, opts = {}) {
  if (!frigateCookie) await frigateLogin();
  let res = await rawRequest(method, p, opts);
  if (res.statusCode === 401) {
    res.resume();
    await frigateLogin();
    res = await rawRequest(method, p, opts);
  }
  return res;
}

async function frigateJson(method, p, json) {
  const body = json === undefined ? undefined : Buffer.from(JSON.stringify(json));
  const res = await frigateRequest(method, p, body
    ? { headers: { 'Content-Type': 'application/json', 'Content-Length': body.length }, body }
    : {});
  const buf = await readBody(res);
  if (res.statusCode >= 300) throw new Error(`frigate ${method} ${p.split('?')[0]} → ${res.statusCode} ${buf.toString().slice(0, 200)}`);
  const text = buf.toString();
  try { return JSON.parse(text); } catch { return text; }
}

// Streams a Frigate response to the app (snapshots, clips, live video). Passes Range
// through so the player can seek inside clips.
async function proxy(req, res, p, { timeout = 30000 } = {}) {
  try {
    const headers = req.headers.range ? { Range: req.headers.range } : {};
    const up = await frigateRequest('GET', p, { headers, timeout });
    res.status(up.statusCode);
    for (const h of ['content-type', 'content-length', 'content-range', 'accept-ranges', 'cache-control'])
      if (up.headers[h]) res.setHeader(h, up.headers[h]);
    req.on('close', () => up.destroy());
    up.pipe(res);
  } catch (e) {
    if (!res.headersSent) res.status(502).json({ ok: false, error: e.message });
    else res.destroy();
  }
}

// ─── Frigate config ──────────────────────────────────────────────────────────
const fillTemplate = (tpl, c) => tpl
  .replaceAll('{ip}', c.ip)
  .replaceAll('{user}', encodeURIComponent(c.username || ''))
  .replaceAll('{password}', encodeURIComponent(c.password || ''));

const relCoords = (pts) => pts.map(([x, y]) => `${x},${y}`).join(',');

// A line (p1→p2) becomes a thin rectangle around it; someone crossing it enters the zone.
function lineToPolygon([[x1, y1], [x2, y2]]) {
  const len = Math.hypot(x2 - x1, y2 - y1) || 1;
  const nx = (-(y2 - y1) / len) * LINE_HALF_WIDTH, ny = ((x2 - x1) / len) * LINE_HALF_WIDTH;
  const clamp = (v) => Math.min(1, Math.max(0, Math.round(v * 1000) / 1000));
  return [[x1 + nx, y1 + ny], [x2 + nx, y2 + ny], [x2 - nx, y2 - ny], [x1 - nx, y1 - ny]]
    .map(([x, y]) => [clamp(x), clamp(y)]);
}

// Frigate 0.17 replaced record.retain with record.continuous / record.motion.
function recordConfig(c, v017) {
  const days = c.retentionDays;
  if (c.analysis === 'motion')
    return v017 ? { enabled: true, continuous: { days: 0 }, motion: { days } }
                : { enabled: true, retain: { days, mode: 'motion' } };
  const objects = { retain: { days, mode: 'active_objects' } };
  return v017 ? { enabled: true, continuous: { days: 0 }, motion: { days: 0 }, alerts: objects, detections: objects }
              : { enabled: true, retain: { days: 0 }, alerts: objects, detections: objects };
}

// "0.16.2-abc" → 16 (minor; Frigate is still 0.x). Major ≥ 1 counts as newer than any 0.x.
function minorOf(version) {
  const [maj, min] = String(version).split('.').map((n) => parseInt(n, 10));
  return maj > 0 ? 1000 : (min || 0);
}
// Face recognition arrived in Frigate 0.16. The no-AVX add-on (needed on this
// Celeron N4000) ships 0.14, so faces stay off until the hardware is replaced.
const supportsFaces = (version) => minorOf(version) >= 16;

let frigateVersion = null;          // cached from /api/version
async function refreshVersion() {
  frigateVersion = String(await frigateJson('GET', '/api/version')).trim();
  return frigateVersion;
}
const facesAvailable = () => frigateVersion !== null && supportsFaces(frigateVersion);

// JSON is valid YAML, so the generated config is sent as JSON text.
// Older Frigate rejects unknown keys, so face settings are only written when supported.
function buildFrigateConfig(version) {
  const v017 = minorOf(version) >= 17;
  const faces = supportsFaces(version);
  const cams = settings.cameras;

  const go2rtcStreams = {};
  const cameras = {};
  for (const c of cams) {
    go2rtcStreams[c.id] = [fillTemplate(c.rtspMain, c)];
    go2rtcStreams[`${c.id}_sub`] = [fillTemplate(c.rtspSub, c)];
    const smart = c.analysis === 'smart';
    const cam = {
      ffmpeg: {
        inputs: [
          { path: `rtsp://127.0.0.1:8554/${c.id}`, input_args: 'preset-rtsp-restream', roles: ['record'] },
          { path: `rtsp://127.0.0.1:8554/${c.id}_sub`, input_args: 'preset-rtsp-restream', roles: ['detect'] },
        ],
      },
      // Motion-only cameras still need the detect stream for motion; object detection is off.
      detect: { enabled: smart, fps: 5 },
      objects: { track: smart ? OBJECTS : [] },
      record: recordConfig(c, v017),
      snapshots: { enabled: smart, retain: { default: c.retentionDays } },
    };
    if (faces) cam.face_recognition = { enabled: smart && !!c.faceRecognition };
    if (smart && c.zone) {
      const pts = c.zone.type === 'line' ? lineToPolygon(c.zone.points) : c.zone.points;
      cam.zones = { [ZONE_NAME]: { coordinates: relCoords(pts), objects: ['person'] } };
    }
    cameras[c.id] = cam;
  }

  const config = {
    mqtt: { enabled: false },
    // Event DB (and face metadata) on the encrypted disk, not the USB stick.
    database: { path: '/media/frigate/frigate.db' },
    detectors: { ov: { type: 'openvino', device: DETECTOR_DEVICE } },
    model: {
      width: 300, height: 300, input_tensor: 'nhwc', input_pixel_format: 'bgr',
      path: '/openvino-model/ssdlite_mobilenet_v2.xml',
      labelmap_path: '/openvino-model/coco_91cl_bkgr.txt',
    },
    ffmpeg: { hwaccel_args: 'preset-vaapi' },
    go2rtc: { streams: go2rtcStreams },
    cameras,
  };
  if (faces)
    config.face_recognition = { enabled: cams.some((c) => c.analysis === 'smart' && c.faceRecognition), model_size: 'small' };
  return config;
}

async function applyFrigateConfig() {
  const version = await refreshVersion();
  const body = Buffer.from(JSON.stringify(buildFrigateConfig(version), null, 2));
  const res = await frigateRequest('POST', '/api/config/save?save_option=restart', {
    headers: { 'Content-Type': 'text/plain', 'Content-Length': body.length }, body, timeout: 60000,
  });
  const out = (await readBody(res)).toString();
  if (res.statusCode >= 300) throw new Error(`frigate rejected config (${res.statusCode}): ${out.slice(0, 300)}`);
}

// ─── Faces ───────────────────────────────────────────────────────────────────
let authorizedCount = 0;

const isFaceName = (s) => typeof s === 'string' && /^[\p{L}\p{N} _-]{1,40}$/u.test(s) && s.trim() === s;

async function listFaces() {
  if (!facesAvailable()) { authorizedCount = 0; return []; }
  const data = await frigateJson('GET', '/api/faces');
  // { name: [imageIds...] } — "train" holds unassigned attempts, not a person.
  const faces = Object.entries(data || {})
    .filter(([name]) => name !== 'train')
    .map(([name, images]) => ({ name, images: Array.isArray(images) ? images.length : 0 }));
  authorizedCount = faces.filter((f) => f.images > 0).length;
  return faces;
}

function multipart(field, filename, type, buf) {
  const boundary = '----alarm' + crypto.randomBytes(12).toString('hex');
  const head = Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="${field}"; filename="${filename}"\r\nContent-Type: ${type}\r\n\r\n`);
  const tail = Buffer.from(`\r\n--${boundary}--\r\n`);
  return { body: Buffer.concat([head, buf, tail]), type: `multipart/form-data; boundary=${boundary}` };
}

// ─── LAN scan ────────────────────────────────────────────────────────────────
// Home LAN prefixes only (skips Docker / Home Assistant bridges on 172.x).
function lanPrefixes() {
  const out = new Set();
  for (const iface of Object.values(os.networkInterfaces()).flat()) {
    if (!iface || iface.family !== 'IPv4' || iface.internal) continue;
    if (/^(192\.168|10)\./.test(iface.address)) out.add(iface.address.split('.').slice(0, 3).join('.'));
  }
  return [...out];
}

function probePort(ip, port, timeout = 500) {
  return new Promise((resolve) => {
    const s = net.connect({ host: ip, port });
    const done = (ok) => { s.destroy(); resolve(ok); };
    s.setTimeout(timeout, () => done(false));
    s.once('connect', () => done(true));
    s.once('error', () => done(false));
  });
}

async function rtspSweep(prefix) {
  const ips = Array.from({ length: 254 }, (_, i) => `${prefix}.${i + 1}`);
  const found = [];
  for (let i = 0; i < ips.length; i += 64) {
    const chunk = ips.slice(i, i + 64);
    const res = await Promise.all(chunk.map((ip) => probePort(ip, 554)));
    chunk.forEach((ip, k) => res[k] && found.push(ip));
  }
  return found;
}

// ONVIF WS-Discovery: multicast Probe, collect who answers.
function onvifDiscover(timeout = 3000) {
  return new Promise((resolve) => {
    const found = new Map();
    const sock = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    const probe = `<?xml version="1.0" encoding="UTF-8"?><e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl"><e:Header><w:MessageID>uuid:${crypto.randomUUID()}</w:MessageID><w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header><e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>`;
    sock.on('message', (msg, rinfo) => {
      const xml = msg.toString();
      const name = /onvif:\/\/www\.onvif\.org\/name\/([^\s<]+)/.exec(xml)?.[1];
      found.set(rinfo.address, name ? decodeURIComponent(name) : null);
    });
    sock.on('error', () => {});
    sock.bind(0, () => {
      sock.send(probe, 3702, '239.255.255.250');
      setTimeout(() => { sock.close(); resolve(found); }, timeout);
    });
  });
}

// ─── Events → alerts ─────────────────────────────────────────────────────────
const seen = new Map();        // frigate event id → { first, done }
const lastAlertAt = new Map(); // camera id → ms
let sirenSetAt = 0;
let hooks = null;              // { sendPush, writeCoil, getStatus }

function detectionActive() {
  const d = settings.detection;
  const armed = hooks?.getStatus()?.m175 === 1;
  return !!(d.manual || scheduleActive(d.schedule) || (d.armLinked && armed));
}

const subLabelName = (e) => (Array.isArray(e.sub_label) ? e.sub_label[0] : e.sub_label) || null;

async function raiseAlert(cam, e, kind) {
  lastAlertAt.set(cam.id, Date.now());
  const r = settings.response;
  console.log(`camera alert: ${kind} on ${cam.name} (event ${e.id}) → ${r}`);
  if (r === 'siren' || r === 'both') {
    try {
      sirenSetAt = Date.now();
      await hooks.writeCoil(SIREN_COIL, true);
    } catch (err) {
      console.error('camera siren (M20) write failed:', err.message);
    }
  }
  if (r === 'push' || r === 'both') {
    const title = kind === 'stranger' ? 'אדם זר!' : 'זוהה אדם';
    const body = kind === 'stranger' ? `אדם לא מזוהה — ${cam.name}` : `אדם באזור המסומן — ${cam.name}`;
    await hooks.sendPush(title, body, 'intruder', { eventId: e.id, cameraId: cam.id, cameraName: cam.name, kind });
  }
}

async function pollEvents() {
  if (storageError || !FRIGATE_PASSWORD || !settings.cameras.length) return;
  const active = detectionActive();
  let events;
  try {
    const after = Math.floor(Date.now() / 1000) - 600;
    events = await frigateJson('GET', `/api/events?labels=person&limit=50&after=${after}`);
  } catch (e) {
    return; // Frigate restarting / not installed yet — try again next tick
  }
  if (!Array.isArray(events)) return;
  const now = Date.now();

  for (const e of events) {
    const cam = settings.cameras.find((c) => c.id === e.camera);
    if (!cam || cam.alertType === 'none' || cam.analysis !== 'smart') continue;
    const st = seen.get(e.id) || { first: now, done: false };
    seen.set(e.id, st);
    if (st.done || !active) continue;
    if (cam.zone && !(e.zones || []).includes(ZONE_NAME)) continue;
    if (now - (lastAlertAt.get(cam.id) || 0) < ALERT_COOLDOWN_MS) { st.done = true; continue; }

    if (cam.alertType === 'person') {
      st.done = true;
      await raiseAlert(cam, e, 'person');
    } else if (cam.alertType === 'stranger') {
      if (authorizedCount === 0) continue;               // nobody enrolled yet → everyone would be a "stranger"
      if (subLabelName(e)) { st.done = true; continue; } // recognised as an authorised person
      if (now - st.first >= FACE_WAIT_MS || e.end_time) {
        st.done = true;
        await raiseAlert(cam, e, 'stranger');
      }
    }
  }
  for (const [id, st] of seen) if (now - st.first > 3600_000) seen.delete(id);
}

// Called by server.js after every PLC poll.
async function onStatus(status, prev) {
  // Disarm (M175 1→0) clears the camera siren.
  if (prev && prev.m175 === 1 && status.m175 === 0 && status.m20 === 1) {
    try { await hooks.writeCoil(SIREN_COIL, false); console.log('camera siren cleared by disarm'); }
    catch (e) { console.error('M20 reset failed:', e.message); }
  }
}

// True right after the cameras raised the siren — lets server.js skip the generic
// "פריצה" push if the PLC also raised M19 for it.
const recentlyRaisedSiren = () => Date.now() - sirenSetAt < SIREN_PUSH_GRACE;

// ─── Routes ──────────────────────────────────────────────────────────────────
const wrap = (fn) => async (req, res) => {
  try {
    await fn(req, res);
  } catch (e) {
    if (e instanceof BadRequest) return res.status(400).json({ ok: false, error: e.message });
    console.error('cameras route error:', e.message);
    if (!res.headersSent) res.status(500).json({ ok: false, error: e.message });
  }
};

const needStorage = (_req, res, next) =>
  storageError ? res.status(503).json({ ok: false, error: storageError }) : next();

const findCam = (id) => settings.cameras.find((c) => c.id === id);

// Face recognition can be switched on only when the running Frigate supports it
// (unknown version = Frigate down; the setting is then accepted and applied later).
function checkFaceHardware(cam) {
  if (cam.faceRecognition && frigateVersion !== null && !supportsFaces(frigateVersion))
    fail(`face recognition needs Frigate 0.16+, this server runs ${frigateVersion}`);
}
const isEventId = (s) => /^[\w.-]{1,64}$/.test(s);

// Saves settings and pushes them into Frigate. Settings are kept even when Frigate
// rejects them; the error goes back to the app.
async function commit(res, extra = {}) {
  saveSettings();
  let frigateError = null;
  try { await applyFrigateConfig(); } catch (e) { frigateError = e.message; console.error(e.message); }
  res.json({ ok: true, frigateError, ...extra });
}

function setup(app, h) {
  hooks = h;
  loadSettings();
  const r = express.Router();
  r.use(needStorage);

  // Overview for the camera tab.
  r.get('/', wrap(async (_req, res) => {
    let frigateOk = true;
    try { await refreshVersion(); await listFaces(); } catch { frigateOk = false; }
    res.json({
      ok: true,
      frigateOk,
      frigateVersion,
      faceSupported: facesAvailable(),
      cameras: settings.cameras.map(publicCamera),
      detection: settings.detection,
      response: settings.response,
      detectionActive: detectionActive(),
      sirenOn: hooks.getStatus()?.m20 === 1,
      authorizedCount,
    });
  }));

  r.post('/scan', wrap(async (_req, res) => {
    const [onvif, ...sweeps] = await Promise.all([onvifDiscover(), ...lanPrefixes().map(rtspSweep)]);
    const rtsp = new Set(sweeps.flat());
    const ips = [...new Set([...onvif.keys(), ...rtsp])].sort((a, b) =>
      a.split('.').map(Number).reduce((x, y) => x * 256 + y) - b.split('.').map(Number).reduce((x, y) => x * 256 + y));
    res.json({
      ok: true,
      devices: ips.map((ip) => ({
        ip, onvif: onvif.has(ip), rtsp: rtsp.has(ip), name: onvif.get(ip) || null,
        addedAs: settings.cameras.find((c) => c.ip === ip)?.name || null,
      })),
    });
  }));

  r.put('/settings', wrap(async (req, res) => {
    Object.assign(settings, validateGlobal(req.body || {}));
    saveSettings();
    res.json({ ok: true, detection: settings.detection, response: settings.response, detectionActive: detectionActive() });
  }));

  r.post('/siren/stop', wrap(async (_req, res) => {
    await hooks.writeCoil(SIREN_COIL, false);
    sirenSetAt = 0;
    res.json({ ok: true });
  }));

  // ── Faces (authorised people) ──
  const FACES_UNAVAILABLE = 'face recognition needs Frigate 0.16+ (not available on this hardware)';
  r.use('/faces', (_req, res, next) => facesAvailable()
    ? next() : res.status(501).json({ ok: false, faceSupported: false, error: FACES_UNAVAILABLE }));

  r.get('/faces', wrap(async (_req, res) => res.json({ ok: true, faces: await listFaces() })));

  // Body: raw JPEG/PNG (Content-Type image/jpeg or image/png).
  r.post('/faces/:name', express.raw({ type: ['image/jpeg', 'image/png'], limit: '10mb' }), wrap(async (req, res) => {
    const name = req.params.name;
    if (!isFaceName(name)) fail('name: letters, digits, space, - or _ (1-40)');
    if (!Buffer.isBuffer(req.body) || !req.body.length) fail('send the image as the request body (image/jpeg or image/png)');
    const n = encodeURIComponent(name);
    const faces = await listFaces();
    if (!faces.some((f) => f.name === name)) await frigateJson('POST', `/api/faces/${n}/create`);
    const ext = req.is('image/png') ? 'png' : 'jpg';
    const mp = multipart('file', `face.${ext}`, req.get('content-type'), req.body);
    const up = await frigateRequest('POST', `/api/faces/${n}/register`, {
      headers: { 'Content-Type': mp.type, 'Content-Length': mp.body.length }, body: mp.body, timeout: 60000,
    });
    const out = (await readBody(up)).toString();
    if (up.statusCode >= 300) return res.status(422).json({ ok: false, error: `face not accepted: ${out.slice(0, 200)}` });
    res.json({ ok: true, faces: await listFaces() });
  }));

  r.delete('/faces/:name', wrap(async (req, res) => {
    const name = req.params.name;
    if (!isFaceName(name)) fail('bad name');
    const data = await frigateJson('GET', '/api/faces');
    const ids = data?.[name];
    if (!Array.isArray(ids)) return res.status(404).json({ ok: false, error: 'not found' });
    await frigateJson('POST', `/api/faces/${encodeURIComponent(name)}/delete`, { ids });
    res.json({ ok: true, faces: await listFaces() });
  }));

  // ── Recordings (Frigate events) ──
  r.get('/events', wrap(async (req, res) => {
    const q = new URLSearchParams({ limit: String(Math.min(Number(req.query.limit) || 50, 200)), has_clip: '1' });
    if (req.query.camera) {
      if (!findCam(String(req.query.camera))) fail('unknown camera');
      q.set('cameras', String(req.query.camera));
    }
    for (const k of ['before', 'after']) if (req.query[k] && /^\d+(\.\d+)?$/.test(req.query[k])) q.set(k, req.query[k]);
    const events = await frigateJson('GET', `/api/events?${q}`);
    res.json({
      ok: true,
      events: (Array.isArray(events) ? events : []).map((e) => ({
        id: e.id, cameraId: e.camera, cameraName: findCam(e.camera)?.name || e.camera,
        label: e.label, subLabel: subLabelName(e), zones: e.zones || [],
        startTime: e.start_time, endTime: e.end_time, hasClip: !!e.has_clip, hasSnapshot: !!e.has_snapshot,
      })),
    });
  }));

  r.get('/events/:eid/thumbnail', wrap(async (req, res) => {
    if (!isEventId(req.params.eid)) fail('bad event id');
    await proxy(req, res, `/api/events/${req.params.eid}/thumbnail.jpg`);
  }));
  r.get('/events/:eid/snapshot', wrap(async (req, res) => {
    if (!isEventId(req.params.eid)) fail('bad event id');
    await proxy(req, res, `/api/events/${req.params.eid}/snapshot.jpg?bbox=1`);
  }));
  r.get('/events/:eid/clip', wrap(async (req, res) => {
    if (!isEventId(req.params.eid)) fail('bad event id');
    await proxy(req, res, `/api/events/${req.params.eid}/clip.mp4`, { timeout: 120000 });
  }));

  // ── Cameras ──
  r.post('/', wrap(async (req, res) => {
    if (settings.cameras.length >= 8) fail('up to 8 cameras');
    const id = 'cam_' + crypto.randomBytes(3).toString('hex');
    const cam = validateCamera(req.body || {}, {
      id, name: '', ip: '', username: 'admin', password: '',
      rtspMain: DEFAULT_RTSP_MAIN, rtspSub: DEFAULT_RTSP_SUB,
      rotation: 0, tilt: 0, faceRecognition: false, alertType: 'person', analysis: 'smart',
      retentionDays: 14, zone: null,
    });
    checkFaceHardware(cam);
    settings.cameras.push(cam);
    await commit(res, { camera: publicCamera(cam) });
  }));

  r.put('/:id', wrap(async (req, res) => {
    const i = settings.cameras.findIndex((c) => c.id === req.params.id);
    if (i < 0) return res.status(404).json({ ok: false, error: 'not found' });
    const cam = validateCamera(req.body || {}, settings.cameras[i]);
    checkFaceHardware(cam);
    // Rotation / tilt are display-only (done on the phone) — no Frigate restart needed.
    const frigateKeys = ['ip', 'username', 'password', 'rtspMain', 'rtspSub', 'faceRecognition', 'alertType', 'analysis', 'retentionDays', 'zone'];
    const needsFrigate = frigateKeys.some((k) => JSON.stringify(cam[k]) !== JSON.stringify(settings.cameras[i][k]));
    settings.cameras[i] = cam;
    if (needsFrigate) return commit(res, { camera: publicCamera(cam) });
    saveSettings();
    res.json({ ok: true, frigateError: null, camera: publicCamera(cam) });
  }));

  r.delete('/:id', wrap(async (req, res) => {
    const before = settings.cameras.length;
    settings.cameras = settings.cameras.filter((c) => c.id !== req.params.id);
    if (settings.cameras.length === before) return res.status(404).json({ ok: false, error: 'not found' });
    await commit(res);
  }));

  r.get('/:id/snapshot', wrap(async (req, res) => {
    const c = findCam(req.params.id);
    if (!c) return res.status(404).json({ ok: false, error: 'not found' });
    const h = Math.min(Math.max(Number(req.query.h) || 360, 120), 1440);
    await proxy(req, res, `/api/${c.id}/latest.jpg?h=${h}`);
  }));

  // Live fMP4 from go2rtc (inside Frigate). ?quality=sub for the grid / slow links.
  r.get('/:id/live', wrap(async (req, res) => {
    const c = findCam(req.params.id);
    if (!c) return res.status(404).json({ ok: false, error: 'not found' });
    const src = req.query.quality === 'sub' ? `${c.id}_sub` : c.id;
    await proxy(req, res, `/api/go2rtc/api/stream.mp4?src=${encodeURIComponent(src)}`, { timeout: 20000 });
  }));

  app.use('/cameras', r);

  setInterval(() => { pollEvents().catch((e) => console.error('camera events:', e.message)); }, EVENT_POLL_MS);
  const refresh = () => refreshVersion().then(listFaces).catch(() => {});
  setInterval(refresh, 60000);
  refresh();
  if (!FRIGATE_PASSWORD) console.warn('FRIGATE_PASSWORD not set — camera features idle');
}

module.exports = { setup, onStatus, recentlyRaisedSiren,
  // exported for tests
  _internal: { validateCamera, validateGlobal, scheduleActive, lineToPolygon, buildFrigateConfig, supportsFaces, setSettings: (s) => { settings = s; } } };

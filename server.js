const express = require('express');
const cors = require('cors');
const rateLimit = require('express-rate-limit');
const crypto = require('crypto');

const { GITHUB_TOKEN, GITHUB_REPO, ALLOWED_ORIGIN } = process.env; // GITHUB_REPO = "username/repo"
const BRANCH = process.env.GITHUB_BRANCH || 'main';
const BASE = process.env.PUBLIC_URL || process.env.RENDER_EXTERNAL_URL || '';
const PORT = process.env.PORT || 3000;

if (!GITHUB_TOKEN || !GITHUB_REPO) console.warn('Set GITHUB_TOKEN and GITHUB_REPO env vars!');

const app = express();
app.set('trust proxy', 1);
app.use(cors({ origin: ALLOWED_ORIGIN ? ALLOWED_ORIGIN.split(',') : true }));
app.use(express.json({ limit: '3mb' }));

const icons = new Map(); // id -> { buf, type, t }
setInterval(() => {
  const now = Date.now();
  for (const [k, v] of icons) if (now - v.t > 30 * 60 * 1000) icons.delete(k);
}, 10 * 60 * 1000);

const gh = () => ({
  Authorization: `Bearer ${GITHUB_TOKEN}`,
  Accept: 'application/vnd.github+json',
  'X-GitHub-Api-Version': '2022-11-28',
  'User-Agent': 'webtoapk-api',
});

const ENUMS = {
  ori: ['auto', 'portrait', 'landscape'],
  splash: ['fade', 'zoom', 'slide-up', 'pulse'],
  ua: ['default', 'desktop'],
  sdk: ['21', '24', '26', '29'],
};
const FLAGS = ['fs', 'ptr', 'off', 'js', 'dl', 'cam', 'push', 'ext', 'ss', 'aab'];

function clean(b) {
  const str = (v, d = '') => (typeof v === 'string' ? v : v == null ? d : String(v));
  const c = {};
  c.name = str(b.name, 'My App').replace(/[\x00-\x1f<>&"'\\%@?]/g, '').trim().slice(0, 30) || 'My App';
  c.pkg = str(b.pkg);
  if (!/^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*){1,4}$/.test(c.pkg)) throw new Error('Invalid package name (example: com.myapp.app)');
  c.url = str(b.url).trim();
  if (!/^https?:\/\/[^\s"'<>\\]+$/.test(c.url) || c.url.length > 2000) throw new Error('Invalid website URL');
  c.ver = str(b.ver, '1.0.0');
  if (!/^[0-9A-Za-z.\-]{1,20}$/.test(c.ver)) throw new Error('Invalid version name');
  c.vc = str(b.vc, '1');
  if (!/^\d{1,10}$/.test(c.vc) || +c.vc < 1 || +c.vc > 2100000000) throw new Error('Invalid version code');
  c.color = str(b.color, '#7c5cff');
  if (!/^#[0-9a-fA-F]{6}$/.test(c.color)) throw new Error('Invalid color');
  for (const k of Object.keys(ENUMS)) {
    c[k] = str(b[k], ENUMS[k][0]);
    if (k === 'sdk' && !b[k]) c[k] = '26';
    if (!ENUMS[k].includes(c[k])) throw new Error('Invalid option: ' + k);
  }
  c.feed = str(b.feed).trim();
  if (c.feed && (!/^https?:\/\/[^\s"'<>\\]+$/.test(c.feed) || c.feed.length > 2000)) throw new Error('Invalid notification feed URL');
  for (const f of FLAGS) c[f] = b[f] === true;
  return c;
}

function saveIcon(dataUri, id) {
  const m = /^data:image\/(png|jpeg|webp);base64,([A-Za-z0-9+/=]+)$/.exec(dataUri || '');
  if (!m) return '';
  const buf = Buffer.from(m[2], 'base64');
  if (buf.length > 1.5 * 1024 * 1024) throw new Error('Icon too large (max 1.5 MB)');
  const png = buf.slice(0, 4).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47]));
  const jpg = buf.slice(0, 3).equals(Buffer.from([0xff, 0xd8, 0xff]));
  const webp = buf.slice(0, 4).toString() === 'RIFF' && buf.slice(8, 12).toString() === 'WEBP';
  if (!png && !jpg && !webp) throw new Error('Invalid icon file');
  if (!BASE) return '';
  icons.set(id, { buf, type: png ? 'image/png' : jpg ? 'image/jpeg' : 'image/webp', t: Date.now() });
  return `${BASE}/icons/${id}.png`;
}

app.get('/', (req, res) => res.sendFile(require('path').join(__dirname, 'index.html')));
app.get('/health', (req, res) => res.send('ok'));

app.get('/icons/:id.png', (req, res) => {
  const i = icons.get(req.params.id);
  if (!i) return res.sendStatus(404);
  res.type(i.type).send(i.buf);
});

app.post('/api/build', rateLimit({ windowMs: 60 * 60 * 1000, limit: 10, standardHeaders: true, legacyHeaders: false, message: { error: 'Too many builds, try again later' } }), async (req, res) => {
  try {
    const cfg = clean(req.body || {});
    const id = crypto.randomBytes(6).toString('hex');
    const iconUrl = saveIcon(req.body.icon, id);
    const r = await fetch(`https://api.github.com/repos/${GITHUB_REPO}/actions/workflows/build.yml/dispatches`, {
      method: 'POST',
      headers: { ...gh(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ ref: BRANCH, inputs: { id, config: JSON.stringify(cfg), icon_url: iconUrl } }),
    });
    if (r.status !== 204) {
      console.error('dispatch failed', r.status, await r.text());
      return res.status(502).json({ error: 'Could not start build' });
    }
    res.json({ id });
  } catch (e) {
    res.status(400).json({ error: e.message });
  }
});

app.get('/api/status/:id', rateLimit({ windowMs: 60 * 1000, limit: 60, standardHeaders: true, legacyHeaders: false }), async (req, res) => {
  const id = req.params.id;
  if (!/^[a-f0-9]{12}$/.test(id)) return res.status(400).json({ error: 'bad id' });
  try {
    const rel = await fetch(`https://api.github.com/repos/${GITHUB_REPO}/releases/tags/build-${id}`, { headers: gh() });
    if (rel.ok) {
      const j = await rel.json();
      const asset = (j.assets || [])[0];
      if (asset) return res.json({ status: 'done', downloadUrl: asset.browser_download_url });
    }
    const runs = await fetch(`https://api.github.com/repos/${GITHUB_REPO}/actions/workflows/build.yml/runs?event=workflow_dispatch&per_page=30`, { headers: gh() });
    if (runs.ok) {
      const j = await runs.json();
      const run = (j.workflow_runs || []).find((x) => x.display_title === `build ${id}`);
      if (run) {
        if (run.status === 'completed' && run.conclusion !== 'success') return res.json({ status: 'failed' });
        return res.json({ status: run.status === 'in_progress' ? 'building' : 'queued' });
      }
    }
    res.json({ status: 'queued' });
  } catch (e) {
    res.status(500).json({ error: 'status check failed' });
  }
});

app.listen(PORT, () => console.log('API on port', PORT));

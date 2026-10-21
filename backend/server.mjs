// Backend Gold Maroc 18K : tâche planifiée (4 créneaux/jour, lundi→samedi, Africa/Casablanca) + API de lecture.
// Zéro dépendance. Node ≥ 20.   Variables : PORT, DATA_FILE, BACKEND_TOKEN (optionnel mais recommandé), RUN_ON_START=1 (test)
import { createServer } from 'node:http';
import { loadState, saveState, tick, runSlot, describeNext } from './lib/job.mjs';
import { timingSafeEqual } from 'node:crypto';

const PORT = Number(process.env.PORT || 8080);
const DATA_FILE = process.env.DATA_FILE || './data/state.json';
const TOKEN = process.env.BACKEND_TOKEN || '';

const state = loadState(DATA_FILE);
let running = false;

async function schedulerLoop() {
  if (running) return;
  running = true;
  try {
    if (await tick(state, Date.now())) saveState(DATA_FILE, state);
  } catch (e) { console.error('tick error', e); } finally { running = false; }
}

const authorized = (req) => {
  if (!TOKEN) return true;
  const got = Buffer.from((req.headers.authorization || '').replace(/^Bearer /, ''));
  const want = Buffer.from(TOKEN);
  return got.length === want.length && timingSafeEqual(got, want);
};

const server = createServer((req, res) => {
  const send = (code, obj) => { res.writeHead(code, { 'content-type': 'application/json', 'cache-control': 'no-store' }); res.end(JSON.stringify(obj)); };
  if (req.method !== 'GET') return send(405, { error: 'method_not_allowed' });
  if (req.url === '/healthz') return send(200, { ok: true, nextSlot: describeNext(Date.now()), lastSlotRun: state.lastSlotRun ? new Date(state.lastSlotRun).toISOString() : null });
  if (req.url === '/v1/latest') {
    if (!authorized(req)) return send(401, { error: 'unauthorized' });
    const g = state.gold, f = state.fx;
    return send(200, {
      gold: g && { price: g.price, computedAt: g.computedAt, isStale: String(g.isStale), fetchedAt: g.fetchedAt },
      fx: f && { usdMad: f.usdMad, updatedUnix: f.updatedUnix, nextUpdateUnix: f.nextUpdateUnix, fetchedAt: f.fetchedAt },
      nextSlot: describeNext(Date.now()),
    });
  }
  send(404, { error: 'not_found' });
});

server.listen(PORT, () => console.log(`backend sur :${PORT} — prochain créneau ${describeNext(Date.now())}`));
setInterval(schedulerLoop, 20000);
if (process.env.RUN_ON_START === '1') { runSlot(state, Date.now()).then(() => saveState(DATA_FILE, state)); } else { schedulerLoop(); }

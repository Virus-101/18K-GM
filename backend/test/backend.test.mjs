import test from 'node:test';
import assert from 'node:assert/strict';
import { nextSlot, lastSlotAtOrBefore, zoneParts, localToInstant } from '../lib/schedule.mjs';
import { parseGold, parseFx, ApiError } from '../lib/fetchers.mjs';
import { runSlot, tick } from '../lib/job.mjs';

const at = (iso) => Date.parse(iso);
const local = (ms) => { const p = zoneParts(ms); return `${p.weekday} ${String(p.h).padStart(2, '0')}:${String(p.mi).padStart(2, '0')}`; };

test('quatre créneaux locaux 07/10/13/18 du lundi au samedi, jamais le dimanche', () => {
  // 2026-10-19 = lundi (00:01 local = 23:01Z la veille car UTC+1)
  let cur = at('2026-10-18T23:01:00Z');
  const seen = [];
  for (let i = 0; i < 28; i++) { cur = nextSlot(cur); seen.push(local(cur)); }
  const perDay = {};
  seen.forEach((s) => { const [d, h] = s.split(' '); (perDay[d] ||= []).push(h); });
  assert.deepEqual(Object.keys(perDay), ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']);
  assert.equal(perDay.Mon.length, 8); // lundi suivant atteint après le samedi : dimanche sauté
  for (const d of ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']) assert.deepEqual(perDay[d].slice(0, 4), ['07:00', '10:00', '13:00', '18:00']);
  assert.ok(!('Sun' in perDay));
});

test('après samedi 18:00 → lundi 07:00', () => {
  const sat1800 = localToInstant(2026, 10, 24, 18, 0);
  assert.equal(local(sat1800), 'Sat 18:00');
  assert.equal(local(nextSlot(sat1800)), 'Mon 07:00');
});

test('dernier créneau échu un dimanche = samedi 18:00', () => {
  assert.equal(local(lastSlotAtOrBefore(at('2026-10-25T12:00:00Z'))), 'Sat 18:00');
});

test('fuseau Africa/Casablanca indépendamment du fuseau du serveur : 07:00 = 06:00Z hors Ramadan', () => {
  assert.equal(new Date(nextSlot(at('2026-10-21T05:30:00Z'))).toISOString(), '2026-10-21T06:00:00.000Z');
});

test('changement d’heure saisonnier (Ramadan 2026) : même heure locale, UTC différent', () => {
  const before = localToInstant(2026, 2, 10, 7, 0);   // UTC+1
  const during = localToInstant(2026, 3, 2, 7, 0);    // UTC+0 pendant le Ramadan
  assert.equal(new Date(before).toISOString(), '2026-02-10T06:00:00.000Z');
  assert.equal(new Date(during).toISOString(), '2026-03-02T07:00:00.000Z');
  assert.equal(local(before).slice(-5), '07:00'); assert.equal(local(during).slice(-5), '07:00');
});

const GOLD = { symbols: [{ symbol: 'XAU', quote_currency: 'USD', unit: 'troy_ounce', price: '4131.67', is_stale: false, computed_at: '2026-10-21T17:00:04.955729+00:00' }] };
const FX = { result: 'success', base_code: 'USD', time_last_update_unix: 1792540952, time_next_update_unix: 1792628862, rates: { MAD: 9.2 } };
const NOW = at('2026-10-21T17:10:00Z');

test('parseGold : valide et rejets', () => {
  assert.equal(parseGold(GOLD, NOW).price, '4131.67');
  for (const bad of [null, {}, { symbols: [] }]) assert.throws(() => parseGold(bad, NOW), ApiError);
  for (const p of ['abc', '0', '-1', '9999999']) assert.throws(() => parseGold({ symbols: [{ ...GOLD.symbols[0], price: p }] }, NOW), ApiError);
  assert.throws(() => parseGold({ symbols: [{ ...GOLD.symbols[0], unit: 'gram' }] }, NOW), ApiError);
  assert.throws(() => parseGold({ symbols: [{ ...GOLD.symbols[0], computed_at: '2026-10-23T00:00:00Z' }] }, NOW), ApiError);
});

test('parseFx : valide, erreur fournisseur, quota', () => {
  assert.equal(parseFx(FX).usdMad, '9.2');
  assert.throws(() => parseFx({ result: 'error', 'error-type': 'unsupported-code' }), (e) => e.kind === 'invalid');
  assert.throws(() => parseFx({ result: 'error', 'error-type': 'quota-reached' }), (e) => e.kind === 'rate_limited');
  assert.throws(() => parseFx({ ...FX, rates: { EUR: 1 } }), ApiError);
});

const fresh = () => ({ lastSlotRun: 0, gold: null, fx: null, log: [] });
const noSleep = { sleep: async () => {}, retryDelaysMs: [1, 1] };

test('une seule récupération par source par créneau si tout va bien', async () => {
  let g = 0, f = 0;
  const s = await runSlot(fresh(), 1000, { ...noSleep, gold: async () => (g++, { price: '1' }), fx: async () => (f++, { usdMad: '9' }) });
  assert.equal(g, 1); assert.equal(f, 1); assert.ok(s.gold && s.fx);
});

test('échec de l’or seul : le change est enregistré, 2 reprises puis abandon, dernière valeur or conservée', async () => {
  const st = fresh(); st.gold = { price: 'ancienne', fetchedAt: 'x' };
  let g = 0;
  await runSlot(st, 1000, { ...noSleep, gold: async () => { g++; throw new ApiError('network', 'hors ligne'); }, fx: async () => ({ usdMad: '9.2' }) });
  assert.equal(g, 3);                       // 1 essai + 2 reprises contrôlées
  assert.equal(st.gold.price, 'ancienne');  // dernière valeur valide conservée
  assert.equal(st.fx.usdMad, '9.2');
});

test('réponse invalide : pas de reprise inutile', async () => {
  let g = 0;
  await runSlot(fresh(), 1000, { ...noSleep, gold: async () => { g++; throw new ApiError('invalid', 'json'); }, fx: async () => ({ usdMad: '9' }) });
  assert.equal(g, 1);
});

test('tick : exécute le créneau échu une seule fois, rattrape après redémarrage, ignore le dimanche', async () => {
  const deps = { ...noSleep, gold: async () => ({ price: '1' }), fx: async () => ({ usdMad: '9' }) };
  const st = fresh();
  const t1 = at('2026-10-21T09:00:30Z'); // mercredi 10:00:30 local
  assert.equal(await tick(st, t1, deps), true);
  assert.equal(await tick(st, t1 + 60000, deps), false);     // déjà traité
  const t2 = at('2026-10-21T12:30:00Z');                      // redémarrage à 13:30 local : rattrape 13:00
  assert.equal(await tick(st, t2, deps), true);
  const sunday = at('2026-10-25T09:00:00Z');                  // dimanche : dernier créneau = samedi 18:00, trop ancien (> 6 h)
  assert.equal(await tick(st, sunday, deps), false);
});

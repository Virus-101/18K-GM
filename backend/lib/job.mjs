import { lastSlotAtOrBefore, nextSlot } from './schedule.mjs';
import { fetchFx, fetchGold } from './fetchers.mjs';
import { readFileSync, writeFileSync, mkdirSync, renameSync } from 'node:fs';
import { dirname } from 'node:path';

export function loadState(path) {
  try { return JSON.parse(readFileSync(path, 'utf8')); } catch { return { lastSlotRun: 0, gold: null, fx: null, log: [] }; }
}
export function saveState(path, state) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path + '.tmp', JSON.stringify(state, null, 2));
  renameSync(path + '.tmp', path);
}

const log = (state, entry) => { state.log.push({ at: new Date().toISOString(), ...entry }); state.log = state.log.slice(-200); };

/**
 * Exécute UNE récupération par source pour le créneau donné (+ reprises contrôlées : 2 max, espacées par `retryDelaysMs`).
 * Chaque source est conservée séparément : en cas d'échec d'une seule, l'autre est enregistrée et la dernière valeur valide de la première est gardée.
 */
export async function runSlot(state, slotMs, deps = {}) {
  const { gold = fetchGold, fx = fetchFx, sleep = (ms) => new Promise((r) => setTimeout(r, ms)), retryDelaysMs = [120000, 300000], now = () => Date.now() } = deps;
  for (const [name, fn] of [['gold', gold], ['fx', fx]]) {
    let attempt = 0;
    for (;;) {
      try {
        const v = await fn();
        state[name] = { ...v, fetchedAt: new Date(now()).toISOString() };
        log(state, { source: name, slot: new Date(slotMs).toISOString(), ok: true, attempt });
        break;
      } catch (e) {
        log(state, { source: name, slot: new Date(slotMs).toISOString(), ok: false, attempt, kind: e.kind || 'error', error: e.message });
        const retryable = e.kind !== 'invalid';
        if (!retryable || attempt >= retryDelaysMs.length) break;
        await sleep(e.kind === 'rate_limited' ? Math.max(e.retryAfterSec * 1000 || 0, retryDelaysMs[attempt]) : retryDelaysMs[attempt]);
        attempt++;
      }
    }
  }
  state.lastSlotRun = slotMs;
  return state;
}

/** Appelé périodiquement : exécute le dernier créneau échu s'il n'a pas encore été traité (rattrapage ≤ 6 h après redémarrage). */
export async function tick(state, nowMs, deps = {}, catchUpMs = 6 * 3600 * 1000) {
  const slot = lastSlotAtOrBefore(nowMs);
  if (slot > state.lastSlotRun && nowMs - slot <= catchUpMs) {
    await runSlot(state, slot, { ...deps, now: () => nowMs });
    return true;
  }
  return false;
}

export const describeNext = (nowMs) => new Date(nextSlot(nowMs)).toISOString();

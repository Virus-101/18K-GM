// Calendrier : 07:00, 10:00, 13:00, 18:00, lundi→samedi, fuseau Africa/Casablanca (tzdata de Node/ICU, donc
// changements d'heure du Maroc inclus : UTC+1 la plupart de l'année, UTC+0 pendant le Ramadan).
export const ZONE = 'Africa/Casablanca';
export const SLOTS = [[7, 0], [10, 0], [13, 0], [18, 0]];

const fmt = new Intl.DateTimeFormat('en-US', {
  timeZone: ZONE, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', weekday: 'short',
});

export function zoneParts(ms) {
  const p = Object.fromEntries(fmt.formatToParts(new Date(ms)).map((x) => [x.type, x.value]));
  return { y: +p.year, mo: +p.month, d: +p.day, h: +p.hour, mi: +p.minute, s: +p.second, weekday: p.weekday };
}

/** Décalage (ms) du fuseau à l'instant ms : heure locale − UTC. */
function offsetAt(ms) {
  const p = zoneParts(ms);
  return Date.UTC(p.y, p.mo - 1, p.d, p.h, p.mi, p.s) - Math.floor(ms / 1000) * 1000;
}

/** Instant UTC (ms) correspondant à y-mo-d h:mi heure de Casablanca. */
export function localToInstant(y, mo, d, h, mi) {
  const guess = Date.UTC(y, mo - 1, d, h, mi);
  let t = guess - offsetAt(guess);
  t = guess - offsetAt(t); // 2e passage : stabilise autour d'un changement d'heure
  return t;
}

function slotsOfLocalDay(y, mo, d) {
  const wd = new Date(Date.UTC(y, mo - 1, d)).getUTCDay(); // 0 = dimanche (calendrier grégorien, indépendant du fuseau)
  if (wd === 0) return [];
  return SLOTS.map(([h, m]) => localToInstant(y, mo, d, h, m));
}

function localDays(centerMs, back, forward) {
  const c = zoneParts(centerMs);
  const base = Date.UTC(c.y, c.mo - 1, c.d);
  const out = [];
  for (let i = -back; i <= forward; i++) {
    const dt = new Date(base + i * 86400000);
    out.push([dt.getUTCFullYear(), dt.getUTCMonth() + 1, dt.getUTCDate()]);
  }
  return out;
}

export function nextSlot(nowMs) {
  for (const [y, mo, d] of localDays(nowMs, 0, 8)) {
    const s = slotsOfLocalDay(y, mo, d).find((t) => t > nowMs);
    if (s !== undefined) return s;
  }
  throw new Error('aucun créneau');
}

export function lastSlotAtOrBefore(nowMs) {
  for (const [y, mo, d] of localDays(nowMs, 8, 0).reverse()) {
    const s = slotsOfLocalDay(y, mo, d).filter((t) => t <= nowMs).pop();
    if (s !== undefined) return s;
  }
  throw new Error('aucun créneau');
}

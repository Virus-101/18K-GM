// Appels aux deux API + validation stricte (mêmes règles que l'application Android).
export class ApiError extends Error {
  constructor(kind, message, extra = {}) { super(message); this.kind = kind; Object.assign(this, extra); }
}

async function getJson(url, fetchImpl, timeoutMs = 15000) {
  let res;
  try {
    res = await fetchImpl(url, { headers: { accept: 'application/json' }, signal: AbortSignal.timeout(timeoutMs) });
  } catch (e) {
    throw new ApiError('network', `réseau : ${e.message}`);
  }
  if (res.status === 429) throw new ApiError('rate_limited', 'HTTP 429', { retryAfterSec: Number(res.headers.get('retry-after')) || null });
  if (!res.ok) throw new ApiError('http', `HTTP ${res.status}`);
  try { return await res.json(); } catch { throw new ApiError('invalid', 'JSON illisible'); }
}

export function parseGold(body, nowMs = Date.now()) {
  const item = Array.isArray(body?.symbols) ? body.symbols.find((s) => s?.symbol === 'XAU') : null;
  if (!item) throw new ApiError('invalid', 'symbole XAU absent');
  if (item.quote_currency !== 'USD') throw new ApiError('invalid', 'devise inattendue');
  if (item.unit !== 'troy_ounce') throw new ApiError('invalid', 'unité inattendue');
  const price = Number(item.price);
  if (typeof item.price !== 'string' || !Number.isFinite(price) || price <= 0 || price > 100000) throw new ApiError('invalid', 'prix invalide');
  const ts = Date.parse(item.computed_at);
  if (!Number.isFinite(ts)) throw new ApiError('invalid', 'computed_at invalide');
  if (ts > nowMs + 300000) throw new ApiError('invalid', 'horodatage dans le futur');
  return { price: item.price, computedAt: new Date(ts).toISOString(), isStale: item.is_stale !== false };
}

export function parseFx(body) {
  if (body?.result !== 'success') {
    if (['quota-reached', 'rate-limited'].includes(body?.['error-type'])) throw new ApiError('rate_limited', body['error-type']);
    throw new ApiError('invalid', `result=${body?.result}`);
  }
  if (body.base_code !== 'USD') throw new ApiError('invalid', 'base_code inattendu');
  const mad = Number(body.rates?.MAD);
  if (!Number.isFinite(mad) || mad <= 0 || mad > 100) throw new ApiError('invalid', 'taux MAD invalide');
  if (!Number.isInteger(body.time_last_update_unix)) throw new ApiError('invalid', 'time_last_update_unix absent');
  return { usdMad: String(body.rates.MAD), updatedUnix: body.time_last_update_unix, nextUpdateUnix: body.time_next_update_unix || 0 };
}

export const fetchGold = async (fetchImpl = fetch, base = 'https://api.goldprice.dev', nowMs = Date.now()) =>
  parseGold(await getJson(`${base}/v1/prices?symbol=XAU-USD-SPOT`, fetchImpl), nowMs);

export const fetchFx = async (fetchImpl = fetch, base = 'https://open.er-api.com') =>
  parseFx(await getJson(`${base}/v6/latest/USD`, fetchImpl));

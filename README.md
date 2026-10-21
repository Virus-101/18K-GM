# Gold Maroc 18K

Application Android (Kotlin, Compose, Room, WorkManager) + backend Node optionnel.
Prix 18K (MAD/g) = (XAU/USD × USD/MAD ÷ 31,1034768) × 0,75.

## APIs (vérifiées le 2026-10-21 par appels réels)
- GoldPrice.dev `GET https://api.goldprice.dev/v1/prices?symbol=XAU-USD-SPOT` : `symbols[0] = {symbol:"XAU", quote_currency:"USD", unit:"troy_ounce", price:"<string>", is_stale, computed_at}`.
  Sans clé : cours **retardé de 15 min**, 10 req/min et 100 req/h par IP. Aucun champ de retard dans la réponse : le délai de 15 min vient de la documentation, `computed_at` est affiché tel quel.
- ExchangeRate-API `GET https://open.er-api.com/v6/latest/USD` : `rates.MAD`, `time_last_update_unix`, `time_next_update_unix`. Mise à jour quotidienne, 429 puis 20 min de blocage si abus. **Attribution obligatoire, redistribution interdite.**

## Compilation
Prérequis : JDK 17, Android SDK 35 (`local.properties` : `sdk.dir=...`).
    ./gradlew testDebugUnitTest      # 52 tests JVM
    ./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
Backend optionnel : `./gradlew assembleDebug -PgoldBackendUrl=https://votre-serveur -PgoldBackendToken=...`
Release : configurer une clé de signature (non fournie).

## Backend (`backend/`)
    cd backend && npm test && BACKEND_TOKEN=secret PORT=8080 node server.mjs
Tâche à 07/10/13/18 h Casablanca, lundi-samedi (tzdata, donc heure d'été/Ramadan inclus), reprises max 2 (2 puis 5 min), rattrapage ≤ 6 h après redémarrage, état dans `data/state.json`, `GET /v1/latest` (Bearer), `GET /healthz`. À placer derrière HTTPS.

## Maintenance
- Calendrier : `domain/RefreshSchedule.kt` et `backend/lib/schedule.mjs`.
- Parseurs stricts : `data/Api.kt`, `backend/lib/fetchers.mjs`. Si un fournisseur change son format, l'app affiche « réponse invalide » et garde les dernières valeurs valides.
- Clé GoldPrice (plus de quota) : à mettre côté backend uniquement, jamais dans l'APK.

## Limites connues
WorkManager ne garantit pas l'heure exacte (Doze). Historique 1 an = uniquement les points enregistrés par l'app. Pas de tests instrumentés (Room réel, UI, reboot). Le backend n'est pas déployé. Vérifier avec les fournisseurs l'usage commercial/redistribution avant publication.

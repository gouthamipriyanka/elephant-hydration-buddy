# Setup guide: dev → qa → uat → prod

Each environment gets its **own** WHOOP app registration (redirect URI), Firebase project, backend deployment, encryption key and APK flavor. Never share secrets across environments.

## 1. WHOOP developer app (per environment)

1. Sign in at the WHOOP Developer Dashboard (developer.whoop.com) and create an app.
2. **Scopes:** `read:workout`, `read:sleep`, `read:recovery`, `read:body_measurement`, `offline`. Nothing else.
3. **Redirect URI:** `https://<your-backend>/whoop/callback`
4. **Webhook URL (v2):** `https://<your-backend>/webhooks/whoop`
5. Store the client ID and secret in your secret manager. **Never commit them.**

> Verify in WHOOP's current docs: the webhook signature headers, the v2 endpoint paths, and the revoke endpoint. See [LIMITATIONS.md](LIMITATIONS.md).

## 2. Firebase (optional, for instant updates after workouts)

Without Firebase the app still works fully: reminders are local and the goal refreshes every few hours.

1. Create a Firebase project per environment and add an Android app (`app.ellie.hydration`, with suffix `.qa` or `.uat` per flavor).
2. Put the **public** client identifiers in `~/.gradle/gradle.properties` (or CI variables):
   ```
   ellie.firebase.appId=...
   ellie.firebase.apiKey=...
   ellie.firebase.projectId=...
   ellie.firebase.senderId=...
   ```
3. Restrict the API key to the Android app (package name and SHA-256 cert) in Google Cloud Console.
4. Create a service account with **only** the *Firebase Cloud Messaging API Admin* role. Its JSON goes to the backend as the secret `FCM_CREDENTIALS_JSON`.

## 3. Backend deploy (example: Fly.io, single instance + volume)

Generate an encryption key:

```bash
cd backend && python -m app.cli genkey
```

Create the per-environment config, then set its secrets:

```bash
cp infra/fly.toml.example infra/fly.prod.toml
```

```bash
fly secrets set WHOOP_CLIENT_ID=... WHOOP_CLIENT_SECRET=... TOKEN_ENCRYPTION_KEY=... FCM_PROJECT_ID=... FCM_CREDENTIALS_JSON="$(cat sa.json)" -c infra/fly.prod.toml
```

Deploy:

```bash
fly deploy -c infra/fly.prod.toml
```

Also set `FORWARDED_ALLOW_IPS` to your platform's proxy range, so rate limiting sees real client IPs.

## 4. Build and install the app

Set `ellie.backend.prod` and `ellie.fromName` (shown on the welcome screen) in `~/.gradle/gradle.properties`.

**UAT first:** install the `uat` build on *your own* phone and live with it for a few days.

Release signing comes only from environment variables:

```bash
export ELLIE_KEYSTORE_PATH=/secure/ellie.jks ELLIE_KEYSTORE_PASSWORD=... ELLIE_KEY_ALIAS=ellie ELLIE_KEY_PASSWORD=...
```

```bash
cd android && gradle assembleProdRelease
```

**Recommended distribution:** Google Play Console → Internal testing. Play Protect scanning and signature-checked updates come with it.

**If sideloading:** share the SHA-256 checksum of the APK. Once it's installed, turn **"Install unknown apps" back off** for whichever app you used.

## 5. The surprise 🎁

1. Install the app on his phone (or have him install it from the internal-testing link).
2. Generate a pairing code, which is valid for 10 minutes:
   ```bash
   fly ssh console -c infra/fly.prod.toml -C "python -m app.cli pair"
   ```
3. He opens the app and sees the welcome letter from you. **He** chooses what to allow: notifications, WHOOP, and location for the bottle reminder. Nothing is read before he opts in.
4. For the bottle reminder, he taps "I'm at home now" while he's at home.
5. Make it reliable: Settings → Apps → Ellie → Battery → **Unrestricted**. Samsung, Xiaomi, OnePlus and some other brands kill background apps aggressively. Search "dontkillmyapp <brand>" for brand-specific steps.

## 6. Turning it off / deleting data

In-app: Settings → *Delete all my data*. This wipes the phone, deletes the server data and asks WHOOP to revoke access. He can also revoke access from the WHOOP app's connected-apps settings.

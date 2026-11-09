# Security & threat model

**Goal:** the app must never be a way to attack his phone, and his health and location data must not leak.

## What we protect

| Asset | Where it lives | Protection |
|---|---|---|
| WHOOP OAuth tokens | Backend DB | Fernet (AES-128-CBC + HMAC) with a key from the secret manager; minimum read-only scopes |
| Water log | Phone only | SQLCipher-encrypted DB; passphrase wrapped by an Android Keystore AES-256-GCM key |
| Home location | Phone only | Keystore-encrypted; geofence evaluated on-device; **never uploaded** |
| Device token | Phone (encrypted) + backend (**SHA-256 hash only**) | Revocable; never logged |
| FCM token | Backend | Fernet-encrypted |

The backend **never receives** location or the intake log (data minimisation).

## Threats and controls

| Threat | Control |
|---|---|
| Phishing link pushed to his lock screen through a compromised backend or message file | Messages are plain text, ≤ 200 chars and **link-free**, validated on the server **and** again on the phone. Push payloads are data-only (`{"type":"sync"}`) and are never displayed. |
| Fake WHOOP login page | The app opens a WHOOP login URL only if it is `https://api.prod.whoop.com` (pinned host in `BuildConfig`), using Custom Tabs (a real browser, not a WebView). |
| OAuth CSRF / code injection | Single-use, 10-minute, hashed `state` bound to the paired device. The callback page never reflects input. |
| Forged or replayed webhooks | HMAC-SHA256 signature check with constant-time compare, ±5 min timestamp window, event-id dedupe, 64 KB body cap, and a user-id match. |
| Other apps on the phone triggering our components | All receivers and services `exported=false`, except `BootReceiver`, which only accepts system-protected actions. Explicit-component PendingIntents. |
| Network interception | HTTPS only; **user-installed CAs are not trusted**; the client never follows redirects with its token; responses capped at 256 KB. |
| Backup / device-transfer leakage | `allowBackup=false`; data-extraction rules exclude everything. |
| Lock-screen snooping | Notifications are `VISIBILITY_PRIVATE`, with a generic public version. |
| Pairing-code brute force | Codes are 96-bit random, single-use, 10-min TTL, stored hashed; the pairing endpoint is limited to 5 requests/min/IP. |
| Malicious dependency / leaked secret | Hash-locked Python deps, pinned Gradle versions, Dependabot, CodeQL, gitleaks in CI, `.gitignore` for keystores and service-account files. |
| Container breakout impact | Non-root user (uid 10001), slim base image, only `/data` writable. |
| Excessive permissions | Only notifications, boot, internet, and (optional) location. No contacts, SMS, storage, camera or mic. |

### Documented exceptions (Android platform requirements)

- **Two PendingIntents are `FLAG_MUTABLE`:**
  - the RemoteInput "Log amount" action, because the system must attach the typed text;
  - the geofence intent, because Play services must attach the event.

  Both use explicit components, our package, and non-exported receivers. Received values are strictly validated: 1–2000 ml, and only the EXIT transition is honoured.

## Residual risks (stated honestly)

- **A compromised phone OS, Google Play services/FCM, or WHOOP infrastructure** is outside this app's control.
- **Sideloaded APK:** security rests on you keeping the release signing key safe, offline, and out of git and CI logs. Prefer the Play Console *internal testing* track, which adds Play Protect scanning and signature-verified updates.
- **Single-instance backend:** the rate limiter and SQLite store are in-process. Scaling out requires Postgres plus a shared limiter.
- **No certificate pinning (deliberate):** a surprise certificate rotation would break the app. If you add pinning, pin the intermediate CA plus a backup pin.
- **Not yet done (recommended before prod):**
  - pin GitHub Actions and the Docker base image by SHA digest;
  - enable Gradle dependency verification (`gradle --write-verification-metadata sha256`; needs a machine with the Android SDK);
  - optional Play Integrity attestation for the pairing call.

## Reporting

Found a problem? Don't open a public issue. Contact the repo owner privately.

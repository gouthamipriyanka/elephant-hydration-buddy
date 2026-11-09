# Limitations & things to verify before production

These were written down honestly at build time (Nov 2026). Treat each ⚠️ item as a checklist entry.

## Verify against primary sources

| ⚠️ | Item | Why |
|---|---|---|
| ⚠️ | **WHOOP v2 endpoints and fields:**<br>• `/v2/user/measurement/body` → `weight_kilogram`<br>• `/v2/activity/workout` → `records[].start/end/score.strain`, `next_token`<br>• `/v2/user/profile/basic`<br>• `DELETE /v2/user/access` | developer.whoop.com was unreachable from the build environment. The code follows WHOOP's documented v2 API as summarised by third-party sources. |
| ⚠️ | **WHOOP webhook signature:** `X-WHOOP-Signature` = base64(HMAC-SHA256(secret, timestamp + body)), plus the `X-WHOOP-Signature-Timestamp` header | Same reason. If wrong, every webhook gets a 401 (fails *closed*, which is safe), and the goal still refreshes by polling. |
| ⚠️ | **Android library versions** (AGP 8.7.3, Compose BOM 2024.12.01, Room 2.6.1, Play Services Location 21.3.0, Firebase Messaging 24.1.0, …) | Google's Maven repo was unreachable from the build environment, so these are known-stable versions. CI resolves them; Dependabot proposes updates. |
| ⚠️ | **Android 14+ dismissal behaviour of ongoing notifications** | Based on platform behaviour as I understand it. Re-posting covers it either way. |
| ⚠️ | **Health facts** in `messages.yaml` | Paraphrased from CDC, Mayo Clinic, NHS and MSU Extension pages, as seen through web search on 2026-11-09. Re-check the pages periodically. |
| ⚠️ | **Goal formula:** ~35 ml/kg plus ~500 ml per workout hour, clamped to 1.5–4.5 L | A common rule of thumb, not a clinical standard. Check with a doctor, especially with kidney or heart conditions. |

## Known functional limitations

- **Android only.** An iPhone version would need a separate app (or a Flutter rewrite).
- **The APK was not compiled in the build sandbox:** the Android SDK host was blocked there. CI on GitHub (`.github/workflows/ci.yml`) compiles it, runs unit tests and lint, and uploads a QA APK. The pure-Kotlin logic tests *were* run locally (5/5 passing).
- **No instrumented UI tests yet.** Notification flows need a manual check on a device; see the checklist below.
- **Night-shift schedules** (sleep time after midnight) aren't supported: `sleep` must come after `wake` on the same day.
- **Reminder timing** is approximate: WorkManager's minimum period is 15 min, and Doze can delay work. Setting the battery mode to "Unrestricted" helps.
- **Geofence exits** can be delayed several minutes and are less reliable with location services off. GPS jitter is handled with a 30-min cooldown.
- **"Sleep" uses fixed wake/sleep times** from settings, not live WHOOP sleep data.

## Manual QA checklist (UAT, on a real phone)

- [ ] A reminder appears. Swiping it away brings it back silently. After three quick swipes, it waits 15 min.
- [ ] Typing `300` in the notification → happy Ellie, "Kudos", progress updated.
- [ ] Typing `lots` → a friendly "didn't understand" message; nothing is logged.
- [ ] `+250` / `+500` quick buttons work. Undo removes the last entry.
- [ ] Reaching the goal → "Goal reached!", and no more reminders that day.
- [ ] No reminders before wake time or after sleep time.
- [ ] Walking ~300 m from home → bottle reminder, at most once per 30 min.
- [ ] The lock screen shows only "Ellie has a little message for you".
- [ ] Reboot the phone → reminders and the geofence still work.
- [ ] "Delete all my data" → the app returns to the welcome screen, and the server's `/v1/whoop/status` returns 401.

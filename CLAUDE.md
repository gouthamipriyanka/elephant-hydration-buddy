# Project context (read this first; keeps future sessions cheap)

Ellie 🐘: an Android hydration-reminder app the owner built as a gift for her husband (Samsung phone).
The owner uses an iPhone and has no Android device or usable laptop, so **she can't run anything locally.**
All builds, emulator checks and releases run in GitHub Actions.

## Status (2026-11-09)
- **Phase 1 is shipped:** standalone app, no backend, no WHOOP, fixed 2.6 L/day goal. Release #2 (commit ef92676) was sent to him for testing.
- **Testing:** he follows `docs/INSTALL_ON_SAMSUNG.md` (parts A–E). The owner will come back with screenshots or bugs.
- **Phase 2 (WHOOP) is not started.** The backend in `backend/` is written and tested against a mocked WHOOP only. Still needed: WHOOP dev app, hosting, verifying WHOOP API fields (see `docs/LIMITATIONS.md`).

## Where things are
- App code: `android/app/src/main/java/app/ellie/hydration/`
  - `ui/MainActivity.kt`: welcome/setup/home/settings screens
  - `notifications/`: reminders, kudos, re-post on swipe
  - `logic/Hydration.kt`: pure logic, unit-tested
- Messages: `backend/app/messages.yaml`. After editing, run `cd backend && python -m app.cli export-messages` (CI checks they're in sync).
- Art: `art/generate_elephants.py`, which generates the SVGs plus the Android drawables (CI checks they're up to date).

## Workflows (all in .github/workflows)
- `ci.yml`: tests, lint, APK builds, secret scan.
- `ui-tour.yml`: real emulator screenshots, pushed to the `screenshots` branch. Trigger it with `gh api -X POST repos/gouthamipriyanka/elephant-hydration-buddy/actions/workflows/ui-tour.yml/dispatches -f ref=main`, then read them with `git fetch origin screenshots`.
- `release.yml`: the owner runs it manually and gets a signed APK. The signing key is encrypted at `android/signing/ellie.jks.gpg` (secret `ELLIE_SIGNING_PASSPHRASE`). **Never regenerate the key**: that would break updates.
- Artifact downloads are blocked from Claude sessions, so the owner downloads APKs from the run page herself.

## Owner preferences
- Production-grade, honest about uncertainty, flags tradeoffs.
- Messages to him: loving and sweet. The welcome note starts with "Meet Ellie!" and is signed "— Your loving wife".

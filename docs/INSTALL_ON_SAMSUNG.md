# Installing & testing Ellie 🐘 on a Samsung phone

About **15 minutes** to install, plus a **2-day "live with it" test**. Samsung menu names vary slightly between One UI versions; if a menu isn't exactly where described, use the 🔍 search in Settings.

---

## Part A: Install (5 min)

1. **Check the file.** You'll receive `ellie-<number>.apk` and a short code called a SHA-256 checksum. *(Optional but recommended: install "Hash Droid" or a similar checksum app from the Play Store and compare. If the code doesn't match, **don't install**.)*
2. Tap the APK file, for example in **My Files → Downloads**.
3. Samsung will say *"For your security, your phone is not allowed to install unknown apps from this source."* Tap **Settings** → turn on **Allow from this source** → go back → **Install**.
4. If **Play Protect** shows a warning about an unrecognised app, tap **More details → Install anyway**. (Play Protect doesn't recognise private apps that aren't on the Play Store; this is expected.)
5. 🔒 **Important:** go back to Settings → Apps → *(the app you installed from, e.g. My Files or Gmail)* → **Install unknown apps** → turn it **OFF** again.

## Part B: Set up Ellie (3 min)

1. Open **Ellie 🐘** and read the welcome note 💙
2. **Allow notifications.** This is essential; Ellie can't do anything without it.
3. **Bottle reminder (optional):**
   - Turn the switch **On**.
   - Allow location **"While using the app"**.
   - On the next screen, choose **"Allow all the time"**. This is needed to notice you leaving home.
   - **While you're at home**, tap **"I'm at home now: save as home"**.
4. Tap **Done**.

## Part C: Stop Samsung from silencing Ellie (3 min). Don't skip!

Samsung phones aggressively put background apps to sleep. Without these steps, reminders may stop after a day or two.

1. Settings → **Apps → Ellie → Battery** → select **Unrestricted**.
2. Settings → **Battery** (or *Battery and device care → Battery*) → **Background usage limits**:
   - Make sure Ellie is **not** in *Sleeping apps* or *Deep sleeping apps*.
   - Add Ellie to **Never sleeping apps**.
3. Still in Background usage limits: if **"Put unused apps to sleep"** is on, Ellie is protected by step 2, so no change is needed.
4. Settings → **Apps → Ellie → Notifications**: make sure all three categories are on: *Water reminders*, *Kudos*, *Bottle reminder*.

## Part D: Quick test (5 min, right after setup)

In Ellie, tap **Settings → "Send a test reminder"** and then check:

| # | Do this | You should see |
|---|---|---|
| 1 | Look at the notification | Sad thirsty Ellie, a sweet message, and buttons: **Log amount**, **250 ml**, **500 ml** |
| 2 | **Swipe it away** | It comes back quietly within a second or two |
| 3 | Tap **Log amount**, type `300`, send | Happy Ellie: **"Kudos! 🎉"** with a progress bar |
| 4 | Send another test reminder → **Log amount** → type `lots` | "Ellie didn't quite understand…" (nothing is logged) |
| 5 | Tap **250 ml** on a reminder | Kudos, and the in-app total goes up by 250 |
| 6 | Open the app → **Undo last entry** | The total goes back down |
| 7 | Lock the phone and send a test reminder | The lock screen shows only "Ellie has a little message for you 🐘" |
| 8 | **Restart the phone**, then send a test reminder | Still works |

## Part E: Live with it for 2 days

- [ ] Reminders arrive during the day when you're behind (roughly every 60–90 min), and **none before your wake time or after your sleep time**. Adjust these in Ellie → Settings.
- [ ] Reaching your goal shows **"Goal reached! 🏆"**, and reminders stop for the day.
- [ ] Walking or driving **~300 m or more from home** shows the **bottle reminder**. It can take a few minutes, and it fires at most once every 30 min.
- [ ] Reminders still arrive on day 2. If they stop, redo **Part C**.

**Something wrong?** Take a screenshot and note the time and what you were doing, then send it to your wife, who will pass it on so it gets fixed. 🐘💙

## Turning it off

Ellie → Settings → **Delete all my data** wipes everything on the phone. Then uninstall the app as usual.

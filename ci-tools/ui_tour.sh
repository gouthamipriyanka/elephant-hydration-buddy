#!/usr/bin/env bash
# Drives the REAL app on an Android emulator and saves screenshots to ./shots.
# Uses only adb + uiautomator (no test framework). Runs inside android-emulator-runner.
set -uo pipefail
PKG=app.ellie.hydration.qa
APK=android/app/build/outputs/apk/qa/debug/app-qa-debug.apk
OUT=shots
mkdir -p "$OUT"
n=0

shot() { n=$((n+1)); local f; f=$(printf '%s/%02d_%s.png' "$OUT" "$n" "$1"); adb exec-out screencap -p > "$f"; echo "saved $f"; }

# Find a node whose text/content-desc contains $1 and tap its centre. Scrolls down to find it.
tap_text() {
  local want="$1" tries=0
  while [ $tries -lt 6 ]; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
    local xy
    xy=$(python3 - "$want" <<'PY'
import re, sys, xml.etree.ElementTree as ET
want = sys.argv[1]
try:
    root = ET.parse("/tmp/ui.xml").getroot()
except Exception:
    sys.exit(0)
for node in root.iter("node"):
    if want in node.get("text", "") or want in node.get("content-desc", ""):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
    if [ -n "$xy" ]; then adb shell input tap $xy; sleep 2; return 0; fi
    adb shell input swipe 540 1700 540 700 300; sleep 1
    tries=$((tries+1))
  done
  echo "WARN: could not find '$want'"; return 1
}

open_shade()  { adb shell cmd statusbar expand-notifications; sleep 2; }
hide_kb()     { adb shell input keyevent 111; sleep 1; }
# Expand the top notification so the big elephant picture shows.
expand_top()  { tap_text_noscroll "Expand" || true; sleep 1; }
tap_text_noscroll() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
  local xy; xy=$(python3 - "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
try: root = ET.parse("/tmp/ui.xml").getroot()
except Exception: sys.exit(0)
for n in root.iter("node"):
    if sys.argv[1] in n.get("text","") or sys.argv[1] in n.get("content-desc",""):
        x1,y1,x2,y2 = map(int, re.findall(r"\d+", n.get("bounds"))); print((x1+x2)//2, (y1+y2)//2); break
PY
); [ -n "$xy" ] && { adb shell input tap $xy; sleep 1; return 0; }; return 1; }
count_reminders() { adb shell dumpsys notification --noredact | grep -c "id=1001" ; }
close_shade() { adb shell cmd statusbar collapse; sleep 1; }

adb install -r "$APK"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb shell am start -n $PKG/app.ellie.hydration.ui.MainActivity
sleep 5
shot welcome

tap_text "Let's set Ellie up"
shot setup
tap_text "Done"
sleep 2
shot home_start

tap_text "+250 ml"
sleep 2
shot home_after_250
open_shade; expand_top
shot kudos_notification_expanded
close_shade

tap_text "Settings"
shot settings
tap_text "Send a test reminder"
sleep 2
open_shade; expand_top
shot reminder_notification_expanded
tap_text_noscroll "Log amount" && { adb shell input text "300"; sleep 1; shot typing_amount; adb shell input keyevent 66; sleep 3; shot after_typed_300; }
close_shade; hide_kb

# Swipe-away test: post reminder, dismiss it like a user would, confirm it is re-posted.
tap_text "Send a test reminder"; sleep 2
echo "reminders before swipe: $(count_reminders)"
open_shade
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
Y=$(python3 -c "
import re,xml.etree.ElementTree as ET
r=ET.parse('/tmp/ui.xml').getroot()
for n in r.iter('node'):
    t=n.get('text','')
    if 'Ellie is thirsty' in t or 'Water time' in t:
        x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds'))); print((y1+y2)//2); break
")
echo "reminder row y=$Y"
[ -n "$Y" ] && adb shell input swipe 200 $Y 1050 $Y 150
sleep 4
shot after_swipe_away_should_be_back
echo "reminders after swipe: $(count_reminders)"
close_shade

adb shell am start -n $PKG/app.ellie.hydration.ui.MainActivity
tap_text "Back"
sleep 1
shot home_end
echo "done: $n screenshots"

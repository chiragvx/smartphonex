#!/usr/bin/env bash
# The QR flow on a real Android system, from the link a scanned code opens through to connecting.
# Needs an emulator: Xiaomi phones block the screen taps this uses.
# Usage: tools/qr-e2e.sh <adb-serial> <relay-url-from-pc> <relay-url-from-device> <user> <password>
cd "$(dirname "$0")/.." || exit 1
export MSYS_NO_PATHCONV=1 # Git Bash would otherwise rewrite device paths such as /sdcard.
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
S=$1; PC=$2; DEV=$3; U=$4; P=$5
A() { "$ADB" -s "$S" "$@"; }
pass=0; fail=0
check() { if [ "$2" = "1" ]; then pass=$((pass+1)); echo "PASS  $1"; else fail=$((fail+1)); echo "FAIL  $1  [$3]"; fi; }

new_link() { # Prints the app link found on the page the QR code opens, rewritten for the device.
  node --input-type=module -e "
    import { login } from './tools/remote-lib.mjs';
    const [pc, dev, u, p] = process.argv.slice(1);
    const cookie = await login(pc, u, p);
    const body = await (await fetch(pc + '/enroll/new', { method: 'POST', headers: { cookie, origin: pc } })).json();
    const html = await (await fetch(body.url)).text();
    const link = html.match(/href=\"([^\"]+)\"/)[1].replaceAll('&#38;', '&');
    const parsed = new URL(link);
    console.log('smartphonex://enroll?relay=' + encodeURIComponent(dev) + '&code=' + parsed.searchParams.get('code'));
  " "$PC" "$DEV" "$U" "$P"
}
open() { A shell am start -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d "'$1'" >/dev/null 2>&1; sleep 3; }
screen() {
  A shell rm -f /sdcard/ui.xml >/dev/null 2>&1
  A shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  local out
  out=$(A shell cat /sdcard/ui.xml 2>/dev/null)
  # An empty read must fail the test run, or "not on screen" checks would pass without looking.
  case "$out" in *"<hierarchy"*) printf '%s' "$out" ;; *) echo "SCREEN READ FAILED" >&2; exit 9 ;; esac
}
tap() { # Taps the button with this exact label.
  local xy
  xy=$(screen | python -c "
import re,sys
m=re.search(r'text=\"$1\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', sys.stdin.read())
print((int(m[1])+int(m[3]))//2,(int(m[2])+int(m[4]))//2) if m else print('')")
  [ -n "$xy" ] && A shell input tap $xy; sleep 3; [ -n "$xy" ]
}
has() { screen | grep -q "$1"; echo $?; }
ok() { [ "$1" = "0" ] && echo 1 || echo 0; }
state() { A exec-out run-as com.smartphonex.app cat files/relay.json 2>/dev/null; }

BEFORE=$(state)

L=$(new_link); open "$L"
check "Scanned link asks for confirmation and names the server" "$(ok "$(has 'Connect this vehicle')")"
tap Cancel
check "Cancel changes nothing" "$([ "$(state)" = "$BEFORE" ] && echo 1 || echo 0)"

open "smartphonex://enroll?relay=http%3A%2F%2Fevil.example&code=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
tap Connect
check "Unencrypted rogue server is refused" "$(ok "$(has 'Not connected')")" "$(screen | grep -o 'text="[^"]*"' | head -6 | tr '\n' ' ')"
tap OK
check "Refused server leaves settings unchanged" "$([ "$(state)" = "$BEFORE" ] && echo 1 || echo 0)"

# The main screen updates too often for a screen read, so count the app's windows: a dialog adds one.
windows() { A shell dumpsys window windows | grep -cE 'Window #[0-9]+.*com\.smartphonex\.app'; }
BASE=$(windows)
L0=$(new_link); open "$L0"
DIALOG=$(windows)
check "Window count can see a dialog" "$([ "$DIALOG" -gt "$BASE" ] && echo 1 || echo 0)" "base $BASE, with dialog $DIALOG"
tap Cancel

open "smartphonex://enroll?code=abc"
check "Link with no server is ignored" "$([ "$(windows)" = "$BASE" ] && echo 1 || echo 0)" "$(windows) vs $BASE"
sleep 2

open "smartphonex://other?relay=$DEV&code=abc"
check "Unrelated link does not ask anything" "$([ "$(windows)" = "$BASE" ] && echo 1 || echo 0)" "$(windows) vs $BASE"

L=$(new_link); open "$L"; tap Connect
check "Confirming links the phone" "$(ok "$(has 'text="Linked"')")" "$(screen | grep -o 'text="[^"]*"' | head -6 | tr '\n' ' ')"
tap OK
sleep 4
AFTER=$(state)
check "Settings saved" "$(echo "$AFTER" | grep -q "$(echo "$DEV" | sed 's#/#\\/#g')" && [ "$AFTER" != "$BEFORE" ] && echo 1 || echo 0)"

open "$L"; tap Connect
check "Reusing the same code is refused" "$(ok "$(has 'already used')")" "$(screen | grep -o 'text="[^"]*"' | head -6 | tr '\n' ' ')"
tap OK
check "Reused code leaves the connection settings alone" "$([ "$(state)" = "$AFTER" ] && echo 1 || echo 0)"

A shell am force-stop com.smartphonex.app
A shell am start -n com.smartphonex.app/.MainActivity >/dev/null 2>&1
sleep 8
check "Stays linked after the app restarts" "$([ "$(state)" = "$AFTER" ] && echo 1 || echo 0)"
echo "$pass passed, $fail failed"
[ "$fail" = "0" ]

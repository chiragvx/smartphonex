#!/usr/bin/env bash
# Screen-off and Doze test. Press the phone's power button when it starts. Temporarily changes the
# simulated battery/idle state and always restores it. Results come from the phone's own log.
# Usage: tools/screenoff.sh <phone-ip>
cd "$(dirname "$0")/.." || exit 1
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
IP="$1"; T=$(cat .tooling/token.txt)
restore() {
  "$ADB" shell dumpsys deviceidle unforce >/dev/null 2>&1
  "$ADB" shell dumpsys battery reset >/dev/null 2>&1
  echo "restored: battery and idle state reset"
}
trap restore EXIT
nowms() { echo $(( $(date +%s) * 1000 )); }
wake() { "$ADB" shell dumpsys power | grep -m1 -oE 'mWakefulness=[A-Za-z]+' | tr -d '\r'; }
idle() { "$ADB" shell dumpsys deviceidle | grep -m1 -oE 'mState=[A-Z_]+' | tr -d '\r'; }

echo "before: $(wake). Waiting up to 2 minutes for someone to press the power button."
for i in $(seq 1 60); do [ "$(wake)" != "mWakefulness=Awake" ] && break; sleep 2; done
echo "screen state: $(wake)"
if [ "$(wake)" = "mWakefulness=Awake" ]; then echo "SCREEN DID NOT TURN OFF: results would be invalid, stopping"; exit 3; fi

S1=$(nowms)
node tools/soak.mjs "$T" "$IP:8080" "A. screen off, app backgrounded, charging" 90
E1=$(nowms); echo "   state after: $(wake)"

S2=$(nowms)
node tools/soak.mjs "$T" "$IP:8080" "B. screen off + full CPU load" 25 --cpu
E2=$(nowms)
sleep 6

"$ADB" shell dumpsys battery unplug >/dev/null 2>&1
"$ADB" shell dumpsys deviceidle force-idle >/dev/null 2>&1
echo "idle state: $(idle)"
S3=$(nowms)
timeout 80 node tools/soak.mjs "$T" "$IP:8080" "C. screen off + forced Doze, simulated unplugged" 60 || echo "C. link test did not complete: the link was lost in Doze"
E3=$(nowms); echo "   state after: $(wake), $(idle)"
"$ADB" shell dumpsys deviceidle unforce >/dev/null 2>&1
"$ADB" shell dumpsys battery reset >/dev/null 2>&1
sleep 5

echo; echo "--- from the phone's own log"
for i in 1 2 3 4 5; do
  curl -s --max-time 15 "http://$IP:8080/export/telemetry.csv?token=$T" > .tooling/telemetry.csv && [ -s .tooling/telemetry.csv ] && break
  sleep 5
done
python tools/gaps.py .tooling/telemetry.csv "A" "$S1" "$E1"
python tools/gaps.py .tooling/telemetry.csv "B" "$S2" "$E2"
python tools/gaps.py .tooling/telemetry.csv "C" "$S3" "$E3"
echo "app pid: $("$ADB" shell pidof com.smartphonex.app)"

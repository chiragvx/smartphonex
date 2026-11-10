# SmartPhoneX plan (v1.1)

Updates [PRD-1.0.txt](PRD-1.0.txt), which is kept as the original. Where they differ, this file wins.
This is an experiment: losing a car or a phone is acceptable.

## Decisions

| Topic | Decision |
|---|---|
| Extra hardware | One USB-UART adapter, nothing else: no microcontroller or other added components. Still identify the car's electronics before wiring. |
| Test phone | Redmi Note 13 Pro 5G |
| Where everything runs | On the phone: control loop, simulator, ground-station web server and MCP server. A PC or browser is optional. |
| Failsafe | Manual commands time out after 500 ms: brake to zero, enter FAILSAFE, stay latched until a human re-arms. |
| Control authority | Stop always wins, from anyone. A human driving overrides the AI at once. The AI cannot undo a human stop or clear a failsafe. |
| AI commands | Every throttle, steering or speed request carries a time limit (maximum 10 s by default) and expires to a stop. |
| Obstacle handling | A local guard on the phone holds the car in AUTO, independent of AI latency. |
| MCP tool names | `vehicle_get_status` style. The PRD's dotted names are not valid for the Claude API. |
| Auth | Access token from Phase 1. Browsers get it by entering a 6-digit code shown on the phone, with lockouts on wrong codes. This is for the local network only: do not expose `/pair` through the Phase 2 relay as is. Full backend auth arrives in Phase 2. |

## Accepted risk

With USB-UART only there is no independent hardware watchdog. If the app or phone hangs, nothing
off the phone can stop the car. Test Phase 3 with the wheels off the ground first and keep a way
to cut vehicle power.

## Phase 3 output options (USB-UART only)

A USB-UART adapter outputs low-current logic signals: TX, plus usually RTS and DTR. It cannot power
motors, so the car's own driver stage must remain. Choose after inspecting the car:

1. **Serial-input receiver or ESC** (e.g. CRSF or iBus): the cleanest fit, and such receivers have their own failsafe. Hobby-grade cars only.
2. **Toy car H-bridge logic inputs:** RTS/DTR for direction and on/off steering; TX byte patterns as paced PWM for throttle. Bang-bang steering only.
3. **Standard servo PWM from TX:** likely poor. A 1-2 ms pulse leaves little room for timing error, so treat it as an experiment.

Checks for any option: match logic voltage (3.3 V or 5 V), share ground with the car, and never
power motors from the adapter. TX idles HIGH, so it must not be wired so that idle means throttle on.
The adapter occupies the phone's USB port, so development switches to wireless ADB.

## Phase 1 status

Running on the phone: `VehicleAdapter`, simulator, controller, profiles, logs, ground station, MCP server.

Verified on the Redmi Note 13 Pro 5G: `tools/e2e.mjs` 52/52 over USB port forwarding on build 4, plus
23 unit tests on the PC. With the screen on: control loop 50 Hz, worst step about 25 ms including under CPU
load, about 11 ms median round trip over Wi-Fi.

A real Claude agent, given the PRD's request in plain language, armed, drove, turned, held 1.5 m/s,
reported status and stopped through MCP. Confirmed from the phone's log, with no collision or failsafe.

Screen off (phone asleep, confirmed from its power state at the start of the run; about 3.5 minutes):
- Control loop unaffected: 50 Hz, no gap over 107 ms in the phone's own log, worst step 26.9 ms.
- Link degraded: one WebSocket drop, and round trips of about 50-60 ms typical, 340 ms p95 and up
  to 528 ms, against about 11 ms with the screen on. Peaks can exceed the 500 ms manual timeout.
- USB ADB goes offline while asleep. The cause is unconfirmed.
- Decision: keep the screen on while the app is open (installed in build 4).

Not yet verified:
- Doze. The forced-idle step never ran because ADB was offline.
- Why the link degrades when asleep. Wi-Fi power saving is only a guess.
- Ground station driven by a person in a real browser. It has only been loaded headless.
- The "Drive from this screen" box unticking on stop.
- Sessions longer than a few minutes, battery drain and heat.

## Installing on this phone

HyperOS blocks `adb install` without a SIM ("Install via USB"). Push the APK to `/sdcard/Download`
and install it from the phone's file manager. Debug builds still allow reading the token through
`adb exec-out run-as com.smartphonex.app cat files/token.txt`.

## Phase 2 status

Built: outgoing relay connection from the phone, relay with sign-in, viewer and operator roles, AI
keys, rate limits and an audit log (`relay/`), network and latency measurement tools (`tools/`).

Verified on the real phone over real 4G LTE (full signal) and public HTTPS, on build 5:
- `tools/relay-e2e.mjs` 24/24, and a real Claude agent ran the square mission.
- One 150 s drive run: round trip 104 ms typical, 182 ms p95, 1292 ms worst, 0% ICMP loss, 0 of 74 AI
  calls failed, control loop at 50 Hz, about 97 kbps up and 44 kbps down.
- One false failsafe in that run, at full signal. One run isn't enough to change the 500 ms timeout.
- A 300 s run on build 6 measured 278 ms typical and 608 ms worst, at the same signal. It can't judge
  false failsafes: the app was restarted at 7:52 PM because the APK was reinstalled during the run
  (Android's exit log says PACKAGE UPDATED), so it wasn't a crash or Android killing it.
  An app that dies while driving still means no failsafe on a real vehicle.

QR setup (build 6), verified on an Android 16 emulator only: 12 flow checks plus relay and browser
tests. A real camera scan, the Xiaomi browser and scanner hand-off, and HTTPS on this flow are untested.

Not yet verified, and needed to finish Phase 2 (see [PHASE2.md](PHASE2.md)):
- 5G, weak signal, handovers, Wi-Fi to cellular, and airplane-mode recovery on the real phone.
- The phone's on-screen controls with no network. Over USB, with no SIM and Wi-Fi off, `tools/e2e.mjs`
  passed 52/52 on build 6 at 50 Hz.
- Longer drive runs, to decide the manual timeout.

Relay hosting is temporary: this PC plus a `cloudflared` tunnel. A permanent host is still to be chosen.

## Next

- Phase 2: run [PHASE2.md](PHASE2.md) with the SIM.
- See "Any vehicle, any device" below.
- Flight controller: see [FLIGHT_CONTROLLER.md](FLIGHT_CONTROLLER.md).
- Any robotic device, set up with any LLM: see [ANY_DEVICE.md](ANY_DEVICE.md).
- Universal ground station: Phase 1 built, walk test outstanding. See [GROUND_STATION.md](GROUND_STATION.md), section 14.
- Phases from here to 1.0, including customization: see [ROADMAP.md](../../ROADMAP.md).

## Saved for later

Not built yet, and parked on purpose:
- Sandboxed code plugins
- Bluetooth and CAN transports
- On-phone LLM and object recognition (vision is simple line tracking today)
- Inverse kinematics for arms
- Reading servo feedback
- A public device pack repository

## Any vehicle, any device

There are no more phases. This is built as one system, and hardware plugs into it when it arrives.

Built:
- **Sensors:** discovered from the device, with nothing assumed. Each has on/off, rate and priority, and
  a data budget drops low priority first. Commands and basic telemetry sit outside the budget.
- **Camera:** any camera the device lists, chosen from the ground station, with resolution, frame rate,
  quality and priority, on its own connection. It runs only while someone is watching.
- **Vehicle types:** car, skid-steer, boat, twin-thruster boat, plane, flying wing and custom. A type is
  only a list of outputs with mixing and failsafe values, so new ones need no code.
- **Output preview:** with no hardware, the phone and ground station show every output's pulse width and
  the exact CRSF or iBus bytes a serial adapter would send.

Rules:
- Camera and location start off. Camera comes back off after every restart. The phone shows when
  either is live. Viewers never get them, and AI agents only if an operator grants it. No microphone.
- Aircraft: failsafe means motor off and surfaces neutral, which is a glide and not a stop. AI can't
  arm them and missions are off, because there is no stabilization. Flying one is not supported yet.
- Sensors don't feed safety decisions. The phone's IMU and GPS aren't wheel speed.

Tested on an Android TV emulator and relay on this PC: unit, relay, device, regression, browser and QR
tests, including one emulated real camera. Not tested on real hardware.

To verify in the live test on a real phone:
- Hardware sensors. The emulator has none, so discovery, values and budget priority are unproven.
- Real cameras, image rotation, and switching between them.
- Location, and all of this over 4G with HTTPS.
- A 30-minute run with everything on: data use, heat, battery, and any effect on command timing.
- Whether Android kills the service in the background. It hasn't been seen yet.

Still open:
- 5G, weak signal, handovers, Wi-Fi to cellular, and airplane mode ([PHASE2.md](PHASE2.md)).
- A real USB-UART adapter class, when there is a car and adapter to test it with.

Known limits on real vehicles:
- **No obstacle guard:** the simulator's range sensor doesn't exist on real vehicles, so it does nothing.
  AI driving needs short time limits and a human watching.
- **No closed-loop speed:** without a wheel sensor `vehicle_set_speed` is refused, and missions run on
  open-loop power.
- **USB port:** the adapter occupies it, so development moves to wireless ADB and the phone runs on battery.
- **Hangs and crashes:** with no hardware watchdog, a frozen phone can't stop a vehicle. Keep a way to cut power.

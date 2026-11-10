# Development

How to build, run and test each part. For how the parts fit together, read [ARCHITECTURE.md](ARCHITECTURE.md) first.
For what makes a good contribution, see [CONTRIBUTING.md](../CONTRIBUTING.md).

## Prerequisites

| Part | Needs |
|---|---|
| Android app | JDK 17 and the Android SDK (Android Studio's bundled JDK works). `minSdk` 26, `targetSdk` 34 |
| Ground station | Node.js 22 |
| Relay | Node.js 22, or Docker |
| Live checks | A phone with USB debugging or an emulator, `adb`, and Chrome or Edge for the browser checks |

## The Android app

```bash
./gradlew testDebugUnitTest     # core logic, on the JVM
./gradlew lintDebug             # Android lint; app/lint-baseline.xml holds the old issues
./gradlew assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows without a system JDK, point `JAVA_HOME` at Android Studio's, for example
`C:\Program Files\Android\Android Studio\jbr`.

The code is in two packages:

- **`com.smartphonex.core`** is plain Kotlin with no Android imports: the controller, autopilot, device engine, planning,
  safety and protocol. That's what makes it unit-testable. New logic belongs here when it can.
- **`com.smartphonex.app`** holds the Android side: the foreground service, the HTTP and WebSocket server, sensors,
  camera, USB, relay client and the phone's own screens (Jetpack Compose).

To reach the phone's server from your computer over USB:

```bash
adb forward tcp:8093 tcp:8080
# then open http://127.0.0.1:8093 and pair
```

## The ground station

Preact and TypeScript in `web/src`, built with Vite into `app/src/main/assets/web`. **The built files are committed**,
because the app ships them, and CI fails if they don't match the source.

```bash
cd web
npm ci
npx tsc --noEmit                # type check
npx vite build                  # writes app/src/main/assets/web
node ../tools/size-budget.mjs   # keeps the bundle small
```

| File | What it holds |
|---|---|
| `store.ts` | Connection, state, commands with acknowledgements, and the stick loop |
| `main.tsx` | The shell: status bar, alerts, rail, menu and pop-out windows |
| `operate.tsx`, `plan.tsx`, `test.tsx`, `setup.tsx`, `review.tsx` | One file per page |
| `parts.tsx` | Shared pieces: panels, layer switch and horizon |
| `map.ts`, `plan3d.ts` | The canvas tile map, and the 3D plan view |
| `style.css` | All styles. Colours are tokens on `:root`, with the sunlight theme under `[data-theme=sun]` |

### Preview without a phone

`tools/preview-station.mjs` serves the built ground station with sample data: a simulated car on a moving plan, a
stand-in camera stream, triggers and review files. Every command is acknowledged and nothing else happens.

```bash
cd relay && npm ci && cd ..     # the preview borrows the relay's ws package
node tools/preview-station.mjs 8095
# open http://127.0.0.1:8095/#token=preview
```

Rebuild with `npx vite build` and reload the page to see changes.

### Checking it in a browser

`tools/gs-check.mjs` drives the real ground station on a phone through headless Chrome or Edge, and checks pairing, every
page, plans, the ground test, alerts and STOP:

```bash
node tools/gs-check.mjs "<path to chrome or msedge>" 127.0.0.1:8093 "<access token>" <screenshot dir> <fresh profile dir>
```

Use a fresh profile directory each time, so an old token doesn't hide pairing problems.

## The relay

```bash
cd relay
npm ci
npm test                         # sign-in, roles and forwarding security
node admin.mjs add-user <name> operator
node server.mjs                  # listens on 127.0.0.1:8787
```

To deploy it, see [deploy/README.md](../deploy/README.md).

## Live checks

These scripts in `tools/` talk to a real phone or emulator. CI doesn't run them, so run the ones that cover your change.

| Script | Covers |
|---|---|
| `e2e.mjs` | The phone's acceptance test, run live |
| `device-e2e.mjs`, `any-device-e2e.mjs` | Sensors, camera, data budget and outputs; device features in preview only, so nothing moves |
| `fc-e2e.mjs`, `sim-flight.mjs` | The flight controller and simulated flight |
| `backup-e2e.mjs` | Backup and restore |
| `face-e2e.mjs` | The robot face |
| `diag-e2e.mjs` | App health, the phone report, crash recording and the update check |
| `relay-e2e.mjs`, `qr-e2e.sh`, `enroll.mjs` | The relay, QR connection and enrollment |
| `gs-check.mjs`, `browser-check.mjs` | The ground station in a real headless browser |
| `stress.mjs`, `soak.mjs`, `stall-test.mjs` | Control deadlines under load, loop timing, and a link that goes silent without closing. Results are in [PERFORMANCE.md](PERFORMANCE.md) |
| `measure.mjs`, `link-probe.mjs`, `lag-proxy.mjs` | Measurement runs through the relay, link quality, and a simulated bad network |
| `ground-run.mjs`, `prop-*.mjs`, `watch-phone.mjs` | Ground runs, the experimental propeller sensor, and a read-only watch of the phone |
| `fake-llm.mjs`, `agent-run.mjs` | The setup assistant against a scripted model, and a real agent run |
| `ros2_bridge.py` | A bridge to ROS 2 |

## Continuous integration

[`.github/workflows/ci.yml`](../.github/workflows/ci.yml) runs on every push and pull request:

| Job | Runs |
|---|---|
| `android` | Unit tests, lint and a debug build. The APK is kept as an artifact |
| `web` | Type check, build, built files up to date, and the size budget |
| `relay` | Tests and `npm audit` |
| `relay-image` | Builds the Docker image and smoke-tests it |

Pushing a `v*` tag runs [`release.yml`](../.github/workflows/release.yml), which builds a signed APK and attaches it to a
GitHub release. See [RELEASING.md](RELEASING.md).

## Conventions

- **Safety rules don't bend.** Nothing bypasses `DeviceEngine`'s safety layer, outputs stay closed until sign-off, and an
  LLM can never approve anything. See [CONTRIBUTING.md](../CONTRIBUTING.md#rules-that-stay).
- **Saved formats are versioned.** If a change alters a saved file's shape, add a migration (see [RELEASING.md](RELEASING.md)).
- **Plain words in the interface.** Labels say what happens ("Hold to start", "Slide to arm"), and errors say why and what to do.
- **No layout shift.** Status fields have fixed widths, and values that come and go keep their space.
- **Never commit secrets:** `keystore.properties`, `.signing/`, `*.jks`, `local.properties` and `.tooling/` are ignored for that reason.

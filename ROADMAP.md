# SmartPhoneX roadmap: from 0.6 to 1.0

**Goal:** a project anyone can install on an ordinary phone, connect to their own device, and shape to fit it, without
writing code, asking us, or trusting a cloud service. It should be complete enough that people stop needing a second app.

Status: Phase 1 in progress (section 4 has what's done). This replaces the Phase 2 table in `GROUND_STATION.md` section 12, and gathers the "Saved for later"
items from `PLAN.md` and `ANY_DEVICE.md`. Sizes are relative (S, M, L, XL), not time estimates.

## 1. Where we are (0.6, build 10)

Working in software: the phone controller and failsafes, device packs, the flight controller, the relay, MCP and HTTP
tools, the LLM setup assistant, and the Phase 1 ground station on the phone and in the browser.

What stops strangers using it today:
- **Getting it:** a debug APK, no signing, no releases, no update path. The relay runs from a laptop through a temporary tunnel.
- **Trust:** no CI, no security review, no crash data, and nothing has driven real hardware.
- **Fit:** layouts, controls, alerts and looks are fixed. Changing behavior means editing JSON by hand or asking an LLM.
- **Reach:** one serial transport, no other autopilots, one device at a time, English only.

## 2. Rules for every phase

These carry over unchanged and **no customization, plugin or setting can change them**:

1. Stop always wins, from anyone, and is always one tap.
2. Limits, failsafes and approvals live on the phone. A panel, plugin, theme or ground station can only ask.
3. An LLM proposes and a person approves. Nothing installed can approve on someone's behalf.
4. Nothing runs in the control loop without a time budget. Plugin code runs outside it or in a sandbox.
5. It works with no account, no internet and no LLM. Every cloud piece is optional and self-hostable.
6. The screen never lies: stale looks stale, refused says why.

New rule for a project everyone uses:

7. **Everything a user can change is data:** versioned JSON with a published schema. It can be exported, shared by file
   or QR, diffed, and rolled back. A change never needs a rebuild.

## 3. The two phases

| Phase | Version | Theme | Size |
|---|---|---|---|
| 1 | 0.8 | **Complete and yours:** anyone can install it, trust it, and shape it to one device | XL |
| 2 | 1.0 | **Everything and everyone:** other autopilots and hardware, fleets and teams, AI copilot, a public ecosystem, stable APIs | XL |

A **hardware proof track** (section 6) runs alongside both, and doesn't depend on any one person's bench.

Why this split: Phase 1 makes one person with one device fully served, from install through customization. Phase 2
widens what it connects to and who uses it together. Customization sits in Phase 1 because every connector, panel and
widget in Phase 2 plugs into the contracts it defines. Building breadth first would mean building each thing twice.

## 4. Phase 1, 0.8: complete and yours

Inside the phase, build in this order: releases and CI, then the settings layers, then the editors on top of them.

### Install, update, trust

| Area | Included |
|---|---|
| Releases | Release signing, versioned APKs on GitHub Releases, then F-Droid. Play Store later (verify its rules on foreground services, background location and remote control) |
| Updates | In-app update check (off by default for F-Droid builds). Settings and packs migrate across versions, with a schema version on every file and tested upgrade paths |
| CI | Unit, relay and web tests on every push. Emulator e2e suites on a schedule. Lint, type check, bundle size budget |
| Relay | Docker image, one-command deploy, real TLS, backups of users and logs, health page. Docs for a cheap VPS and for a home server. The temporary tunnel stays for testing only |
| Security | Threat model written down. External review of pairing, tokens, relay sessions and roles. Dependency scanning. Rate limits on every public endpoint. A `SECURITY.md` with how to report |
| Phone reach | Self-test report per phone model, shared on opt-in, building a public compatibility table: sensors, GPS quality, USB host, timing jitter, background survival |
| Reliability | Crash and hang reports, opt-in and self-hostable. Watchdog on the phone that records why the service died. Battery and heat warnings |
| Onboarding | First-run guide covering safety, pairing and choosing a device. Docs site built from the repo: install, first device, relay, troubleshooting |
| Phase 1 gaps | Disarm refusal in flight, direction check, settings comparison, trigger limits by fence, altitude or speed, MapLibre for Plan (lazy loaded, so Operate stays small) with offline map areas |

### Customization

Customization comes in layers. Each layer is a JSON document with a schema, saved on the phone, versioned, and shareable.

| Layer | Who it belongs to | What it holds |
|---|---|---|
| **App preferences** | One person, one screen | Theme, units, language, sounds, text size, left or right handed |
| **Workspace layout** | Shared or personal | Which panels, where, at what size, per device class and per screen size |
| **Controls profile** | Shared or personal | Keys, gamepad, receiver channels and touch controls mapped to inputs, with expo, rates, deadband and curves |
| **Device pack** | The device | Outputs, limits, skills, triggers, checklist, alert thresholds (exists, extended) |
| **Mission and behavior** | A task | Plans, fences, triggers, behavior trees |
| **Branding** | An organization | Name, logo, colors and default layout for a team's own build or relay |

| Area | Included |
|---|---|
| Layout editor | Drag, resize and hide panels in the browser and on the phone's home screen. Presets per device class. Reset to default always available |
| Widgets | Gauges, numbers, charts, bars, maps and buttons, bound to any value or command by picking from a list. No code |
| Themes | Design tokens, light and dark, high contrast, a sunlight mode, colorblind-safe palettes checked by a validator |
| Input mapping | Any input to any named control, with expo, rates and curves, saved per device. Live preview of the result |
| Alerts | User rules on any value: threshold, duration, sound, speech, vibration, severity. Built-in safety alerts can't be turned off, only acknowledged |
| Behavior editor | A visual editor for tier 2 behaviors (trees and state machines), with simulation before sending |
| Custom panels | Third-party panels in a sandboxed iframe with a small published API: read state, send commands through the normal checks. No access to tokens |
| Profiles | Export and import all of the above as one bundle, by file, link or QR. Diff before applying, one-click rollback |
| Languages | All text externalized. English plus community translations, right-to-left supported |
| Accessibility | Screen reader labels, keyboard-only use, large touch targets, reduced motion |

### Progress

Done, in 0.7.0-dev (build 11):
- **Releases:** release signing (key kept out of git, `docs/RELEASING.md`), one version source in `gradle.properties`, and a release workflow that tests, signs, verifies and publishes from a tag.
- **CI:** unit tests, lint (new issues fail; existing warnings baselined), web type check and build, a check that the built web files match the source, a size budget, relay tests, a dependency audit, and a relay image smoke test.
- **Data:** a data version with ordered, tested migrations that roll back on failure. Backup and restore in **Setup, Backup**: secrets stay on the phone, hostile files are refused, and the files swap in at a restart.
- **Relay:** a Docker image running as a non-root user, and Compose with Caddy for automatic HTTPS (`deploy/README.md`).
- **Security:** `SECURITY.md` and `docs/THREAT_MODEL.md`. Fixed: a relay client could fake its address with proxy headers to get round sign-in lockouts; `ws` had a high-severity advisory; Android 12+ device transfer could copy the access token to another phone.
- **Reach:** devices without a battery, such as TV boxes, could never arm. They now can.
- **Face:** a face on the phone's screen for robots, with 12 expressions, three styles and colours. It reacts to state (looks where it turns, alarmed in failsafe, sleepy when quiet), and a ground station or AI can show, hide and set it. A tap closes it.

- **App health:** crashes are written to the phone before the app dies, and Android's record of why the app stopped (memory, system, crash, not responding) is kept and shown on Connected and in Setup.
- **Phone reports and table:** a report of what a phone can do, with no location or IDs, to share. `docs/PHONES.md` starts with the reference phone.
- **Update check:** tells people when a newer release is out, with a link. It never downloads or installs. Off until the build names a feed (`spx.updateUrl`).

- **Performance:** a stress test (`tools/stress.mjs`) and fixes from it and from research into other phone controllers: less screen drawing, top priority and hint sessions for time-critical threads, low-latency Wi-Fi, heat shedding, and battery-limit checks. See `docs/PERFORMANCE.md`.
- **USB hardware:** line settings for SBUS and others, FTDI latency, chip checks, unplug detection and a lasting permission. See `docs/USB_HARDWARE.md`.

- **GPS safety:** a GPS guard smooths, rejects jumps and rates trust; missions and Return home stop when it isn't good; the fence allows for uncertainty and acts when the position is lost; network positions never steer. See `docs/NAVIGATION_SAFETY.md`.
- **Experimental propeller sensor:** the phone's microphone hears the propeller's motor speed, and can learn airspeed and wind from it in flight. No extra parts, never sets throttle. It finds the revolution rate from the whole family of tones, learns background like a laptop fan by itself, and on the phone held 3,000–12,000 rpm exactly and locked on within about 0.4 s of a change (synthetic sound; a real motor is next). A hold-to-run ground run before each flight records the motor's speed curve: it measures the pack's strength, spots a changed propeller or motor, and corrects in-flight readings that come out double. Airspeed is learned only in level, steady flight from recent circles, only trusted at throttle and speed combinations it has flown, and shown as "~13 m/s ±2, 8% below your cruise". Plus refusing to disarm a plane that looks airborne, by two fixes in a row, and disarmed vehicles that never drive a motor from a speed reading. See `docs/SPEED_SAFETY.md`.

- **Mission planning, stage 1 of its Phase 1:** plans are files (`smartphonex.plan/1`) that a person or an AI writes. The phone compiles one into the path it would really fly or drive, with turns, survey turnarounds, climbs and descents, and a timeline of what happens when and where, with a time range for wind. It refuses plans whose path leaves the fence, whose climbs can't be made under their miss rules, or that run out of battery before reaching home or a landing zone. It shows where a plane couldn't glide to safety. Also: an automatic failsafe no longer cuts a flying plane's motor (it flies home or circles), and mission legs are now checked exactly against keep-out areas. See `docs/PLANNING.md`.

Not yet run: the workflows on GitHub (no remote yet), a deploy on a real server with a domain, and the update check against a real published release (tested against a local feed).

Next: onboarding and a docs site, the remaining Phase 1 gaps, then customization.

### Phase 1 is done when

1. Someone who's never seen the project installs a signed release, pairs, sets up a sample device and reaches it over
   the internet through their own relay, using only the docs.
2. They build a layout with three widgets, remap a gamepad with expo, add a custom alert, and share it all as one QR
   that another phone imports, without touching JSON.
3. A sample third-party panel, written using only the docs, runs sandboxed and can't read the token or bypass a check.
4. An update from 0.6 keeps every setting, pack and log.
5. CI is green, and the security review's high findings are fixed.

## 5. Phase 2, 1.0: everything and everyone

Inside the phase, build in this order: connectors and transports, then fleets and teams, then AI, then the registry
and the API freeze.

| Area | Included |
|---|---|
| Other autopilots | MAVLink connector for ArduPilot and PX4: watch first, then commands, with each command's effect documented. The phone as a MAVLink companion computer |
| Robotics | ROS 2 connector beyond the current bridge: topics as widgets, actions as skills |
| Connector API | One published contract for connectors, so the community can add autopilots and robots without changing the app |
| Transports | Bluetooth (classic and LE), USB hub with two adapters, wireless serial, CAN through a USB adapter |
| Drivers | Servo feedback read back (position, load, temperature), VESC, Firmata, more bus servo families |
| Kinematics | Inverse kinematics for arms, URDF import for joint structure, 3D pose view |
| Sandboxed code | Tier 3 WebAssembly controllers with a strict time budget (verify speed on low-end Android first; drop it if it can't hold the budget) |
| Fleet | All devices on one map and list, health at a glance, per-device control, group missions |
| Operators | Team accounts and roles on the relay, one clear control owner, handover that needs the new owner to accept, an audit of who did what |
| Sharing | Read-only live links with expiry, shareable test reports and run replays |
| Mapping | Survey grids, orbit, corridor, terrain following, geotagged photo export |
| Awareness | Terrain, airspace, ADS-B traffic and weather layers, each a swappable provider (verify sources and licensing per country) |
| Review | Automatic log findings, run comparisons, fleet statistics |
| Copilot | Draft a mission from a sentence, explain an alert or a log, suggest tuning, build a layout from a description. All under the existing approval rules |
| Providers | Claude, OpenAI-compatible, Gemini and local models, each tested for real, not just by format |
| Perception | On-phone object detection (verify speed and heat per phone), as a sensor that behaviors can use, never as a safety input |
| Local AI | An on-phone model for simple voice commands, if phones can manage it (verify) |
| Registry | A public repository for packs, layouts, panels and connectors. Each entry says what hardware it was really tested on, with its test report |
| Trust | Signed entries, publisher identity, review for anything that runs code, one-click report and takedown |
| Stability | Device format, protocol, panel API and connector API frozen at 1.0, with a deprecation policy |
| Docs | A complete reference, tutorials per device class, and "your first panel" and "your first connector" guides |
| Governance | Maintainers, a release process, and a code of conduct |

### Phase 2 is done when

1. The same ground station screens run a simulated ArduPilot vehicle (SITL) and a SmartPhoneX device side by side.
2. Three simulated devices and two operators on one relay run a group mission, and hand control back and forth with
   no moment where both or neither owns a device. A viewer watches through a read-only link.
3. A person describes a survey in one sentence, the copilot drafts it, the plan checks refuse one bad point with a
   reason, and the person approves the rest. This works with at least two providers and one local model.
4. An outside developer publishes a panel and a connector to the registry, using only the docs. Both pass the
   conformance suite, and a stranger installs and uses them with no help from us.

## 6. Hardware proof track

Software tests can't prove a real vehicle behaves, and the project can't depend on one person's bench.

| Step | How | Proves | Phase |
|---|---|---|---|
| Loopback bench | A second USB-UART adapter on a PC reads what the phone sends. Automated in CI on a self-hosted runner | CRSF, iBus and other frames are exact, with timing and failsafe on cable pull | 1 |
| Reference builds | A documented cheap rover, a pan-tilt and a bus servo arm, with parts lists | A known device anyone can rebuild and compare against | 1 |
| Community reports | A test report from the app, attached to a pack or phone model | Real-world coverage across phones and hardware | 1, then the registry in 2 |
| Simulators | ArduPilot SITL, Gazebo for ROS 2, the built-in simulators | Connectors and missions, end to end | 2 |

Claims follow evidence: a feature is called "tested on hardware" only with a report attached.

## 7. Decisions, as recommended

| Topic | Decision |
|---|---|
| Distribution | GitHub Releases first, then F-Droid. Play Store only after a policy check |
| Hosting | Self-hosted relay by default. No central service we run is needed for anything |
| Settings format | Versioned JSON with JSON Schema for every customizable layer, one bundle format for sharing |
| Panel sandbox | Sandboxed iframes and a message API, not code in the app |
| Map | MapLibre for Plan and Fleet, lazy loaded. The current canvas map stays as the small fallback |
| Crash and usage data | Off by default, opt-in, and self-hostable |
| License | Apache-2.0 stays. Registry entries carry their own license |
| Repo | One repo until 1.0. Split the relay and web app out only if contributors ask |

## 8. Biggest risks

| Risk | Response |
|---|---|
| Each phase is very large | Build in the order given inside each phase, so every piece is usable as it lands. Release 0.7 and 0.9 along the way |
| Customization makes it confusing | Good presets per device class, one reset button, and layers kept separate so a broken layout never breaks a device |
| Plugins become an attack path | Sandboxing, no token access, signing, review for code, and the phone's checks behind everything |
| Phones vary widely | The self-test decides what each phone can do, and the public table sets expectations |
| Someone gets hurt | Safety rules that no setting can change, clear warnings, and hardware claims only with evidence |
| Legal: radio, flight, cellular control, map data | Users are responsible. The app shows limits and warnings, and providers stay swappable |

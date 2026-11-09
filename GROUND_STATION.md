# Universal ground station: plan

**Goal:** one ground station for anything. It should work the same way for a boat, plane, arm or biped, and do what
a dedicated ground control app does: test, arm, trigger, plan waypoints, watch live video, review logs, and more.

Status: **Phase 1 is built** (app build 10). Section 14 lists what's been tested and what's still to do. Phase 2 is now part of `ROADMAP.md`.

## 1. Rules

1. **The screen adapts to the device.** Panels and controls come from what the device says it can do, so a new robot needs no new UI code.
2. **The ground station asks, and the phone decides.** Every limit, check and failsafe lives on the phone. A ground station bug can't remove them.
3. **Everything critical runs on the phone.** Missions, triggers and geofences keep working if the ground station closes or the link drops.
4. **The screen never lies.** Every value shows its age, and stale data looks stale. A control shows sent, accepted or refused, with the reason.
5. **Dangerous actions are hard to do by accident.** They take a deliberate gesture. Stop is always one tap and always visible.
6. **It works with no internet.** The phone serves the whole app, and only map imagery needs a network or a saved area.
7. **A person stays in charge of AI.** AI can draft a mission or explain a log. A person approves anything that moves hardware.

## 2. The workspaces

| Workspace | Purpose | Main contents |
|---|---|---|
| **Operate** | Run the device now | Video or map as the main view, horizon, status strip, mode, arm, stop, triggers, sticks |
| **Plan** | Build what it will do | Map or sequence editor, waypoints, fences, triggers along the route, checks |
| **Test** | Prove it's ready | Pre-run checklist, output tests, sensor tests, failsafe tests, reports |
| **Setup** | Configure it | Setup wizard and LLM assistant, outputs, calibration, settings, input mapping |
| **Review** | Learn from a run | Logs, synced charts and video, track replay, events, export |
| **Fleet** (Phase 2) | See everything | All devices, map, health, who's in control |

Always on screen, in all of them: device name, armed state (with time armed), whether it's ready to arm, mode, control owner, GPS quality, battery, link quality, active alerts, and **STOP**.

How the screens are laid out:

- **Operate:** the map or video carries height above home, speed, distance and direction home, and flight time left, beside the horizon. The side column puts what matters in a run first: arm (with a Ready or "n to fix" badge), mission, safety, mode and triggers. Face, all numbers, outputs and joints close to their title, and each browser remembers which are open.
- **Plan:** tools are grouped as Route, Safety and Place. Keys: Ctrl+Z and Ctrl+Y undo and redo, Enter finishes an outline, Escape cancels it or clears the selection, Delete removes the selected step. A new plan asks first.
- **Setup:** a row of section links stays at the top.
- **The ⋯ menu:** alert sounds, sunlight colours (light and high contrast, kept per browser), and the classic screen.
- Keyboard focus always shows, and on touch screens every control is at least 44 px high.

## 3. How it adapts to any device

On connecting, the phone sends a capability description, built from the device description and self-test.

| If the device has | The ground station shows |
|---|---|
| GPS | Map, track, waypoints, geofence, return home |
| Attitude sensing | Horizon, tilt limits, stabilized modes |
| Joints | Joint table, jog sliders, sequence editor, 3D pose if a model is provided |
| Wheels, thrusters | Speed and turn controls, route planning |
| Flight surfaces | Pitch and roll sticks, flight modes, altitude |
| Cameras | Video, camera picker, photo and record buttons |
| Triggers | A labelled button for each |
| Skills, behaviors | Run and stop buttons |
| None of the above | Its named outputs and inputs, as generic controls |

Words change too: "flight mode" on a plane, "drive mode" on a rover, and "arm" as a safety state stays distinct from a robot arm.
Anything the device lacks is hidden, not greyed out. Anything temporarily unavailable stays visible with the reason.

## 4. Test

Hardware tests are only reachable while disarmed and in an explicit **Test mode**, and leaving the page ends them.

| Test | How it works | Safety |
|---|---|---|
| Pre-run checklist | Automatic checks plus device-specific items for a person to tick | Needed before arming, per the device's settings |
| Output test | Move one output at a time | **Hold to run**: release stops it. Size and time limits |
| Motor test | Spin one motor at low power | Confirms "props off or wheels off the ground". Hold to run, with a low power cap |
| Direction check | Tilt or move the device: the screen says which surfaces should respond | Output preview only |
| Sensor test | Live values with plausibility checks, such as gravity reading about 9.8 m/s² | No motion |
| Link test | Round trip, jitter and loss over a set time, compared to this device's needs | No motion |
| Failsafe test | Deliberately drop the link, and confirm the phone reacts correctly | Disarmed, or with outputs in preview |
| Stop test | Press stop, and measure how fast outputs reach their safe state | Disarmed or restrained |
| Camera, trigger test | Fire each trigger with its action shown, not performed, or for real with confirmation | Dangerous triggers need two steps |
| Timing test | The phone's control timing under full load, with video on | No motion |

Each session produces a saved, dated **test report** that's attached to the device and viewable in Review.

## 5. Arming

| Part | Behavior |
|---|---|
| Readiness panel | Every check, passing or failing, with what to do about it |
| Arm | Slide to arm, or a long press. Never a plain click. The receiver and gamepad can arm by their own deliberate action |
| Warnings | Warnings can be acknowledged. Failures can't be overridden from the screen, except in an explicit, logged test override for a non-flying device |
| Auto-disarm | Disarms if nothing happens for a set time |
| Disarm | One clear action, refused in flight for aircraft unless forced by a confirmed second step |
| **Stop** | Goes to the device's safe state. One tap. Always there |
| **Kill** | Cuts all outputs at once. Needs a guarded gesture, as a falling aircraft or collapsing robot is a real cost |
| Audit | Who armed, from where, and which checks and warnings applied |
| Clear naming | The current `MANUAL` pill is confusing. It becomes separate **Armed**, **Mode** and **In control** indicators |

## 6. Triggers

A trigger is a named action defined by the device. It's a new section of the description, and runs on the phone.

| Part | Options |
|---|---|
| **Action** | Pulse an output, set an output, run a skill, take a photo, start or stop video, add a log marker, send an alert |
| **Fired by** | Screen button, keyboard key, gamepad or receiver channel, reaching a waypoint, every N metres, every N seconds, a sensor condition, AI (only if allowed) |
| **Limits** | Armed only or not, cooldown, maximum count, inside a geofence only, a required altitude or speed |
| **Danger** | Normal, or dangerous. A dangerous one, such as a drop or release, needs two steps from a person, and AI can't fire it |

Examples: a camera shutter every 10 m for mapping, a gripper close, a payload release over a target (two-step), a horn, lights.
Each firing is logged with time, position and source, so photos can be geotagged afterward.

## 7. Plan: waypoints and missions

### On a map

- Click to add, drag to move, with an editable list beside the map, undo and redo.
- **Per waypoint:** altitude or depth, speed, hold time, heading, arrival radius, and triggers to fire.
- **Mission settings:** defaults, what to do at the end, what to do on link loss, and return-home point.
- **Areas:** survey grid over a polygon, perimeter, orbit around a point, and corridor along a path. Phase 2 for most.
- **Zones:** geofence, keep-out areas, safe landing or rally points.
- **Live guidance:** "go here" by tapping the map while running.
- **Layers:** satellite and street, already built. Terrain and airspace in Phase 2.

### Without a map

A robot arm or biped doesn't have waypoints on a map, so the same editor works over its own coordinates:

| Device | "Waypoint" means | Editor |
|---|---|---|
| Rover or boat, boat, plane | A place | Map |
| Indoor robot | A position in a local frame | Floor plan or grid, once there's positioning |
| Arm, biped, hexapod | A pose | Timeline of keyframes, with "capture current pose" |
| Fixed mechanism | An output value over time | Timeline |

### Validation and execution

A mission is uploaded and checked **on the phone**, which returns specific problems before it can run: outside the fence, too far from
home, over an altitude limit, too long for the battery estimate, an unknown trigger, or a device that can't navigate there.

It's stored on the phone and runs there. The ground station shows progress, and can pause, resume, skip or abort. If the link drops, the
mission's own rule applies: continue, hold, or return home. An LLM can draft a mission from a sentence, and it goes through the same checks,
then waits for a person's approval.

**Import and export:** GeoJSON and KML, plus the QGroundControl and Mission Planner formats (verify those formats when building).

## 8. Operate: the rest of the bells and whistles

| Feature | Detail |
|---|---|
| Video view | Video with horizon, heading, speed, and a return-home arrow overlaid. Switch between video and map, with the other as an inset |
| Sticks and controllers | On-screen sticks, keyboard, and any browser gamepad, with mapping, expo and rate settings |
| Alerts | Levels of info, warning and critical. Sound and spoken alerts, which must be acknowledged, with a history |
| Status | Battery estimate, remaining time, signal, GPS quality, temperature, vibration, timing |
| Recording | Save video and data together on the phone, or in the browser |
| Multi-camera | Picture-in-picture, where the phone allows concurrent cameras (verify per phone) |
| Quick actions | Configurable button bar per device |
| Interfaces | Phone, tablet and desktop layouts, installable as an app, and a high-contrast outdoor mode |

## 9. Setup and Review

**Setup:** the LLM wizard, already built, plus visual output mapping, input mapping, guided calibration, settings search, a saved settings
history with comparing and restoring, and backup of whole devices.

**Review:** a list of logs, synced charts with replay, video and map, events on the timeline, automatic findings such as "vibration exceeded
limits at 02:13", export, and test reports. An LLM can answer questions about a log in Phase 2.

## 10. The phone app

The phone is often the only screen beside the vehicle, so it has to work well by itself: no computer, internet or browser required.
Today's screen is one long list of debug text. It becomes a proper app.

| Tab | Purpose |
|---|---|
| **Home** | Device and picture of its state: armed, mode, who's in control, health, battery. STOP is always on screen |
| **Connected** | Everything linked to the phone, with status and how to fix it |
| **Trim** | Trim outputs and set level, with live feedback |
| **Test** | The same tests as the ground station, run from the phone |
| **More** | Pick a device, sensors, cameras, setup assistant, pairing and settings, and the full ground station |

### Connected

One list, each row showing state in plain words, with the fix when something's wrong:

| Row | Shows | Example of a fix |
|---|---|---|
| Vehicle output | Adapter, protocol, signed off or not, last send | "USB access needed": Allow button |
| Receiver | Adapter, frames per second, signal lost or not | "No frames: check wiring and protocol" |
| Gamepad | Name, and live stick movement | "Press any button to connect" |
| Ground stations | How many are connected, and who has control | Show pairing code and QR |
| Relay | Connected or not, server name, outage time | "Can't reach it: check mobile data" |
| AI agents | Which agent or assistant is connected, its last action | Revoke access |
| Sensors | How many are working, with rates, and what's missing | "No barometer: altitude comes from GPS" |
| GPS | Satellites, accuracy, and age of fix | "Go outside" or "Turn on Location" |
| Cameras | Which exist, and which is live | "Allow camera" |
| Network | Wi-Fi or 4G/5G, signal, and data used this session | Warn if USB tethering is on, as it disrupts the link |
| Power | Battery, temperature, thermal state | "Phone is overheating" |

Tapping a row opens details. The phone also shows its own **QR code**, so a browser or second device can pair without typing an address.

### Trim

| Part | Behavior |
|---|---|
| Output trim | One row per output, with a slider, fine +/- steps, live value and pulse width, and "center" |
| Also here | Reverse, and low and high endpoints, within the device's signed-off limits |
| Level trim | A live horizon and Set level, for the phone's mounting. Mounting orientation is picked from pictures |
| Saving | Per device, and saved at once, with a history and undo |
| Live on hardware | Moves the real output only in Test mode, or while armed. Otherwise it's preview, and the screen says which |
| While armed | Small steps only, and a size limit, so a slip can't cause a sudden movement |
| After sign-off | Small trims are fine. Larger changes require re-running the limits and direction checks |
| Who can change it | A person, on the phone or in the ground station. An LLM can propose a value, and a person accepts it |

### Tests on the phone

The same test engine as the ground station, so results and rules match, and reports are saved to the same place.
Output tests are **hold to run**, with a big press-and-hold button: releasing stops at once. Only one screen can run a hardware test
at a time, and any other connected ground station sees that it's running. When testing a moving device, the app warns to restrain it first.

### Built for use in the field

- Large controls, usable with one hand, in sunlight, and with a high-contrast mode.
- **Touch lock** while mounted on a vehicle, so vibration or spray can't press anything. STOP from other sources still works.
- A short first-run guide: pick or set up a device, check Connected, trim, test.
- All of the above works in airplane mode, because it talks directly to the runtime.

### How it's built

Native screens using Jetpack Compose for the five tabs, so **STOP, trim and tests don't depend on a web page or local server working**. Plan and Review
open the same web ground station inside the app, so those aren't written twice. Native screens read one shared runtime, and a person's input on the phone keeps
its place in the control order.

## 11. Architecture

```
                       Ground station app (the same app everywhere)
   Workspaces -> Panels (shown by capability) -> Device store (timestamped) -> Connector
                                                                                   |
        +-----------------------------+-----------------------+--------------------+
   SmartPhoneX direct            SmartPhoneX relay       MAVLink gateway (Phase 2)
        |                             |                        |
      Phone                         Phone             ArduPilot, PX4 and others
```

### What has to be built on the phone

The biggest part of this isn't screens. These don't exist yet:

| Phone module | Purpose | Phase |
|---|---|---|
| Capability report | Tells the ground station what to show | 1 |
| Readiness engine | One source of pre-arm checks, with reasons | 1 |
| Test mode | Hold-to-run outputs and timed limits | 1 |
| Trigger engine | Actions, conditions, limits, logging | 1 |
| Mission engine | Validates, stores and runs missions, with Auto and Hold modes | 1 |
| Geofence | Keeps the device inside limits, independent of missions | 1 |
| Kill | All outputs off, separate from stop | 1 |
| Log index and photo capture | Listing, downloading, geotagging | 1 |
| Trim store | Output trim, reverse and endpoints, per device, with history and limits | 1 |
| Connection report | One list of everything connected, with status and fixes, for both screens | 1 |

### Frontend

The current ground station is one growing JavaScript file, which won't scale to this. **Recommended:** rewrite it in TypeScript with
Preact, as it's very small, and MapLibre GL for maps, with a size budget since the phone serves it. The old page stays until the new one
matches it, and the existing relay and test tools keep working. Heavy panels update from one shared frame loop, so a laptop
doesn't choke on 20 Hz canvases.

### Protocol

A single versioned, documented message set with capability negotiation, so older phones and newer ground stations can work together. Every command
gets an ID and a tracked result. Anyone can write another ground station against it.

### Known problem to fix early

Browsers slow down timers in background tabs. Manual control depends on the page sending commands, so switching tabs can trigger the failsafe. It's
why I've had to ask you to keep the tab active. Phase 1 moves the command stream to a Web Worker, warns when a tab isn't visible, and doesn't
treat a hidden tab as controlling.

### Maps offline

Satellite and OSM tiles work online. **OSM's and Esri's tile terms restrict bulk downloading**, so pre-saving large areas from those servers isn't
acceptable. Offline areas will need a proper source, such as OSM-derived vector map files that a user can download or host, and terms have to be checked.

## 12. The two phases

### Phase 1: everything for one device

A complete, polished ground station for SmartPhoneX devices on one phone.

| Area | Included |
|---|---|
| Foundation | New app shell, capability-driven panels, protocol, command tracking, stale-data rules, background-tab fix, layouts for phone to desktop |
| Operate | Video and map views, horizon overlay, on-screen and gamepad control, alerts with sound, status strip, clear indicators |
| Arm and stop | Readiness panel, slide to arm, auto-disarm, stop, kill, audit |
| Test | Checklist, output and motor tests with hold to run, sensor, link, failsafe, stop and trigger tests, saved reports |
| Triggers | Engine and buttons, plus firing by waypoint, distance, time, sensor and receiver |
| Plan | Map waypoints with altitude, speed, hold and triggers, geofence, return point, validation, upload, run, pause, resume, abort, guided "go here", keyframe timeline for jointed devices, GeoJSON import and export |
| Setup and Review | Existing setup moved in, settings history and restore, log list, chart, track and video replay, test reports |
| Phone app | Home, Connected, Trim and Test tabs as native screens, touch lock, QR pairing, first-run guide, and the web ground station inside the app |

**Phase 1 is done when, on your phone over 4G with no vehicle:**
1. A pre-run test produces a report, and a failing check really blocks arming.
2. You plan five waypoints on satellite view, with a photo trigger at two, and bad plans get refused with reasons.
3. You arm with a slide, and walk the route. Progress follows you, photos fire at the right places, and they're geotagged.
4. Airplane mode mid-route applies the mission's link-loss rule, and the mission's state comes back on reconnect.
5. Walking through the fence triggers its response.
6. The 12-joint biped gets the right screens, with no map, and a keyframe sequence plays in preview.
7. Replay shows the whole run, with charts and video in sync.
8. In airplane mode, using only the phone, you can see what's connected, trim an output and set level, and run a hold-to-run test that stops on release.

Walking proves logic, guidance and timing. It can't prove a real vehicle tracks those commands well, which needs real hardware.

### Phase 2: universal, and many

| Area | Included |
|---|---|
| **Other autopilots** | MAVLink connector and gateway for ArduPilot and PX4, watching first, then commands. ROS 2 connector. An open connector interface |
| Fleet | Multiple devices on one map, per-device control, group missions, and handover between operators with one clear owner |
| Mapping | Survey grids, orbit, corridor, terrain following, offline map areas, geotagged photo export |
| Awareness | Terrain data, airspace layers, ADS-B traffic, weather (verify sources and licensing per country) |
| AI copilot | Draft missions from a sentence, explain alerts and logs, suggest tuning, all under the existing approval rules |
| Advanced Review | Automatic log findings, run comparisons, fleet statistics |
| Sharing | Read-only live links, shareable test reports, team accounts and roles |
| Extensibility | Third-party panels, sandboxed, with a published API and a theme system |
| Reach | More languages, accessibility review, 3D pose view, and desktop and mobile packaging |

**Phase 2 is done when:** the same screens run a real ArduPilot or PX4 vehicle and a SmartPhoneX device side by side, an outside developer ships a panel or
connector using only the docs, and several operators can hand off control cleanly.

## 13. Biggest risks

| Risk | Response |
|---|---|
| Phase 1 is large, with most of the work on the phone | Build in order, keeping each piece usable: engines before screens that depend on them |
| "Adaptive" screens get confusing | Design presets per class, not just rules, then test them with real people |
| The screen and phone disagree about state | The phone is the single truth, and the screen only shows confirmed state |
| A mistaken tap has physical consequences | Deliberate gestures, tested on touchscreens, in sunlight and with gloves |
| Commanding other autopilots safely | Read-only first, and those systems keep their own failsafes, so document exactly what each command does |
| Hand tests can't prove real-world performance | Say so plainly in reports, and require a real vehicle before claiming it |
| Regulations for flight, cellular control and airspace data | Users are responsible, and the app makes limits and warnings visible, but it can't guarantee compliance |
| Map and data licensing | Check before depending on a source, and keep providers swappable |

## 14. Phase 1 status

Built: every Phase 1 row in section 12, on the phone and in the new web app (`web/`, built into `app/src/main/assets/web`). The old page stays at `/classic/`.

Deviations from the plan:
- **Map:** a small tile map of our own on a canvas, not MapLibre, which keeps the bundle at about 30 KB gzipped.
- **Replay video:** about two pictures a second are saved with each run while the camera is on, not full video. They're synced to the charts.
- **Dangerous triggers** fire only by hand or from a point in a plan a person sent. Time, distance, sensor and receiver can't fire them.
- **AI:** a mission or trim from an LLM always waits for a person's approval. Missions stay off for planes.
- **Disarm in flight:** refused for a plane that's moving faster than `DISARM_AIR_SPD` (4 m/s) or is 15 m above home, on a fresh GPS fix. STOP still cuts the motor. Without a fresh fix it can't tell, so it doesn't refuse.
- **Not built:** stick expo and gamepad remapping, settings comparison beyond history and restore, a direction check, and trigger conditions limited to a geofence, altitude or speed.

Automated results, on build 10:
- 187 unit tests and 29 relay tests.
- On the Redmi Note 13 Pro 5G over USB: `tools/gs-check.mjs` (a real browser against the phone's server) and `tools/fc-e2e.mjs` 23/23.
- On an Android 16 TV emulator: e2e 52/52, device 48/48, flight 11/11, any-device 33/33, QR 12/12, relay 24/24, browser check, and the native tabs (arming, trim, hold to run, touch lock, Plan in the app).
- A geotagged photo taken on the real phone, read back with its location in EXIF and alongside it.

The done criteria in section 12 are **not yet met**: 2 to 5 and 7 need a real walked run, and 1, 6 and 8 have automated coverage only. Nothing here has been tested over real 4G, and nothing has run on a real vehicle. Native slide to arm has only had injected touches on the emulator, not a finger.

Phone GPS limits what missions can do. Measured indoors on this phone: top-4 signal 25 to 27 dB-Hz, accuracy typically 13 to 35 m. The app shows signal strength, and warns when a point's arrival radius is smaller than the current accuracy.

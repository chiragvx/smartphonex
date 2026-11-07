# Any device: design plan

**Goal:** connect a phone to almost any robotic device. Using the LLM of their choice, a user gets it configured
and working. The phone then runs by itself, acting on its own AI and/or a person's commands through a receiver.
Everything is open, so anyone can add hardware, controllers, behaviors or AI providers.

Status: built, apart from the gaps below, and tested in software and on an emulator. Nothing has driven real hardware.
Numbers are estimates until measured. Details marked "verify" have not been checked against real parts.

**Tested:** a real Claude agent went from a plain description to a valid device, in preview, stopping where only a
person can approve. The stand-in LLM confirmed the app's Anthropic and OpenAI-compatible request formats, but no OpenAI
or local model has actually been run. Only Claude has done real setup.

**Built, but never run on real hardware:** USB-UART, bus servo drivers, receiver input, gamepad, MAVLink with a real
ground control app, the ROS 2 bridge, gaits and balance on a real robot, and line tracking on a real line.

**Not built:** sandboxed code plugins (tier 3), Bluetooth and CAN transports, on-phone LLM and object recognition (vision
is simple line tracking), inverse kinematics, reading servo feedback, and a public pack repository. Item 12's docs,
conformance test and bridges exist.

Decisions made: Apache-2.0, a USB hub allowed as an option, and a serial bus servo device as the first real target.

## 1. The core idea

Today the software assumes a vehicle with throttle, steering, pitch and roll. It needs to assume only this:

> A device has **inputs** (what it can sense), **outputs** (what it can move), a **safe state**, and **skills**.
> A portable description says what those are. An LLM helps write that description, and the phone enforces it.

Three rules follow, and they don't bend:

1. **The LLM configures and supervises. It's never in a real-time loop.** Model replies take seconds, and a walking robot falls in milliseconds.
2. **The LLM proposes, and the phone decides.** Every LLM action passes the same local validation as any other command.
3. **After setup, nothing external is needed.** No LLM, internet, PC or ground station is required to run safely.

## 2. Architecture

```
  Any LLM        Ground station      RC receiver     Gamepad     Phone screen    ROS 2, MAVLink
     |  MCP or API     |                  |             |             |               |
     +-----------------+------------------+------+------+-------------+---------------+
                                                 |
                                  INPUT ROUTER (authority and liveness)
                                                 |
   Behaviors  ---> SKILLS ---> CONTROLLERS (plugins, fixed-rate, time-budgeted) <--- ESTIMATORS <--- SENSORS
   (local AI)                        |                                                                  ^
                                     v                                                                  |
                         SAFETY LAYER (limits, rate limits, safe state, watchdogs)                      |
                                     |                                                                  |
                                     v                                                                  |
                        ACTUATOR DRIVERS (protocol plugins) <---> TRANSPORTS (USB-UART, others) --------+
                                     |
                              The physical device
```

### Plugin interfaces

Each is a small, versioned, published contract.

| Interface | Job | Examples |
|---|---|---|
| Transport | Move bytes | USB-UART, USB CDC, Bluetooth, Wi-Fi, CAN adapter |
| Protocol driver | Speak to a device family | CRSF, iBus, SBUS, bus servos, VESC, MAVLink, Firmata, custom text commands |
| Input source | Bring in human or machine commands | Receiver, gamepad, screen, ground station, MCP |
| Sensor source | Bring in measurements | Phone sensors, camera, servo feedback, external sensors |
| Estimator | Turn sensors into state | Attitude filter, odometry, position |
| Controller | Turn state and goals into outputs | Rate/attitude (exists), differential drive, joint trajectory, balance |
| Skill | A named thing the device can do | Drive to, stand up, walk, grasp, return home |
| AI provider | Answer a prompt | Claude, OpenAI-compatible, Gemini, a local model |

### How plugins are trusted

A random device must not mean random code in the control loop.

| Tier | Form | Can it break the loop? | Who can add it |
|---|---|---|---|
| 1. Data | JSON packs, including custom text and binary protocols | No | Anyone, including the LLM |
| 2. Behavior language | Safe, bounded trees and state machines | No: no loops without limits, and it can only call skills | Anyone, including the LLM |
| 3. Sandboxed code | WebAssembly controllers with a strict time budget and no system access | Stopped if over budget | Developers (verify runtime speed on Android) |
| 4. Built in | Kotlin or native, in the app | Yes | Reviewed contributions |

Most devices should only need tiers 1 and 2. LLM-written code doesn't go in the loop.

## 3. The device description

A single versioned JSON file. It's readable, shareable, and easy for an LLM to write and for the phone to validate.

```json
{
  "schema": "smartphonex.device/1",
  "name": "Small biped",
  "class": "legged",
  "hazard": "light",
  "transports": [{ "id": "bus", "type": "usb-uart", "baud": 115200 }],
  "actuators": [{
    "id": "left_hip", "driver": "bus-servo", "transport": "bus", "address": 1,
    "unit": "deg", "min": -45, "max": 45, "neutral": 0, "max_rate": 180,
    "safe": { "action": "hold" }, "feedback": ["position", "load", "temperature"]
  }],
  "sensors": [{ "id": "imu", "source": "phone.imu", "mount": { "type": 1, "yaw": 0 } }],
  "inputs": [{ "id": "rc", "driver": "crsf", "transport": "rx", "map": { "ch1": "turn", "ch2": "forward", "ch5": "mode" }, "failsafe_on_loss": true }],
  "controllers": [{ "id": "balance", "type": "ankle-hip-balance", "rate_hz": 100, "gains": {} }],
  "skills": [{ "id": "walk", "type": "gait", "params": { "step_ms": 600 } }],
  "safe_state": { "on_link_loss": "sit_down", "on_app_death": "unverified" },
  "limits": { "ai_autonomy": "supervised" },
  "commissioning": { "tests": [], "signed_off_by": null }
}
```

Packs bundle the description, gains, skills, behaviors and test report. They contain no secrets or location.
Current vehicle presets become the first packs, so cars, boats and planes stay working through the change.

## 4. Device classes

The system is general, but physics isn't. Each class declares what it needs, and the phone's self-test decides if it qualifies.

| Class | Examples | Control need | Fit | Main problem |
|---|---|---|---|---|
| Fixed mechanisms | Pan-tilt, turntable, conveyor, plotter | Low | Good | Calibration |
| Wheeled, tracked | Cars, tanks, delivery bots | Low | Good | No wheel speed without an encoder |
| Water | Boats, surface drones | Low | Good | Waterproofing, GPS drift |
| Arms | Hobby arms, grippers | Medium, slow is fine | Good | Inverse kinematics, knowing position |
| Fixed wing | Foam planes | Medium | Plausible | Timing and vibration |
| Multi-legged | Hexapods, quadrupeds | Medium | Plausible | Many servos, gait tuning |
| Two-legged, slow | Small servo bipeds, static gaits | Medium-high | Plausible, hard | Phone weight, falling |
| Multirotor | Quadcopters | Very high | Doubtful | Android timing |
| Two-legged, dynamic | Running, torque-controlled | Very high | No | Needs far faster control and torque sensing |

### What two-legged really means here

- **Achievable target:** a small biped using position-controlled servos, walking slowly with stored gaits, with phone IMU balance corrections.
- **Weight is a design issue:** a phone may weigh around 200 g, and it sits high up. It needs to be part of the robot's design, not an add-on.
- **Falling is normal:** skills include fall detection, going limp or protecting joints, and getting up.
- **"Stop" is unsafe:** cutting power makes a biped collapse, so its safe state is "sit down if possible, otherwise hold".
- **LLM limits:** an LLM can set up the joints and propose gaits, but it can't reliably invent a stable gait by itself.
  Expect a library of known gaits, adapted by measured tuning.

## 5. Connecting hardware

The USB-UART decision still holds as the default, and it turns out to fit better than expected.

- **Serial bus servos:** several families daisy-chain many joints on one UART, with position feedback. A biped or arm could need only one
  adapter. They're half-duplex, which may need adapter wiring (verify per family). Some run at speeds not every adapter supports (verify).
- **Serial-input receivers and ESCs:** a direct fit for vehicles.
- **Servo boards, microcontrollers, motor controllers with serial ports:** connect through protocol drivers. An external board is the
  device's own hardware, not something the platform requires.

### The one-port problem

A receiver and an output device usually need different speeds, and one UART has one speed. So:

| Option | Good | Bad |
|---|---|---|
| Two adapters on a USB hub (recommended default for receiver control) | Simple, fully separate | Adds a hub. Not every phone powers one, so test it |
| Bluetooth gamepad for human input | No extra port, cheap | Radio range, and pairing can drop |
| Device with one serial link carrying both | One port | Depends on the device |
| Wireless serial adapter | No port needed | Adds latency and a radio link |

This relaxes "USB-UART and nothing more" only when someone wants a receiver *and* a separate output device.

### Correction to the flight controller plan

`FLIGHT_CONTROLLER.md` proposes a receiver's own failsafe as the backstop if the phone dies. That assumes a receiver can drive servos from its
serial input, which many can't, since their outputs follow the radio link (verify). So commissioning must test what really happens, not assume it.

## 6. The receiver: running without internet

A receiver gives independence: long range, low latency, and no phone network.

- **Formats:** CRSF, iBus and SBUS input drivers on the serial port. SBUS uses an inverted signal, which needs an adapter that supports it (verify).
- **Mapping:** channels map to named inputs, such as `forward` and `mode`, not to fixed roles.
- **Liveness:** the receiver's failsafe flag and frame timing feed the same link-loss logic as everything else.
- **Mode switch:** a transmitter switch selects human, assisted or AI control.

### Who's in charge

The input router has one active owner, in a fixed order that users can restrict but not invert:

1. **Any stop:** always wins, from any source.
2. **Physical human input:** receiver or gamepad with sticks moved.
3. **Local screen.**
4. **Ground station.**
5. **Local behaviors and on-device AI.**
6. **Remote LLM.**

Every source has its own liveness timeout. Taking control is immediate. Giving it back to AI always needs a deliberate action.

## 7. Bring your own LLM

Anyone's LLM should be able to do setup, whichever way it connects:

| Route | For | How |
|---|---|---|
| MCP | Claude and other MCP clients | Existing endpoint, with new setup tools |
| HTTP API | Anything else that can call tools | The same operations with a published OpenAPI description |
| Built-in assistant | People without a developer setup | A chat in the ground station. They bring a key or a local model address |
| Local model | Privacy, offline | Any OpenAI-compatible endpoint on their network |

API keys stay encrypted on the phone (Android Keystore) or in the user's own client. They're never put in packs or logs.

### The setup session

The phone runs this as a strict sequence, and the LLM acts within the current stage only.

| Stage | LLM does | Phone enforces | Person does |
|---|---|---|---|
| 1. Describe | Asks what it is, reads a photo if allowed, and reads any datasheet provided | Nothing moves | Answers, grants camera |
| 2. Discover | Lists USB devices, tries known protocols, and scans buses | Read-only probes only | Connects hardware |
| 3. Draft | Writes the description | Schema and physical sanity checks | Reviews a plain summary |
| 4. Preview | Exercises outputs | Output preview only: hardware stays disconnected | Checks mapping |
| 5. Safe setup | Explains how to restrain the device | Blocks motion until confirmed | Lifts wheels or restrains it, and confirms stop works |
| 6. First motion | Requests one joint, tiny and slow | Hard limits on size, speed and time. Needs confirmation for each new output | Watches, says which way it moved |
| 7. Limits | Widens range in steps | Stops on load or current spike, if readable | Confirms real end stops |
| 8. Failure tests | Runs link loss, stop, and USB pull | Records real behavior | Pulls the cable when asked |
| 9. Tune | Proposes gains from measured responses | Bounds and rollback. Reverts if worse | Approves |
| 10. Skills | Composes skills and behaviors | Tier 1 and 2 only | Tries them |
| 11. Sign off | Writes a report | Saves a pack with test results | Signs off |

### Safeguards on the LLM

- **Local validator:** limits come from the description and physical tests. Once a person has signed off, the LLM can't widen them.
- **Human confirmation:** the first motion of any output, widening limits and signing off all need a person at the phone or ground station. An LLM can't approve itself.
- **Prompt injection:** datasheets and web pages are untrusted. They can make an LLM propose bad things, which is another reason limits are enforced locally.
- **Audit:** every LLM request and result is logged.
- **Undo:** each stage's settings are versioned, and rollback is one action.

**Hazard levels** set what's allowed, and the LLM can't lower one without a person:

| Level | Examples | AI autonomy allowed |
|---|---|---|
| Toy | Under about 1 kg, slow | Unattended behaviors, with limits |
| Light | Small rovers, arms, bipeds | Supervised by default |
| Heavy or fast | Fast or strong devices | Human in control, AI suggestions only |
| Flying | Any aircraft | Line of sight, safety pilot, local rules |

Out of scope: weapons, anything carrying people, medical devices, and machinery where a failure could seriously injure someone.

## 8. Independence after setup

Three layers of intelligence, and each works without the next:

| Layer | Runs on | Speed | Example |
|---|---|---|---|
| Reflexes | Phone, built in | Milliseconds | Limits, fall detection, stop on link loss |
| Behaviors | Phone, compiled from what the LLM wrote | Tens of milliseconds | "Patrol. If you see a person, stop and turn to them" |
| Local perception | Phone's vision models | Tens to hundreds of ms | Detect objects, follow a line (verify phone speed and heat) |
| Local LLM | Phone, if capable | Seconds | Simple voice commands (verify) |
| Remote LLM | Internet, when available | Seconds | Plan a mission, explain a problem, adjust behavior |

So an LLM can leave behind a behavior that keeps running after the connection ends. The device degrades cleanly as layers disappear:
no internet means no planning, but behaviors and receiver control continue.

**Safety in behaviors:** bounded by description limits, with their own timeouts, and they can only call skills. They never reach actuators directly.
Changes from a remote LLM apply only when the device is in a safe state, unless a person has approved live changes.

## 9. Open architecture

| Area | Commitment |
|---|---|
| Spec | Device description, plugin contracts and pack format are versioned and documented |
| Protocols | MCP and HTTP for AI, MAVLink for autopilot tools, a ROS 2 bridge for robotics, WebSocket for ground stations |
| Portability | Export everything. Standard formats wherever they exist, such as URDF import for joint structure |
| Replaceability | Every layer in section 2 can be replaced, including the ground station |
| Community | A public pack repository, with packs carrying commissioning reports |
| Conformance | A test suite that any plugin or pack must pass |
| Android reach | Self-test defines what each phone can do, and missing hardware degrades features, not the whole app |
| No lock-in | Works with no account, no cloud service, and any LLM, or none |

### Limits of a shared pack

A pack from someone else is a starting point, not proof that it's safe on your hardware. Real parts vary, so importing one always re-runs
stages 5 to 8 before full limits apply.

## 10. Changes to existing code

| Now | Becomes |
|---|---|
| `Channel`: normalized values and pulse widths | One actuator kind among others (position, velocity, torque, on/off), with units and feedback |
| `Command.Drive`: four fixed axes | Named inputs from the description |
| `VehicleProfile` | Device description, with existing presets as packs |
| `FlightController` | A controller, estimator and skills set, used by the aircraft and vehicle packs |
| `VehicleAdapter` | Transport and protocol driver plugins |
| Failsafe values per channel | Per-actuator safe actions: hold, neutral, go limp, or run a sequence |
| Modes | Skills |
| Human sources in `VehicleController` | The input router |
| MCP vehicle tools | Generated from the description, so any device gets correct tools, plus setup tools |

Existing tests have to keep passing through the change, so cars, boats and planes work the whole way.

## 11. Build order

One system, built in order. Each item is usable when finished, and needs only what's listed.

| # | Build | Proof | Needs |
|---|---|---|---|
| 1 | Description schema, validator, packs. Convert presets | All existing tests still pass | Nothing new |
| 2 | Generalized actuators and inputs, safe actions, and the preview for any device | A made-up 12-joint biped shows live on the phone | Phone |
| 3 | Input router with liveness and authority | Priority and handover tests | Phone |
| 4 | Setup session with MCP and HTTP tools, stages 1-4 | **Different LLMs configure a device, in preview, from a plain description** | Phone, LLMs |
| 5 | Built-in assistant with bring-your-own key and local models | A non-developer does step 4 | Phone |
| 6 | USB-serial transport and first drivers | Real bytes on a logic analyzer or loopback | USB-UART |
| 7 | Receiver input | Real sticks drive the preview, and loss triggers failsafe | Receiver, adapter |
| 8 | Stages 5-11 on real hardware | One servo, then a small device, fully commissioned | Servo, then a device |
| 9 | Behavior language and on-phone runtime | Runs with the LLM and internet gone | Device |
| 10 | Joint controllers, gaits, and balance | Arm or hexapod, then a small biped | Those robots |
| 11 | Local vision | Behavior reacts to what it sees | Phone |
| 12 | Open release: docs, conformance tests, pack repository, ROS 2 and MAVLink bridges | Someone else adds a device without our help | Contributors |

Items 1 to 5 need only a phone. **Item 4 is the true test of the idea**, so it's worth reaching early.

## 12. Biggest risks

| Risk | Why it matters | Response |
|---|---|---|
| LLMs produce confident, wrong configs | Hardware can be damaged | Local limits, staged tests, a person confirming each first motion |
| Unknown hardware has no readable feedback | The LLM can't see what happened | Use the person and the phone's camera and IMU as feedback |
| Phone dies while the device is moving | Outputs may hold their last value | Test and record it per device. It limits autonomy if there's no hardware dead-man |
| Android timing and heat | Limits demanding devices | Self-test decides, and measure rather than promise |
| Android USB host varies by phone | Some phones won't work with adapters | Detect it, and offer Bluetooth or Wi-Fi as alternatives |
| "Any device" spreads effort thin | Could end up with nothing working well | One fully commissioned device per class before adding more |
| Liability with shared packs | A bad pack could hurt someone | Re-run safety stages on import, with clear responsibility statements |

## 13. Decisions, as made

1. **License:** this decides who can build on it. Apache-2.0 is recommended for wide adoption, including commercial use, with patent protection.
2. **First real device after the preview:** recommended: a cheap pan-tilt or small arm using serial bus servos, since it tests joints and feedback at low risk.
3. **Receiver wiring:** accept a USB hub when a receiver and a separate output device are used together? Recommended yes, as an option, not a requirement.

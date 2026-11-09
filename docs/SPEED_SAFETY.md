# Speed data that can't move the throttle on its own: plan

Goal: no single bad speed reading, and no run of them, can drive the throttle to full, cut it to zero, or reverse a
motor. This holds whichever source the speed comes from: GPS, wheel encoders, an airspeed sensor, or the fusion filter.

## 1. Where things stand today

Checked in the code, 2026-11-08:

| Path | Uses speed? | Risk today |
|---|---|---|
| AUTO speed control (`VehicleController`) | Only if the vehicle reports a measured speed. On real hardware none does, so it uses a fixed throttle per target speed, with no feedback | None. It appears as soon as a speed sensor is connected |
| Braking when disarmed or in failsafe | No longer. **Fixed 2026-11-09:** disarmed and failsafe give zero throttle, and every output sits at its failsafe value whatever any sensor says. Braking from speed is only used while armed in AUTO | None while disarmed. Armed braking gets layers 3–4 |
| Obstacle stop distance | Faster means stop sooner | A spike makes it stop early. Safe direction |
| Plane Return home and Hold | Fixed `RTH_THROTTLE`, no speed | None |
| Plane disarm check (`flyingReason`) | **Fixed 2026-11-09:** two airborne fixes in a row (the higher of Doppler and the guard's smoothed speed), or 1 s of propeller airspeed. It stays "flying" until every sign has been gone for 3 s | A single spike can't refuse Disarm, and a headwind dip can't allow it |
| GPS guard | Its own speed estimate, from gated positions | Reach check only, no throttle |

So the plan is mostly about what we build next: speed hold, the fusion filter, and automatic plane throttle. Steps 1
and 2 below fix the two existing paths first.

## 2. Worst cases to design for

| # | Fault | Example | What a naive controller does |
|---|---|---|---|
| 1 | Single spike up | GPS reads 40 m/s for one fix (multipath, a bad fix) | Cuts the throttle. A plane can stall |
| 2 | Single spike to zero | A dropped encoder pulse, or a fix with speed 0 | Full throttle |
| 3 | Stuck value | The same fix repeated, a frozen sensor, a stalled serial line | The integrator winds up to full throttle |
| 4 | Slow bias | Drift while parked reads 0.5–2 m/s | Creeps or brakes against nothing |
| 5 | Dropout | Tunnel, indoors, unplugged sensor | A held reading, then a jump when it comes back |
| 6 | Garbage | NaN, infinity, negative from an unsigned source | Anything |
| 7 | Late data | Android batches fixes and delivers seconds late, or a clock jump | Reacts to the past |
| 8 | **No sign** | GPS speed is always positive, even reversing | Target −1, measured +1: pushes harder in reverse, runaway |
| 9 | **Ground speed isn't airspeed** | A plane downwind in 10 m/s wind reads fast while near stall | Cuts the throttle and stalls the plane. The classic one |
| 10 | Wheel slip | Wheels spin on grass or ice, or lock under braking | Reads fast or zero, wrong both ways |
| 11 | Sources disagree | Wheels say 3 m/s, GPS says 0 | Picks one at random |
| 12 | Wrong units or scale | A pack maps an encoder with the wrong wheel size | Consistently wrong by a factor |

## 3. The design: five layers, each enough on its own

### Layer 1: check every reading
One `SpeedEstimate` per vehicle. Raw readings never reach a controller.
- Reject NaN, infinity, negatives from unsigned sources, readings above the vehicle's `SPD_MAX`, and anything older
  than `SPD_TIMEOUT_MS` or out of time order (faults 6, 7).
- **Acceleration limit:** speed can't change faster than the vehicle can physically accelerate or brake (`ACC_MAX`,
  per vehicle type, say 5 m/s² for a car and 3 for a boat). A reading outside the possible band is ignored (faults 1, 2).
- **Stuck detection:** a reading identical for N samples while throttle and the IMU say it should change is marked
  stuck (fault 3).
- **Sign from elsewhere:** the sign comes from the commanded direction, wheel encoder phase, or IMU acceleration,
  never from GPS (fault 8).

### Layer 2: cross-check sources
- Every source carries an uncertainty. The estimate is a weighted blend, as in the fusion filter planned in
  `NAVIGATION_SAFETY.md`.
- When two sources disagree by more than their combined uncertainty for longer than `SPD_DISAGREE_S`, the estimate is
  marked **untrusted**, it's logged, and the operator is told (fault 11).
- Wheels, GPS and IMU fail in different ways. Wheels slip, GPS jumps, the IMU drifts. Two agreeing beat one (fault 10).
- With only one source, trust is capped. It's **never good enough to cut power**: only a cross-checked estimate may do
  that (see layer 4).

### Layer 3: the controller can't overreact
Whatever the estimate says:
- **Feed-forward first:** throttle = expected throttle for the target speed (from `ff`) + a correction.
- **Bounded correction:** the feedback part can move the throttle at most `SPD_AUTH` (say ±25%) from feed-forward. Even
  a wildly wrong speed can only swing the throttle by that much (faults 1, 2, 4, 12).
- **Slew limit:** the throttle output changes by at most `THR_SLEW` per second (say full range in 1 s), in auto modes.
  A one-tick spike moves it 1%, not 100%.
- **Integrator rules:** clamped, frozen while the estimate is untrusted, and bled back towards feed-forward when it is
  (fault 3).
- Manual throttle from a person is never slew-limited or overridden, except by the existing failsafes.

### Layer 4: power cuts and power increases need different evidence
- **To add power:** the trusted estimate is enough, within layer 3's limits.
- **To cut power or reverse** (braking, a speed-triggered stop, Disarm refusal flipping): the condition must hold for
  `SPD_PERSIST_S` (say 0.5 s), on a trusted estimate, or come from a person.
- Disarmed and failsafe **never drive a motor from a speed estimate**. Braking while disarmed becomes neutral, or a
  fixed, small brake for vehicles that set one (`BRAKE_THR`). This fixes the existing `brake()` path.
- `flyingReason` uses the guard's smoothed speed and needs the condition for 1 s, so one spike doesn't flip it.

### Layer 5: fallbacks when speed is untrusted
| Vehicle | Fallback |
|---|---|
| Car, robot | Feed-forward only, as today. If untrusted for over `SPD_FAIL_S`, ramp down to a stop and tell the operator |
| Boat | Feed-forward only. A boat coasting with no throttle is safe, so no ramp-up |
| Plane | **Never control plane throttle from GPS speed** (fault 9). Auto modes use a fixed cruise throttle (`THR_CRUISE`) with pitch holding attitude, as ArduPilot does without an airspeed sensor. Throttle never drops below `THR_MIN_AIR` while airborne in an auto mode. A real airspeed sensor is used only when it agrees with GPS speed plus a wind estimate |

## 4. Order of work

1. **Fix `brake()`:** disarmed and failsafe never drive a motor from a speed estimate. **Done.**
2. **Persistence on `flyingReason`:** smoothed speed, condition held for 1 s. **Done** (two fixes in a row, released after 3 s).
3. **`SpeedEstimate` with layer 1:** checks, acceleration band, stuck detection, sign. Every controller reads only from it.
4. **Layer 3 in the speed PID:** bounded correction, slew, integrator rules. This must land before any real speed
   source is wired to the PID.
5. **Layer 4 and 5 fallbacks**, with operator alerts on Operate.
6. **Layer 2**, together with the fusion filter (wheels, GPS, IMU).
7. **Plane throttle:** fixed cruise, `THR_MIN_AIR`, airspeed sensor only with a cross-check.

## 5. How it's tested

- **Fault injection:** every case in section 2 runs against the simulator, as a unit test. It feeds clean speed,
  injects the fault, and asserts that:
  - throttle never steps by more than the slew limit;
  - it never leaves feed-forward by more than `SPD_AUTH`;
  - power is never cut on a single reading;
  - a disarmed vehicle's outputs never move;
  - a plane in an auto mode never drops below `THR_MIN_AIR`.
- **Random faults:** thousands of runs with random spikes, freezes, dropouts and biases, checking the same rules.
- **Replay:** real GPS logs from the phone (the `SpxGnss` summaries and fix logs) through the estimate, to tune limits
  on real noise rather than guessed noise.
- **On the vehicle:** wheels off the ground first, with spikes injected from a test command, then on the ground.

## 6. Settings this adds

`SPD_MAX`, `ACC_MAX`, `SPD_TIMEOUT_MS`, `SPD_DISAGREE_S`, `SPD_AUTH`, `THR_SLEW`, `SPD_PERSIST_S`, `SPD_FAIL_S`,
`BRAKE_THR`, `THR_CRUISE`, `THR_MIN_AIR`. Defaults per vehicle type, like the existing failsafe settings. The limits
on authority, slew and `THR_MIN_AIR` can be tightened by a pack, but not turned off.

## 7. Experimental: the propeller as an airspeed sensor, heard by the microphone

No extra parts. Off unless `MIC_PROP` is set. It never sets throttle.

### What's built

- **Hearing** (`MIC_PROP` 1):
  - It finds every strong peak, then the **revolution rate** that explains them as one family of whole multiples:
    blade tones, the motor's own tone (often 7×), imbalance. So a loud motor tone, or a blade tone the microphone barely
    hears, can't be taken for the blade tone. Blade-pass = revolutions × `PROP_BLADES`.
  - A family must have a propeller's shape: blade harmonics including the 1st or 2nd, and at most one other series.
    Scattered room noise doesn't, unless it's very strong.
  - Locking on, or a jump of over 15%, is believed only once it repeats for 3 frames.
  - **Background:** a tone heard steadily for 10 s with every output still, within ±4% (a fan hunting for speed), is
    skipped from then on, armed or not, and forgotten a minute after it stops. Tones of a tracked tone that's changing
    speed are never learned, so a propeller tested on the bench isn't.
  - Clipping, a second propeller and a lost microphone are flagged on Operate. Airspeed learning skips them.
  - Sound is analyzed in memory and never stored.
- **Airspeed** (`MIC_PROP` 2):
  - `airspeed = k0 + k1·n + k2·(u/n) + k3·(u/n)·minutes`, from balancing motor and propeller torque.
  - Learned with the wind from trusted GPS, after flying all 8 directions.
  - Kept only within `PROP_FIT_RMS`. Used on Operate and in the in-flight disarm check only.
- **Tested on the phone, 2026-11-09** (`tools/prop-sweep.py` and `tools/prop-sweep.mjs`): a 50 s sound with holds from
  3,000 to 12,000 rpm, jumps both ways, a throttle-cut ramp and a punch-out ramp, played through the phone's own
  speaker beside a running laptop fan whose speed it wasn't told:

  | | Result |
  |---|---|
  | 9 held speeds | Every one 0.0% median error, 0.1% worst, SNR 25–45 dB |
  | 3,000 rpm | Right, from the 4th, 6th and 7th harmonics: the speaker can't play its 100 Hz blade tone |
  | Lock-on after a jump | 0.30–0.41 s (frame-exact, unit test); 0.2–0.8 s on the phone, which reports twice a second |
  | Ramps of 833 and 1,750 rpm/s | 0.19 s behind, then 0.1% off |
  | Sound stops | Noticed within 1.3 s |
  | The fan | Learned as background by itself (150 Hz); 25 s of room alone gave no false readings |

- **Still the easy case.** The sound is synthetic, from a speaker next to the microphone. A real motor and propeller is
  the stage 2 gate.

### Flaws found

Ranked by how badly each could mislead. "Bug" means the code as written is wrong today.

**Hearing the right thing**

| # | Flaw | Effect | Fix |
|---|---|---|---|
| H1 | **Bug:** a motor spun on the Test page while disarmed is learned as "background" | The real propeller tone is skipped in flight at that rpm | Learn background only while every output is at rest, and only in the minute before arming. Clear it at each arming |
| H2 | **The motor hums too.** An outrunner's magnets and the ESC make tones at pole pairs × rev/s (often 7×), stronger than the blades at some throttles | Locks onto the motor tone and reads rpm 3.5× too high | Find the **revolution frequency** as the common fundamental of every tone present: blades, motor and imbalance are all whole multiples of it. Then the blade count isn't needed |
| H3 | **Phone microphones roll off below about 100 Hz.** Android only promises a flat response from 100 Hz for the unprocessed source, and for voice recognition. I didn't check which one this phone offers | A slow propeller's fundamental fades, and the 2nd harmonic wins: rpm reads double | Same common-fundamental fix. Plus a sanity check: rpm must follow throttle the way it did on the ground run |
| H4 | **Clipping.** A propeller 10–20 cm away can exceed what a phone microphone handles (roughly 120 dB SPL; not measured) | Distortion adds harmonics and shifts levels | Detect clipped samples and mark readings untrusted. Advise mounting the phone with its microphone away from the propeller |
| H5 | **Airflow noise in flight** is loudest below about 200 Hz, right where slow propellers sit | The tone gets lost at speed | Report signal-to-noise in flight logs. Learn nothing when the tone is weak |
| H6 | **Twin motors, or other aircraft nearby** | Two tones beat. It may lock onto the other one | Common-fundamental search reports when it sees two families. Twins are out of scope at first |
| H7 | **The microphone gets taken:** phone calls, video with sound, voice assistants | Silence or an error mid-flight | Already reported as unavailable. Make sure it's shown loudly on Operate, not just in stats |

**Turning rpm into airspeed**

| # | Flaw | Effect | Fix |
|---|---|---|---|
| A1 | **Battery voltage matters more than airspeed.** A pack sags 10–15% through a flight, and differs between packs. That changes rpm at the same throttle as much as airspeed does. The phone can't measure it, and `minutes` is a crude stand-in | The biggest error source. A fit from one flight can be wrong on the next pack | **A ground run before each flight:** static rpm at a fixed throttle measures this pack's strength, and throttle is scaled by it. In flight, keep re-learning with a forgetting factor, so it tracks the sag on any circle |
| A2 | **Throttle isn't voltage at the motor.** ESC throttle curves, timing and braking differ | Model shape is off between ESCs | The ground run sweep (3 throttle steps) learns the static rpm/throttle curve per setup |
| A3 | **Weak signal.** From static to cruise, rpm may change only a few percent | Small errors in rpm or voltage become big airspeed errors | Ground-run measurement (A1) plus in-flight re-learning. Report the uncertainty honestly. Present it as **"relative to your cruise"**, not an absolute number |
| A4 | **No estimate with the motor off or at idle**: gliding and landing approach, exactly where stall matters most. A windmilling propeller doesn't follow the model | Nothing when it's needed most | Say so on Operate. Stall protection must not depend on it (section 3, layer 5) |
| A5 | **High angle of attack.** Near stall, air doesn't flow straight into the propeller, so the model breaks down | Wrong at slow speed | Show no estimate below the slowest speed it learned. Never call it a stall warning |
| A6 | **Climbs and dives:** GPS horizontal speed is less than airspeed when climbing | Learns wrong values | Learn only in level flight (barometer or GPS altitude rate small) and when steady (rpm and speed stable for 3 s) |
| A7 | **Lag:** the tone is smoothed, and GPS speed arrives late | Learns wrong while speeding up or slowing down | Same steady-flight rule. Line the readings up by timestamp |
| A8 | **Wind changes** with time and height | Fit drifts | Learn only from circles completed within a few minutes |
| A9 | **Inputs move together:** n and u/n rise and fall together, so the fit is poorly determined, and fine at the samples but wrong in between | Bad estimates in combinations it hasn't seen | Check validity over cells of throttle × rpm actually flown, not just the rpm range. Add sensible bounds on the k values |
| A10 | **A new propeller, motor or battery type** silently invalidates the fit | Confidently wrong | The ground run compares with the stored static curve. More than 5% different means the old fit is forgotten, and a warning is shown |

**What it's used for**

| # | Flaw | Effect | Fix |
|---|---|---|---|
| U1 | Disarm check: the propeller estimate is OR'ed with the GPS checks | A false "flying" only refuses Disarm (STOP still works). A false "not flying" changes nothing. **Safe as is** | Keep the OR |
| U2 | It looks like a real airspeed on Operate | People may trust it like an airspeed tube | Label it "estimate" with ± and the time since last learning. Hide it when untrusted |

### Revised plan

Each stage has to pass before the next starts.

1. **Fix H1.** Done: background is learned only with every output at rest, and never from a tone that's changing
   speed. Covered by unit tests.
2. **Hear the right thing, and prove it on a real motor** (`MIC_PROP` 1):
   - Common-fundamental (revolution frequency) search, instead of assuming the blade tone (H2, H3, H6). **Built.**
   - Clipping detection (H4). **Built.**
   - Signal-to-noise in the live stats, and no learning below `MIC_SNR` + 6 dB (H5). **Built.**
   - A notice in the Arm panel when the microphone is lost, distorting, or hears another propeller (H7). **Built.**
   - Left of H3: with only the blade tones heard, and the fundamental too faint to hear, the reading can still be
     double. A real motor's own tone usually settles it; the ground run (stage 3) now catches the rest in flight.
   - Found on the phone while testing stage 3: a laptop fan's 188 Hz tone sat at 3 × half the revolution rate of a
     7,611 rpm propeller, so half the true rate explained one more peak and won (read 3,805). Fixed: a family is
     marked down for gaps in its blade series (4, 8, 12 without 6 and 10), which a real propeller doesn't have.
   - **Check against a real rpm, with no extra parts:** a 240 fps slow-motion video from a second phone shows the
     blades. Count frames per revolution.
   - **Gate:** within 2% at 5 throttle steps, on 2 different motors.
3. **Ground run before each flight.** **Built** (`GroundRun.kt`, Test page, `tools/ground-run.mjs`):
   - Hold-to-run, disarmed, people only, after confirming the plane is restrained. Steps through `PROP_GR_LO` to
     `PROP_GR_HI` (30, 45, 60%); each step counts once the speed holds within 1.5% for 1.5 s, about 2.5 s each.
     Letting go, arming or kill stops the motor at once.
   - Refuses a step that didn't speed up, never settled, or jumped more than the throttle explains (a harmonic).
   - The first run per vehicle is the reference. Later runs give this pack's strength (A1), which scales throttle
     for the airspeed model within 30 minutes. A curve of a different shape (over 3%), or a speed beyond 0.75–1.2×,
     is a changed setup (A10): it becomes the new reference and learned airspeed is forgotten.
   - In flight, every reading is checked against the curve (H3). Half or double what fits is corrected; anything
     else is flagged and not learned from. Shown in the Arm checks as "Ground run".
   - **Tested on the phone, 2026-11-09,** with synthetic motor sounds from its speaker, beside the laptop fan:

     | Run | Result |
     |---|---|
     | Reference (sound: 6,036 / 7,611 / 9,024 rpm) | 6,035 / 7,611 / 9,023 |
     | Same pack again, sound 0.4% faster | Strength 1.004: agrees within 1% |
     | Weaker pack, sound 7% slower | Strength 0.930, "Pack 7.0% weaker" |
     | Different propeller, curve bent 5% | "Setup changed (5.3% off)", new reference |

   - **Gate, still open:** the same on a real motor and two real packs, full and half-empty.
4. **Learning, done properly** (`MIC_PROP` 2). **Built, unit tested:**
   - Level and steady only (A6, A7): motor within 2%, ground speed within 1 m/s, and height changing under 0.7 m/s
     (barometer) or 1.2 m/s (GPS), over 3 s.
   - Recent circles only (A8): samples older than 6 minutes are dropped.
   - Re-learn with forgetting through the flight (A1): refits every 50 samples, newer samples weigh more (3 minute
     time constant), and the ground run's pack strength scales throttle.
   - Throttle × rpm validity cells (8 × 6, kept in `PROP_CELLS`) and bounded k values (A9): faster spinning at a
     fixed throttle must mean faster air; implied speeds and wind must be possible.
   - **Gate:** on logged real flights, the fit predicts the next circle's wind-corrected speed within 1.5 m/s.
5. **Present it honestly** (U2, A3, A4, A5). **Built, unit tested:**
   - "~13 m/s ±2, 8% below your cruise", labelled an estimate, with when it was learned.
   - Hidden when idle or gliding, outside the learned cells, or slower than it learned, with the reason shown.
6. **Uses stay as they are:** Operate and the in-flight disarm check. Unchanged, checked. Never throttle, never a stall warning. Revisit
   only after stage 4 has passed on several planes.

The airspeed model's biggest weakness is A1: the battery. If stage 3 can't tell packs apart reliably, stop at motor
speed (`MIC_PROP` 1), which is still useful for detecting a motor that's lost power or a damaged propeller.

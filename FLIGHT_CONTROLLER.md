# Phone flight controller: plan

**Goal:** a phone attached to any vehicle acts as its flight controller, with the ground station link
replacing a $1000+ setup. We build our own basic, modular, ArduPilot-style autopilot designed around phones.
It is our own code: the concepts are common, but no ArduPilot code is copied, because of its GPL license.

**Testing method:** the phone itself is the test vehicle. Moving it by hand or carrying it is the flight.
There is no software flight simulator. Thresholds below are starting estimates until measured.

## How it behaves

The flight controller runs from app start, whether or not a vehicle is attached.

| State | Estimator and telemetry | Control laws | Outputs |
|---|---|---|---|
| Disarmed | Running | Running, shown as "would command" | Held at failsafe values |
| Armed | Running | Running | Follow the selected mode |
| Failsafe | Running | Failsafe action | Per vehicle type |

Pilot and AI commands no longer go straight to the mixer. They become requests to the current mode:

```
Pilot, AI, mission -> Mode -> Navigation -> Attitude control -> Rate control -> Mixer -> Output
                                                 ^                  ^
Phone sensors ------------> Estimator -----------+------------------+
```

The existing command validation, authority rules, stale-command rejection and failsafe latching stay in front.

## Modules

Each sits behind an interface, so it can be replaced per phone or vehicle without touching the others.

| Module | Job | First version | Replaceable later with |
|---|---|---|---|
| Device layer | IMU, GPS, compass, barometer, clock and storage sources | Android sensor APIs | Native high-rate sensor paths, USB sensors |
| Capability probe | Tests this phone, then sets what it may do | Sensors present, real rates, timing | Thresholds per vehicle class |
| Scheduler | Fixed-rate fast, medium and slow tasks, each with a time budget | Fast 50-100 Hz, as the phone allows | Native thread |
| Calibration | Gyro bias, accelerometer, compass, level trim, phone mounting orientation | Guided in the ground station | Automatic in-use calibration |
| Estimator | Attitude and heading | Complementary filter | EKF with GPS position and velocity |
| Rate and attitude control | Rate PID inside an angle controller | Per-axis gains | Auto-tune |
| Navigation | Heading, waypoints, return home | Straight-line tracking | Energy control, wind handling |
| Modes | One small module per mode | Manual, Stabilize, Hold Heading, Return Home | Loiter, Auto, Acro |
| Vehicle class | Which laws and modes apply | Plane, boat, rover | Multirotor, if timing data allows |
| Mixer and output | Already built | Preview display | USB-UART serial |
| Failsafe manager | Link, GPS, battery, heat, sensor and timing failures | Per vehicle type | Geofence |
| Arming checks | Refuse to arm on bad state, with a stated reason | Sensors, calibration, timing, battery | Per-mode requirements |
| Parameters | Named, bounded, saved settings for everything | Per vehicle profile | Import and export |
| Logger | Full-rate binary log per session | Sensors, estimator, control, outputs | Replay of real logs in unit tests |

### Phones differ

The probe chooses the best available source and reports what's missing:

| Missing or weak | Fallback | Consequence |
|---|---|---|
| Gyroscope | None | Stabilization refused, Manual only |
| Compass | GPS course when moving | No heading when stationary |
| Barometer (this phone) | GPS altitude | Altitude is coarse |
| GPS | None | No Return Home or waypoints |
| Slow or irregular sensors or loop | Lower control rate | Limits the vehicle classes allowed |

## Telemetry on the ground station

**"Perfect" means:** every value is real, timestamped and shows its age. Stale data visibly greys out rather
than appearing current. Anything unavailable says so, and nothing is faked.

| Panel | Shows |
|---|---|
| Artificial horizon | Our roll, pitch and heading, moving with the phone |
| Reference check | Our attitude against Android's own fused attitude, with the difference |
| Controller | Mode, armed state, why it can't arm, active failsafe |
| Commands vs response | Requested vs actual angle and rate per axis, and the P, I and D terms |
| Outputs | Would-command and actual output values |
| Map | Real position, track, home, distance and bearing home, GPS accuracy and satellites |
| Motion | Gyro, accelerometer, compass and altitude charts, plus a vibration level |
| Health | One status light per module: sensors, estimator, timing, GPS, link, heat, battery |
| Timing | Real rate of each task, worst stall, overruns, sensor-to-output delay |
| Link | Round trip, telemetry age, network type, data use |
| Logs | Record, download, and replay in the ground station |

**Data priority:** flight state is sent at a guaranteed rate outside the data budget, after commands and before
sensors and video. Starting estimate: about 20 Hz and under 10 kbps. The full-rate data stays in the phone's log.

## Testing by hand

| Test | Do this | Must see |
|---|---|---|
| Attitude | Tilt to known angles on a table edge | Horizon matches, within a few degrees of the reference |
| Drift | Leave it still for 10 minutes | Small, measured drift |
| Shake | Shake it hard, then hold still | Recovers within seconds |
| Mounting | Change the orientation setting and rotate it | Horizon still reads correctly |
| Stabilize | Armed, roll it right | Surfaces command the correct opposite way, smoothly and in proportion |
| Mixing | Repeat for each vehicle type | Correct output moves |
| Hold Heading | Rotate it by hand | Steering command turns it back |
| Return Home | Set home outside, walk away | Commands steer toward home, and distance falls on the way back |
| Link loss | Airplane mode while armed | Correct failsafe for the vehicle type, and recovery rules |
| GPS loss | Go indoors | Health turns red, modes respond correctly |
| Arming | Arm with a bad calibration or no GPS | Refused, with the reason |
| Endurance | 30 minutes with camera and 4G on | Timing, heat and battery measured |

**What hand testing can't prove:** gains that hold a real vehicle stable, since moving the surfaces doesn't
move the phone. In hand tests the integral term has to be limited or it just winds up. It also can't prove
vibration behavior, real output delay, or real flight behavior. Those need a vehicle later, with
a safety pilot and a physical power cutoff.

## Status

Built: everything in the build order below, except running the hand tests. Satellite (Esri) and street
(OpenStreetMap) map layers use real GPS, and load only once there's a position.

Tested on an emulator with no motion sensors, and with stand-in telemetry for the ground station panels.
Attitude, control direction, Return Home and failsafes are tested only with computed sensor values in
unit tests. **None of it has run on real sensors yet:** step 6 is next.

Compass and gyro use Android's own calibration, plus optional gyro and level calibration in the ground station.

## Build order

One system, built in this order. Each item is usable once finished:

1. Parameters, scheduler with timing report, and device layer with capability probe.
2. Calibration and estimator, with the horizon and reference check.
3. Full telemetry panels and the logger.
4. Rate and attitude control, Manual and Stabilize, arming checks, and the failsafe manager.
5. Navigation, Hold Heading and Return Home, with the map.
6. Hand tests above on your phone, over 4G.

Later, when hardware exists: USB-UART output, the receiver failsafe check, Loiter and Auto missions, then a real vehicle.

## Limits to be honest about

- Android can't guarantee timing. The app measures and reports this, but can't remove it.
- The first version runs in Kotlin. If measurements show that's too slow or irregular, the fast loop moves to native code.
- A controller running on an unattached phone proves the logic, not airworthiness.

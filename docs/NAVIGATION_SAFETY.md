# GPS and safe navigation

Phone GPS is good to about 3 to 5 m under open sky, and 10 to 50 m near buildings or indoors. It can also jump tens
of metres for a moment, and drift slowly while claiming better accuracy than it has. SmartPhoneX assumes all of
this will happen, and is built so a bad position makes the vehicle **stop**, not drive somewhere wrong.

## What the app does

Every satellite fix passes through a GPS guard (`GpsGuard.kt`) before navigation sees it.

| Problem | What happens |
|---|---|
| **Jitter** | A small filter smooths the position, so noise doesn't steer the vehicle |
| **A sudden jump** | A fix further than the vehicle could have moved is ignored. If the GPS keeps insisting, the new place is accepted but treated as unproven for 10 s, and navigation waits |
| **Slow drift while parked** | An armed vehicle that isn't being driven is held still, which roughly halves drift. It can't remove drift completely: only extra sensors could |
| **Overconfidence** | The guard never claims to be better than about 60% of the GPS's own figure, because GPS errors drift together and don't average away |
| **Network positions** | Wi-Fi and cell-tower positions are shown on the map, but never steer anything |
| **Duty cycling** | The GPS chip is kept tracking all the time on Android 12 and later, which reduces jumps |

The guard's state decides what navigation may do:

| State | Meaning | Missions and Return home |
|---|---|---|
| Good | Settled, and within `ARM_GPS_ACC` (25 m) | Drive |
| Settling | Fewer than 5 consistent fixes | Wait, stopped |
| Too rough | Uncertainty over `ARM_GPS_ACC` | Wait, stopped |
| Jumped | A jump is being checked | Wait, stopped |
| Lost | No usable fix for `GPS_TIMEOUT_S` (3 s) | Mission holds. Return home fails safe and a person takes over |

And around it:
- **Missions won't start** unless the position is good, and every point's arrival radius is wider than the current uncertainty. The refusal says how wide to make it.
- **While running**, a mission stops and waits whenever the position is untrustworthy, or worse than 1.5 times the current point's radius. If it's still bad after `NAV_WAIT_S` (30 s), the mission pauses and holds for a person to decide.
- **Return home** stops (a plane circles) rather than drive on a position it doesn't trust.
- **The geofence allows for uncertainty.** A position must be inside by more than its ± radius plus `FENCE_MARGIN` (2 m), or the fence acts. A fence that can't be checked because the position is lost also acts.
- **Everything is logged**: ignored jumps, resets, waits, and resumes, for Review.
- **The map shows it**: the vehicle where navigation thinks it is, a circle for how unsure, blue when trusted and amber when not, and the raw GPS fix as a grey dot.

## What's up to you

- **Size arrival radii to the GPS:** at least 2× the accuracy you see outdoors. 10 m is a safe start for a phone.
- **Keep fences well inside anything dangerous:** a road, water, a drop. Allow at least the GPS error plus the vehicle's stopping distance.
- **Run outside, with open sky.** Check that Connected shows a GPS signal of 30 dB-Hz or more before a mission.
- **Mount the phone with a clear view of the sky.** Metal, carbon and a person's body block the signal.
- **Watch the first runs** with a hand on STOP, and keep the receiver's own failsafe set.

## Limits

- No sensor fusion yet. Wheel odometry or the phone's IMU could ride through short GPS gaps; today the vehicle stops instead.
- Phone GPS can't do better than a few metres. Precise work (lane-level, docking, follow-me within a metre) needs RTK GPS or visual markers, which aren't supported.
- The guard has been tested with simulated jumps, drift and loss in unit tests. It hasn't yet run live on a phone, or on a moving vehicle outdoors.

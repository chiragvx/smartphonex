# Mission planning

A plan is a file that says what a vehicle should do. A person writes it on the map, or an AI writes it from a sentence.
The phone compiles it into the path the vehicle would really travel, a timeline of what happens when and where, and a
list of problems. The same file format serves planes, rovers and boats; each vehicle's settings decide how it's done.

## Rules

1. **The phone decides.** Every check runs on the phone, the same code that flies. The browser and the AI only ask.
2. **Safety checks are enforced.** A plan whose path leaves the fence, can't make its climbs under its own rules, or
   can't reach home or a landing zone on its battery is refused. There's no override.
3. **Regulatory layers are advisory.** Airspace and no-go layers (phase 2) are drawn and announced, with a disclaimer
   and a logged acknowledgement. They don't block: compliance is the operator's responsibility, and the layers may be
   incomplete. The tool never says a plan is "compliant".
4. **Honest numbers.** Times come with a range for wind. Every assumption the compiler used is returned with the plan.
5. **What was approved is what runs.** A plan that checks out has a hash of its compiled route and assumptions.

## The file

```json
{
  "format": "smartphonex.plan/1",
  "name": "North field survey",
  "vehicle": "plane",
  "home": [18.52, 73.85],
  "bvlos": true,
  "defaults": { "speed": 15, "alt": 60, "altRef": "home", "radius": 40 },
  "miss": { "altitude": { "do": "circle", "maxS": 90, "then": "abort" } },
  "onEnd": "home",
  "onLinkLoss": "home",
  "zones": [
    { "id": "field", "kind": "landing", "at": [18.523, 73.852], "radius": 30 },
    { "id": "scrub", "kind": "ditch", "at": [18.527, 73.849] }
  ],
  "steps": [
    { "do": "takeoff", "id": "up", "heading": 270, "alt": 60 },
    { "do": "survey", "id": "north", "area": [[18.521, 73.851], [18.524, 73.851], [18.524, 73.855], [18.521, 73.855]],
      "spacing": 60, "angle": 0, "actions": [{ "do": "trigger", "id": "photo", "everyM": 20 }] },
    { "do": "land", "id": "down", "at": [18.52, 73.85], "heading": 270 }
  ]
}
```

Positions are `[lat, lon]`, WGS84. Distances are metres, speeds m/s, times seconds, headings compass degrees.

| Field | Meaning |
|---|---|
| `format` | Required, `smartphonex.plan/1` |
| `vehicle` | `plane`, `rover` or `boat`. Optional; if given it must match the vehicle |
| `home` | Where the plan was made for. Used only when the phone has no home yet, and said so |
| `bvlos` | Beyond line of sight: gaps in glide reach become problems, not warnings |
| `defaults` | `speed`, `alt`, `altRef` (`home` or `msl`), `radius` for every step |
| `miss` | Default miss rules (below). Planes circle to make altitude; rovers and boats hold |
| `onEnd` | `hold`, `home` or `stop`. A plane can't `stop` |
| `onLinkLoss` | `continue`, `hold` or `home` |
| `zones[]` | Safe places: `id`, `kind` (`landing` or `ditch`), `at`, `radius`. Home always counts as a landing zone |
| `steps[]` | What to do, in order |

### Steps

Every step has an `id`, so edits and the timeline can refer to it. Missing ids are filled in (`goto-1`, `survey-2`) and
the plan comes back with them. Any step can set `speed`, `alt`, `altRef`, `radius`, `miss` and `actions`.

| `do` | Fields | What it becomes |
|---|---|---|
| `goto` | `at` | One point |
| `path` | `points[]` | Points in order |
| `hold` | `seconds`, `at` (default: where it is) | Wait there. A plane circles |
| `orbit` | `center`, `radius`, `laps`, `dir` (`cw`/`ccw`) | Circles round a point |
| `survey` | `area[]`, `spacing`, `angle` (the lines' bearing) | Parallel lines across the area, alternating. A plane joins the first line lined up and turns around beyond the lines' ends, so each line is flown straight |
| `corridor` | `path[]`, `width`, `spacing` | Passes along a path, side by side |
| `takeoff` | `heading`, `alt` | Planes, first step only: climb out from home along the heading |
| `land` | `at`, `heading`, `slope` (default `PLAN_LAND_SLOPE`), `approachAlt` (30) | Planes, last step: approach down the slope to touch down |
| `home` | none | Go to home |
| `repeat` | `times` (1–50), `steps[]` | The steps, again. Not nested. Timeline ids get `#1`, `#2`… |

### Actions

| `do` | Fields | When |
|---|---|---|
| `trigger` | `id`, optional `everyM` | On arriving, or every so many metres along the straight part of the leg. A dangerous trigger never fires by distance |
| `notify` | `text` | On arriving |

### Miss rules

What happens when a step can't be done as planned. Each kind can be a word (`"abort"`) or an object.

| Kind | When |
|---|---|
| `altitude` | Arrived more than `tolerance` (10 m) from the step's altitude |
| `arrival` | Didn't reach the point in time |
| `speed` | Couldn't hold the planned speed |
| `action` | A trigger didn't confirm |

`do` is `circle` (planes), `hold`, `continue`, `skip` or `abort` (return home). Waiting (`circle`, `hold`) needs `maxS`
(1–600 s) and ends in `then`: `continue`, `skip` or `abort`. A wait can't lead to another wait, so every fallback ends.
Stalling is never a fallback: in flight a plane keeps its speed before its altitude, and arrives low rather than slow.

## What the compiler does

1. **Expands** templates into points, in metres around home.
2. **Flies them**, using the vehicle's settings (`PLAN_*`, below):
   - It turns on its real turning circle: `speed² / (g · tan(bank))` for a plane, `PLAN_TURN_R` for others. If the next
     point is inside the circle, it goes on straight until it can turn.
   - Survey turnarounds are a U (lines at least a turning circle apart) or an omega (closer lines). The first line is
     joined by the shortest turn-straight-turn (Dubins) path, so it arrives lined up.
   - It climbs and descends at `PLAN_CLIMB` and `PLAN_SINK` while moving. If it arrives off its altitude, the miss rule
     decides, and circling time is added.
3. **Times it.** Expected time, with a range: a plane between `speed/(speed+wind)` and `speed/(speed−wind)` of the
   still-air time, others at least ±10%. Holds are fixed.
4. **Checks it.** Problems block the plan; warnings are shown.

| Check | Problem when |
|---|---|
| Fence | The path, turns included, crosses the fence or a keep-out area, or comes within `PLAN_MARGIN`. Planes are checked on turns flown downwind in `PLAN_WIND`, the widest they get |
| Distance and height | Beyond the fence's distance from home (less the margin), or above its height |
| Climbs | The miss rule ends in `abort` |
| Battery | At any point, the time left (slow clock, keeping `PLAN_RESERVE`) can't reach home or a landing zone |
| Glide (planes) | With the motor off, no landing or ditch zone within `alt × PLAN_GLIDE`, against the wind. A warning, or a problem when `bvlos`. Takeoff and landing are exempt |
| Plane limits | Slower than `PLAN_SPEED_MIN`, an arrival radius under half the turning radius, an orbit tighter than it can turn, a landing slope it can't descend, `onEnd` stop |
| The file | Unknown steps, triggers or fields out of range, duplicate ids, more than 200 steps or 2,000 points |

### The answer

`plan_check` (see `docs/SPEC.md`) returns:

- `ok`, `problems[]` and `warnings[]`, each with the step and the place.
- `hash`, when ok.
- `summary`: distance, time with its range, battery %, top altitude.
- `assumptions`: every number it planned with, and where home came from.
- `timeline[]`: `t`, `lo`, `hi`, `step`, `kind`, `text`, `lat`, `lon`, `alt`, and `until` for repeated triggers.
- `gaps[]`: stretches with no reach to safety.
- `zones[]`, `points[]`, `path[]` (`[lat, lon, alt, t]`, at most about 4,000).
- The plan back, with ids.

## Settings

| Setting | Default | Meaning |
|---|---|---|
| `PLAN_SPEED` | 2, plane 15, boat 1.5 | Cruise speed. A plane uses its learned cruise airspeed once it has one |
| `PLAN_SPEED_MIN` | plane 11 | Stall plus a margin |
| `PLAN_CLIMB`, `PLAN_SINK` | 2.5, 2 | m/s a plan may count on |
| `PLAN_BANK` | 30 | Degrees in turns, sets a plane's turning circle |
| `PLAN_TURN_R` | 1.5, boat 4 | Turning radius of ground vehicles and boats |
| `PLAN_WIND` | 0, plane 5, boat 0.5 | Wind or current to plan for |
| `PLAN_GLIDE` | plane 8 | Glide ratio, motor off |
| `PLAN_ENDURANCE` | 30, plane 15 | Minutes on a full battery |
| `PLAN_RESERVE` | 20 | Percent kept unused |
| `PLAN_MARGIN` | 5, plane 15 | Metres kept from fence edges, besides turns |
| `PLAN_LAND_SLOPE` | 6 | Landing approach, degrees |

## Phases

### Phase 1: format, compiler and safe execution

| Stage | What | State |
|---|---|---|
| 1 | Format, compiler, timeline, enforced checks, `plan_check`, settings. Failsafe fix: a flying plane is never cut by an automatic failsafe. Exact leg check for today's missions | **Built**, 12 tests, checked on the phone |
| 2 | Running a compiled plan (below) | **Built**, 9 tests. Untested in the air |
| 3 | Fence layers in flight: a turn-back line before the fence for planes, the outer limit, and a staged contingency chooser (link lost, battery can't make home, motor out heard by the microphone, GPS lost) that goes to the best reachable zone. Terminate is in the format but stays off | |
| 4 | Ground station: Plan edits the file, checks it live, and shows the path as flown, action markers, zones and gaps, an altitude profile and the timeline. Inserting, reordering and multi-editing points; dragging fence corners. Saved plans on the phone, in backups | |
| 5 | Operate shows expected against actual times and ETAs; Review shows the plan against what happened. A plane simulator, so all of this runs end to end without hardware | |

#### Running a plan (stage 2)

- **Sending.** `plan_upload` compiles the plan against the phone's real home and loads it only if it checks out. The
  file is kept and compiled again after a restart. `mission_start` compiles it once more and refuses if it no longer
  checks out, or if its hash changed: what runs is what was checked.
- **Following the line.** The vehicle aims `NAV_LOOKAHEAD` metres ahead on the straight line from the last point, so
  it flies the path that was checked rather than cutting corners towards each point.
- **Distance triggers** fire at 0, every, 2 × every… metres along the leg while the vehicle is on the line, the same
  count the timeline shows. Any still due fire on arrival.
- **Orbits** go round their laps; **holds** circle round the point (planes) or stop.
- **Miss rules in flight.** A leg taking more than twice its planned time plus 30 s applies the arrival rule.
  Arriving more than the tolerance off the altitude applies the altitude rule: circling at the point until within
  tolerance or `maxS`, then `then`. `abort` flies home. Each is logged with its step.
- **Lateness.** Each arrival is compared with the plan's expected time ("reached point 4 (step field), 12 s late"), and
  `ops.mission` carries `lateS`.
- **A plane's height.** Pitch follows the height error (`NAV_ALT_P`), no steeper than `NAV_PITCH_UP` so a climb never
  costs speed, with more throttle when low (`NAV_THR_P`). When the propeller says it's below `PLAN_SPEED_MIN` it stops
  climbing and opens the throttle to `NAV_THR_MAX`: it gives up height, never speed. Height above home is barometric
  when the barometer was also read at home, else GPS.
- **Landing.** After the touchdown point: motor off, wings level, nose slightly up; disarmed once it's been down and
  still for 5 s.
- **Planes** run only sent plans, and only with `NAV_PLANE_MISS` set to 1. Old-style missions never fly a plane.

Done when a plane plan with a survey, a too-steep climb and a turn near the fence is caught when planning with clear
fixes; flown in the simulator, it handles a forced altitude miss and a link loss by its rules and goes to the right zone;
Operate shows the times drifting; and the same file set to rover or boat compiles with that vehicle's behaviour.

### Phase 2: AI, map layers and learning

- AI tools: check, read the timeline, draft and diff plans. Geometry tools (buffer, shape round a point, clip), place
  lookup and map features, and anchors clicked on the map, so the AI never invents coordinates. Loosening a restriction
  is always highlighted. The AI never sends or starts a plan.
- MapLibre with a 3D route view and offline areas. Terrain is an optional layer.
- Advisory layers as region packs with source and date: India airspace (airport bands computed from open data, plus
  imported zones), and no-go areas for land and water from OpenStreetMap. Notices, a disclaimer and logged
  acknowledgements, as in rule 3.
- Learning: logged misses suggest corrections to `PLAN_CLIMB`, `PLAN_SPEED` and `PLAN_GLIDE`, which a person approves.
- QGroundControl `.plan` import and export.

Out of scope: water depth, terrain following, a 3D globe, collision rules for boats, termination switched on, and
vehicles changing their own plan beyond the miss rules.

## Untested with real hardware

The compiler's numbers are only as good as the settings. Before flights beyond line of sight these need real flights:
climb, sink and glide ratio; the turning radius in wind; the motor-out signal from the microphone; and the contingency
chooser with a real link loss.

Running a plan on a plane is untested in the air: line following, the height controller and its gains, speed
protection (which depends on the propeller's airspeed estimate), hand-launched takeoff and the landing glide. Fly the
first plans with a pilot ready to take over, on a vehicle that has flown well in STABILIZE.

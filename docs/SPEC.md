# SmartPhoneX open specification

Version: `smartphonex.device/1`. Everything here is meant to be implemented or extended by anyone.

**Status:** the hardware protocol drivers and receiver input follow published protocol layouts but haven't been
tested with real parts yet. See "Untested with real hardware" below.

## Device description

A JSON file. `setup_status` returns a worked example, and the validator's errors are written to be fixed by an LLM.

| Field | Meaning |
|---|---|
| `schema` | Always `smartphonex.device/1` |
| `id`, `name` | Short identifier and display name |
| `class` | `fixed`, `wheeled`, `tracked`, `water`, `arm`, `fixed-wing`, `multi-legged`, `biped`, `multirotor`, `other` |
| `hazard` | `toy`, `light`, `heavy`, `flying`. `heavy` and `flying` get no AI control |
| `transports[]` | `id`, `type` (`usb-uart`, `tcp`, `preview`), `baud`, and for tcp `host` and `port`. `template` configures the text driver |
| `actuators[]` | `id`, `driver`, `transport`, `address`, `unit`, `min`, `max`, `neutral`, `max_rate` (units per second), `safe`, `mix`, `feedback` |
| `actuators[].safe` | `action`: `hold`, `neutral`, `limp` or `value`, with `value` for the last |
| `actuators[].mix` | Named input to gain. `forward`, `turn`, `pitch` and `roll` come from pilots and AI. Any other name is a custom input |
| `inputs[]` | Receiver: `driver` (`crsf`, `ibus`, `sbus`), `transport`, and `map` from `ch1` etc. to a name, including `arm` and `stop` |
| `controllers[]` | `balance` (`params.pitch` and `params.roll` map joints to gains, `gains.limit` caps the correction) or `fall_detect` (`gains.limit` in degrees) |
| `skills[]` | `id`, `type` (`pose`, `sequence`, `gait`, `set_inputs`, `stop`), `params` |
| `behaviors[]` | `id` and `tree` |
| `safe_state` | `on_link_loss` and `on_fall`: a skill id, `hold` or `stop` |
| `limits.ai_autonomy` | `none`, `supervised` or `unattended` |
| `commissioning` | Stage, tests, approvals and sign-off. Reset on import |

## Behaviors

Trees of `sequence`, `selector`, `condition`, `skill`, `wait`, `repeat` and `stop_skill`. They call skills and read
values, and have no other abilities. At most 200 nodes and 16 levels. Values: `attitude:roll`, `attitude:pitch`,
`attitude:heading`, `sensor:<id>:<axis index>`, `output:<actuator id>`.

## Control priority

1. Any stop, always, from anyone.
2. Receiver, once its sticks move.
3. Gamepad on the phone.
4. Phone screen.
5. Ground station.
6. On-phone behaviors and AI, in AUTO only.

## Extending it

| To add | Do this |
|---|---|
| A device | Write a description, and run commissioning |
| A text-command device | Use the `text` driver with a `template` such as `S{address}={value}\n`. Needs no code |
| A protocol | Implement `ProtocolDriver` (`Protocols.kt`): pure functions from values to bytes, with published test vectors |
| A transport | Implement `Transport` and return it from `TransportProvider` |
| A receiver format | Implement `ReceiverParser` |
| An estimator | Implement `AttitudeEstimator` |
| An AI provider | Implement `Provider` (`Assistant.kt`), or point the assistant at any OpenAI-compatible address |
| A ground station | Use the WebSocket messages below, or MCP or HTTP |

Sandboxed code plugins (tier 3 in `ANY_DEVICE.md`) aren't implemented yet. Controllers currently ship in the app.

## Connecting from outside

| Interface | Where | Authentication |
|---|---|---|
| MCP (stateless JSON over HTTP) | `POST /mcp` | `Authorization: Bearer <key>` |
| HTTP tools, described in `openapi.yaml` | `GET /api/tools`, `POST /api/tools/{name}` | Same |
| Ground station WebSocket | `/ws`, with `/video` for the camera | Token on the phone, session cookie on a relay |
| MAVLink 2 telemetry, read-only | UDP to port 14550 on a private address you set | None. Private networks only |
| ROS 2 | `tools/ros2_bridge.py` publishes attitude and outputs | Same as the HTTP tools |

On the phone directly, the key is the phone's token. Through a relay, it's a key from `relay/admin.mjs add-ai-key`.

### WebSocket messages

JSON text. To the phone: `cmd`, `ping`, `fault`, `probe`, `sensor_cfg`, `budget`, `camera_cfg`, `ai_access`, `param_set`,
`fc_action`, `setup_approve`, `device_import`, `mavlink_cfg`, `assistant_cfg`, `assistant_say`, `assistant_reset`.
From the phone: `hello`, `config`, `telemetry`, `fc`, `fcs`, `sensors`, `sensorStats`, `ack`, `cfgAck`, `params`,
`setup`, `device`, `assistant`, `pong`.

## Untested with real hardware

Tested only in software, or with stand-ins:

- USB-UART transport and the phone's USB permission flow
- LX-16A, Feetech and Dynamixel drivers (frames match published examples where tested), and half-duplex wiring
- CRSF, iBus and SBUS receiver input. SBUS also needs an adapter that inverts the signal and supports even parity
- Gamepad input, MAVLink with a real ground control app, and the ROS 2 bridge
- Gaits and balance on a real robot: the stored gaits are starting points and will need tuning

## Ground station protocol, version 2

`hello` now carries `protocol: 2` and `caps`, which say what to show for this device. A client that doesn't see `protocol` is talking to an older app.

The phone also sends `ops` twice a second: `readiness`, `armProblem`, `flightMode`, `owner`, `killed`, `mission`, `fence`, `triggers`, `trims`, `checklist`, `connections`, `caps`, `diag`, `report` and `ground`. Viewers don't get `missionData` or the fence's shape. Each entry in `readiness` and `connections` is `{id, label, status, detail, fix}`.

New `cmd` values, each answered by the usual `ack`:

| Command | Arguments |
|---|---|
| `kill` | none |
| `test_output` | `output`, `value`, `confirmed`. Send every 100 ms: it stops by itself after 400 ms of silence |
| `test_stop`, `diagnostics_run` | none |
| `test_record` | `test`, `status`, `detail` |
| `checklist_set` | `item`, `done` |
| `trim_set` | `output`, and any of `trim`, `reverse`, `lo`, `hi` |
| `trim_undo` | `output` |
| `trigger_save` | `trigger` |
| `trigger_delete`, `trigger_prepare`, `trigger_fire` | `id` |
| `mission_upload` | `mission`: `name`, `onEnd`, `onLinkLoss`, `items[]` of `lat`, `lon`, `alt`, `throttle`, `holdS`, `radiusM`, `triggers[]` |
| `mission_start`, `mission_pause`, `mission_resume`, `mission_skip`, `mission_abort` | none |
| `goto` | `lat`, `lon` |
| `fence_set` | `fence`: `polygon`, `keepOut`, `maxDistM`, `maxAltM`, `action` |
| `home_set` | none |
| `pose_capture` | `skill`, `steps`, `loops` |
| `face_express` | `expression`: one of `neutral happy excited sad angry surprised scared confused sleepy curious wink love`, or `auto` to hand back to the device's state. `seconds`: default 4, 0 holds until changed, at most 3600. AI may use it |
| `face_show` | `show`: true or false. Works with the face set to off. AI may use it |

Serial transports in a device description take `baud`, and optionally `data_bits` (5 to 8), `parity` (`none`, `even`, `odd`),
`stop_bits` (1 or 2) and `inverted` (idle-low, as SBUS is; FTDI adapters only). See `docs/USB_HARDWARE.md`.

App data and health, on the phone's own address only:

| Request | What |
|---|---|
| `GET /backup?token=`, `&media=1` | A backup zip. Secrets are never in it |
| `POST /backup/restore` with `Authorization: Bearer` | Restore one. Refused while armed. The app restarts to apply it |
| `GET /diagnostics?token=` | `exits` (why the app stopped), `crashes`, `update` |
| `GET /diagnostics/phone?token=` | The phone report, as a file |
| `GET /diagnostics/crash/{name}?token=` | One crash, as text |
| `POST /diagnostics/update` with `Authorization: Bearer` | Check for a newer release now |

Review files, with `?token=` on the phone or a session cookie on a relay (operators only): `GET /review/files`, `/review/file/log/{name}`, `/review/file/photo/{name}`, `/review/frames/{run}`, `/review/frame/{run}/{t}`.

A receiver channel mapped to `trigger:<id>` fires that trigger each time it goes high.

### The face

The phone can show a face, mostly for robots. Settings `FACE_MODE` (0 off, 1 after `FACE_IDLE_S` untouched, 2 robot),
`FACE_STYLE` (0 eyes, 1 eyes and mouth, 2 visor), `FACE_HUE` and `FACE_BRIGHT` are per device, like any setting.
`ops.face` reports `{shown, mode, requested, held, blocked}`; `blocked` says why it can't show, such as driving from the phone's screen.
Safety states always show over an expression: failsafe is alarmed and red, and kill shows crossed-out eyes.
The face has no buttons. A tap anywhere closes it and shows the controls, with STOP. MCP tools: `vehicle_set_face`, `vehicle_show_face`.


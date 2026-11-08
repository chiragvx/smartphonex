# SmartPhoneX

Turn an Android phone into the brain of a robotic device: a rover, boat, arm, walker or something you built yourself.
Describe the device to the LLM of your choice, and it walks you through setup. The phone then runs on its own, with
no LLM, internet or PC needed, and takes commands from AI, a ground station, or a receiver.

**Status: experimental.** The software has been tested on one phone and an emulator. The hardware output paths have
not been tested with real parts, and there's no independent backup if a phone freezes. Keep a physical way to cut power, and
don't fly anything or run anything that could hurt someone. See [docs/SPEC.md](docs/SPEC.md#untested-with-real-hardware).

## Quick start

1. Build with `./gradlew assembleDebug`, or install a release APK, then open the app.
2. On a computer on the same network, open the address on the phone's screen and enter the pairing code.
3. Pick a device in **Vehicle and control law**. The examples show live on the output preview, with nothing connected.
4. Set up your own device with any LLM, through **Setup assistant**, MCP or the HTTP tools.

For use over mobile data, run your own relay: see [deploy/README.md](deploy/README.md).

## Connecting hardware

The only extra part is one USB-UART adapter (an FTDI FT232R is recommended). Read
[docs/USB_HARDWARE.md](docs/USB_HARDWARE.md) before wiring anything: it covers voltages, what never to connect,
and how one adapter drives many servos.

## How it stays safe

- **The LLM proposes, and the phone decides.** Limits are enforced on the phone, and no tool can approve anything.
- **Hardware stays closed** until a person has gone through commissioning and signed off.
- **Commissioning is staged**, from a preview, through tiny first motions, to real failure tests.
- **Stop always wins.** A person at the controls always outranks AI.
- **Not for** weapons, anything carrying people, medical devices, or machinery that could seriously injure someone.

## Layout

| Path | What |
|---|---|
| `app/` | The Android app: controller, autopilot, device engine, ground station |
| `relay/` | Server for remote use: sign-in, roles, forwarding |
| `tools/` | Test and measurement scripts, and bridges |
| `docs/SPEC.md` | The open device format, protocol and plugin interfaces |
| `docs/USB_HARDWARE.md` | Adapters, wiring, voltages and keeping the phone safe |
| `docs/PERFORMANCE.md` | Stress test results and what the app does under load |
| `docs/NAVIGATION_SAFETY.md` | How GPS errors are kept from causing harm, and what's up to you |
| `docs/PHONES.md` | Which phones work, from shared phone reports |
| `docs/THREAT_MODEL.md`, `SECURITY.md` | Security design and how to report a problem |
| `docs/RELEASING.md` | Signing, versions and releases |
| `deploy/` | Your own relay with Docker and automatic HTTPS |
| `ROADMAP.md` | Phases from here to 1.0 |
| `ANY_DEVICE.md`, `FLIGHT_CONTROLLER.md` | Design plans |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Licensed under [Apache-2.0](LICENSE). Report vulnerabilities privately: see [SECURITY.md](SECURITY.md).

# Contributing

Devices, protocols, gaits and phone test reports are all welcome. Contributions are accepted under the Apache-2.0 license.

## Adding a device pack

1. Add a description to `app/src/main/assets/packs/`, named after its `id`. The format is in [docs/SPEC.md](docs/SPEC.md).
2. Run `./gradlew testDebugUnitTest`. The conformance test checks every shipped pack.
3. Say what hardware you really tested it on, and what you saw in the failure tests. Untested is fine if you say so.

Never include commissioning results or sign-off. They apply to one physical device, so they restart on import.

## Adding a protocol driver

Implement `ProtocolDriver` in `Protocols.kt`. Keep it pure, from values to bytes, and add tests using the protocol's own
published examples where they exist, as `DeviceTest` does. Say whether it's been tried on real hardware.

## Rules that stay

- Nothing bypasses the safety layer in `DeviceEngine`, and real output stays closed until sign-off.
- An LLM can't approve anything. Approvals only come from a person, through the ground station or phone.
- The AI stays out of fast control loops.
- Don't weaken these for convenience, even behind a setting.

## Tests

| Run | For |
|---|---|
| `./gradlew testDebugUnitTest` | Core logic |
| `cd relay && npm test` | Relay security |
| `tools/e2e.mjs`, `device-e2e.mjs`, `fc-e2e.mjs`, `relay-e2e.mjs`, `qr-e2e.sh` | Live checks on a phone or emulator |

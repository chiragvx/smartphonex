# Contributing

Devices, protocols, gaits and phone reports are all welcome. For a phone, attach its report from **Setup, App health** to an issue: see [docs/PHONES.md](docs/PHONES.md). Contributions are accepted under the Apache-2.0 license.

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
| `./gradlew lintDebug` | Android lint. New issues fail; `app/lint-baseline.xml` holds the old ones |
| `cd web && npx tsc --noEmit && npx vite build`, then `node tools/size-budget.mjs` | Ground station. Commit the built files |
| `tools/e2e.mjs`, `device-e2e.mjs`, `fc-e2e.mjs`, `backup-e2e.mjs`, `face-e2e.mjs`, `diag-e2e.mjs`, `relay-e2e.mjs`, `qr-e2e.sh` | Live checks on a phone or emulator |

CI runs everything except the live checks on every push. If a change alters the shape of a saved file, add a migration: see [docs/RELEASING.md](docs/RELEASING.md).

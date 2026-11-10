## What this changes

<!-- What and why, in a few lines. Link the issue if there is one. -->

## How it was tested

- [ ] `./gradlew testDebugUnitTest lintDebug`
- [ ] Ground station: `npx tsc --noEmit && npx vite build` in `web/`, and the built files are committed
- [ ] `npm test` in `relay/` (if the relay changed)
- [ ] Live on a phone or emulator: <!-- which tools/*.mjs checks, or what you did by hand -->
- [ ] On real hardware: <!-- what, or "not tested with hardware" -->

## Safety

- [ ] Nothing bypasses STOP, limits, the safety layer, sign-off or a person's approval
- [ ] Saved file formats are unchanged, or there's a migration

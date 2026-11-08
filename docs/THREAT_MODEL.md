# Threat model

What SmartPhoneX defends against, how, and what it doesn't. Update this file in the same change as anything that moves a trust boundary.

## What's worth protecting

| Asset | Why |
|---|---|
| Control of the device | Movement can injure people or break things |
| The safety layer: stop, limits, failsafes, approvals | It's what keeps a bug or an attacker from causing harm |
| The phone's access token | It grants full operator access on the local network |
| Camera and location | Personal data, and they reveal where the device and its owner are |
| The relay's accounts, sessions and vehicle key | Remote control over the internet |
| AI provider keys | Cost and account abuse |

## Who might attack

| Attacker | Can |
|---|---|
| Someone on the same Wi-Fi | Reach the phone's server on port 8080 |
| Anyone on the internet | Reach a public relay's sign-in, enrollment and WebSocket endpoints |
| A viewer account | See telemetry, and nothing more |
| An AI agent, or text that steers it (prompt injection) | Call the tools it was given |
| A malicious pack, layout, panel or backup file | Whatever the importer lets through |
| A thief with the phone | Everything on it, if it's unlocked |

## Boundaries and defenses

| Boundary | Defense | Checked by |
|---|---|---|
| Local network to the phone | 128-bit random token, compared in constant time. Browsers get it through a 6-digit pairing code shown on the phone, with lockouts | `PairingTest`, e2e |
| Internet to the relay | Passwords (scrypt), sessions, roles, per-account and per-address sign-in lockouts, same-origin checks | `relay.test.mjs` |
| Proxy to the relay | Only the hop the proxy added is believed. `CF-Connecting-IP` only in `cloudflare` mode | `relay.test.mjs` |
| Relay to the phone | Per-vehicle key, enrolled by a one-time QR code that expires after 5 minutes. HTTPS only, except local test addresses | `relay-e2e`, `qr-e2e` |
| AI to hardware | The phone validates every command. AI can't arm aircraft, approve, widen limits, or undo a person's stop. Commands expire | `ControllerTest`, `DeviceTest` |
| Imported pack to hardware | Schema and physical checks. Commissioning restarts on import, so hardware stays closed until a person signs off | `DeviceTest` conformance |
| Backup file to the phone | Allowed names only, no `..` or absolute paths, size and count limits, unpacked beside the live files and swapped in at restart. Secrets can't be overwritten. Refused while armed | `AppDataTest` |
| Phone reports and crashes | Kept on the phone and shared only by a person. Reports carry the model, never location or IDs. Crash files are read only by their own names | `diag-e2e` |
| Update feed to the phone | HTTPS only, and links are only shown, never downloaded or installed by the app | `VersionsTest`, `diag-e2e` |
| Phone to Android backups | Cloud backup and device-to-device transfer are off, so the token can't follow the data to another phone | Manifest |
| Phone to AI provider | Key stored encrypted with the Android Keystore. Plain HTTP only to private network addresses | `Assistant` |

## Known gaps

- **Plain HTTP on the local network.** Anyone who can watch the Wi-Fi can read the token in transit. Use a trusted network, or the relay over HTTPS. Local TLS needs a certificate story first (planned).
- **No hardware watchdog.** A frozen phone can't stop a vehicle. Keep a way to cut power.
- **No general rate limits on the relay.** Sign-in and enrollment lock out, but other endpoints don't limit request rates (planned).
- **The token is long-lived.** Rotating it means reinstalling or clearing data today (planned: rotate from the phone).
- **Release builds aren't shrunk or obfuscated.** That isn't a security boundary, but it's noted.
- **No external review yet.** Planned before 1.0, per `ROADMAP.md`.

# Security policy

SmartPhoneX moves real hardware, so a security bug can be a safety bug. Thank you for reporting one.

## Reporting

Please don't open a public issue for a vulnerability. Use GitHub's private vulnerability reporting on this repository
(Security, then Report a vulnerability). Include what you found, how to reproduce it, and what it lets someone do.

We aim to reply within 7 days, and to fix high and critical issues before the next release. We'll credit you in the
release notes unless you'd rather we didn't.

## In scope

- The Android app: its local server, pairing, tokens, MCP and HTTP tools, backup and restore, and the safety layer.
- The relay: sign-in, sessions, roles, enrollment, forwarding, and the Docker image.
- The ground station web app.
- Anything that lets an AI, a viewer or a remote party do more than its role allows, or get past a stop, limit or approval.

## Out of scope

- Attacks that need someone already holding the phone unlocked, or the relay's server.
- The temporary tunnel used for testing (`PHASE2.md`). It isn't a deployment.
- Physical safety problems with no software cause. Those belong in a normal issue.

## Supported versions

Only the latest release gets security fixes until 1.0.

See [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md) for what the project defends against and how.

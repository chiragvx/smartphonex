# Documentation

Everything about SmartPhoneX, by what you're trying to do.

## Use it

| Page | Read it when |
|---|---|
| [Getting started](GETTING_STARTED.md) | You're installing it, pairing, picking a device and running the ground test |
| [Ground station guide](GROUND_STATION_GUIDE.md) | You want to know what a screen or button does |
| [USB hardware](USB_HARDWARE.md) | Before wiring anything: adapters, voltages and what never to connect |
| [Mission planning](PLANNING.md) | You're writing plans, or wondering why the phone refused one |
| [Run your own relay](../deploy/README.md) | You want to use it over mobile data |
| [Phones](PHONES.md) | You're choosing a phone, or want to add yours |

## Understand it

| Page | What it covers |
|---|---|
| [Architecture](ARCHITECTURE.md) | How the phone, ground station, relay and AI tools fit together |
| [Open specification](SPEC.md) | The device format, behaviors, control priority, plugin points and the ground station protocol |
| [HTTP API](openapi.yaml) | The HTTP tools, in OpenAPI form |
| [Performance](PERFORMANCE.md) | Measured timing under stress, and what the app does under load |

## Safety and security

| Page | What it covers |
|---|---|
| [Navigation safety](NAVIGATION_SAFETY.md) | How GPS errors are kept from causing harm, and what's up to you |
| [Speed safety](SPEED_SAFETY.md) | Keeping bad speed data away from the throttle |
| [Threat model](THREAT_MODEL.md) | What's protected, from whom, and the known gaps |
| [Security policy](../SECURITY.md) | How to report a vulnerability |

## Work on it

| Page | What it covers |
|---|---|
| [Development](DEVELOPMENT.md) | Building, previewing and testing each part |
| [Contributing](../CONTRIBUTING.md) | Device packs, protocol drivers and the rules that stay |
| [Releasing](RELEASING.md) | Signing, versions and releases |
| [Roadmap](../ROADMAP.md) | The phases from here to 1.0 |

## Design plans

The plans each part was built from. They record decisions and reasoning, and parts of them describe work still to come.

| Plan | About |
|---|---|
| [PRD-1.0.txt](design/PRD-1.0.txt) | The original product requirements |
| [PLAN.md](design/PLAN.md) | Decisions and status, which update the original requirements |
| [ANY_DEVICE.md](design/ANY_DEVICE.md) | Any robotic device, set up with any LLM |
| [FLIGHT_CONTROLLER.md](design/FLIGHT_CONTROLLER.md) | The phone as a flight controller |
| [GROUND_STATION.md](design/GROUND_STATION.md) | The universal ground station |
| [PHASE2.md](design/PHASE2.md) | The runbook for testing over mobile data |

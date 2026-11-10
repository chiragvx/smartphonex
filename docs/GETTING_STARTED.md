# Getting started

This page takes you from a bare phone to a device you've tested on the ground. Do the steps in order. Each one is safe
to stop at.

> [!WARNING]
> SmartPhoneX is experimental, and its hardware output paths have not been tested with real parts. Keep a physical way to
> cut power, restrain anything that can move, and never use it for anything that could hurt someone.

**Contents:** [1. Install](#1-install) · [2. Pair a ground station](#2-pair-a-ground-station) ·
[3. Pick a device](#3-pick-a-device) · [4. Run the tests](#4-run-the-tests) · [5. Connect hardware](#5-connect-hardware) ·
[6. Your own device, with an LLM](#6-your-own-device-with-an-llm) · [7. The ground test](#7-the-ground-test) ·
[8. Remote use](#8-remote-use-over-mobile-data)

## 1. Install

**You need** an Android 8.0 (API 26) or later phone with a gyroscope, accelerometer and GPS. [PHONES.md](PHONES.md) lists
the phones that have been measured. For hardware, the phone also needs USB host support (most do).

Install a release APK from [Releases](https://github.com/chiragvx/smartphonex/releases) when one is published, or build it yourself:

```bash
git clone https://github.com/chiragvx/smartphonex.git
cd smartphonex
./gradlew assembleDebug        # JDK 17 and the Android SDK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then, on the phone:

1. Open **SmartPhoneX** and allow location. Without it there's no GPS, so no map, missions or fence.
2. In Android's settings, set battery use for SmartPhoneX to **No restrictions**, or Android may stop it in the background.

> [!TIP]
> Debug and release builds are signed with different keys, so one can't update the other. To switch, take a backup first.
> See [RELEASING.md](RELEASING.md#debug-and-release-builds).

## 2. Pair a ground station

The phone serves the ground station itself. Nothing is installed on the computer.

1. Put the phone and a computer, tablet or second phone on the same Wi-Fi.
2. The phone's home screen shows a web address and a pairing code. Open the address in a browser, or scan the phone's QR code.
3. Enter the code. The browser keeps its access from then on.

You'll land on **Operate**. The bar along the top shows whether it's armed, the drive mode, who's in control, GPS,
battery and the link. **STOP** is always at the top right. The [ground station guide](GROUND_STATION_GUIDE.md) explains every screen.

> [!IMPORTANT]
> On the local network, anyone with the access token has full control. Only pair devices you trust, and use the relay
> (step 8) for viewer-only accounts.

## 3. Pick a device

Open **Setup → Device** and choose what this phone controls. The examples ship with the app:

| Pack | What it is |
|---|---|
| Two-wheel rover | Differential drive: two motors, tank steering |
| Pan-tilt camera mount | Two servos that point the camera |
| Small robot arm | Joints driven by servos |
| Hexapod walker | Six legs, with gaits |
| Small biped, 12 joints | Two legs, with balance |

A new device starts **closed**: you can watch its outputs move in the preview, but nothing is sent to hardware until a
person has gone through commissioning and signed off. The format of these packs is in [SPEC.md](SPEC.md#device-description).

## 4. Run the tests

Open **Test** and choose **Run tests**, with the phone lying still. It checks the accelerometer, gyroscope, GPS, control
timing and the link-loss failsafe, and saves a report. **Test link** adds five seconds of round-trip timing to the next report.

**Ready to arm?** on the same page lists everything that would stop arming, and how to fix each one.

## 5. Connect hardware

The only extra part is a USB-UART adapter. An FTDI FT232R is recommended. It connects through the phone's USB-C port
to a servo controller, ESC or receiver.

**Read [USB_HARDWARE.md](USB_HARDWARE.md) before wiring anything.** It covers adapters, voltages, what must never be
connected to the phone, and how one adapter drives many servos.

Then commission the device. The setup tools lead it, used by the **Assistant** or any LLM over MCP (step 6). It goes in
stages, from the preview, through small, slow first motions, to real failure tests. Each actuator's first motion and the final
sign-off wait for a person to approve them, in the ground station or on the phone. Outputs open only after that sign-off.
Under **Test → Output tests**, each output runs only while you hold its button, and stops as soon as you let go.

## 6. Your own device, with an LLM

Describe your device in plain words, and an LLM writes the description, explains the wiring and walks you through
commissioning. It can only **propose**: limits are checked on the phone, and only a person can approve.

Pick one of these:

- **Setup → Assistant** on the ground station. Enter an API key for your provider, or point it at any
  OpenAI-compatible address, including a local model.
- **An MCP client, such as Claude Code.** **Setup → Connect** shows the exact command, for example:

  ```bash
  claude mcp add --transport http smartphonex http://<phone-address>:8080/mcp \
    --header "Authorization: Bearer <token>"
  ```

- **Plain HTTP tools.** See [openapi.yaml](openapi.yaml) and [SPEC.md](SPEC.md#connecting-from-outside).

Once set up, the phone needs no LLM, internet or PC to run.

## 7. The ground test

**Test → Ground test** is a checklist that the phone grades from what it has actually measured. Each stage turns green only
on evidence:

| Stage | What proves it |
|---|---|
| Phone self-test | **Run tests** passed |
| Link | A link test with acceptable round-trip time and loss |
| Each output moves the right way | Every output run both ways, then confirmed by a person who watched it |
| STOP disarms it | STOP pressed while armed |
| It follows a plan | A route of at least 3 points reached in order, staying near the line, on time |
| The fence catches it | The vehicle taken outside the fence while armed, and the fence acted |
| It stops when moving | Driving only: stopping time and distance, measured from GPS speed |

Choose how:

- **Walk it.** Carry the phone round the plan, armed, with nothing connected that can move. This proves navigation,
  arrivals, timing and the fence on real GPS, with no risk.
- **Drive it.** The vehicle drives itself. This adds the stopping test. Only do this after walking it.

**Make a test plan here** draws a small square and a fence around the phone and opens it in **Plan**. Send it, then
**Hold to start** on Operate. **Start again** clears the evidence for this vehicle.

## 8. Remote use, over mobile data

On Wi-Fi, nothing else is needed. To reach the phone over the internet, run your own relay. It's one small Node.js
service that signs people in, gives them **operator** or **viewer** roles, and forwards traffic. The phone connects out
to it, so it needs no open ports.

- **For real use:** [deploy/README.md](../deploy/README.md) runs it with Docker and automatic HTTPS.
- **For a quick test:** [design/PHASE2.md](design/PHASE2.md) runs it on a PC behind a temporary tunnel.

The phone refuses a relay without HTTPS. Its threat model is in [THREAT_MODEL.md](THREAT_MODEL.md).

## Next

- [Ground station guide](GROUND_STATION_GUIDE.md): every screen and control
- [Mission planning](PLANNING.md): the plan format, and the checks the compiler makes
- [Navigation safety](NAVIGATION_SAFETY.md): what the phone does about GPS errors, and what's up to you
- [Architecture](ARCHITECTURE.md): how it works inside

# Performance under stress

Android isn't a real-time system. An app can't get guaranteed timing, so SmartPhoneX is built to keep its deadlines
with margin, measure itself, and fail safe when it can't. This page lists what other projects ran into, what we
measured, and what the app does about it.

## How to measure

```
node tools/stress.mjs <host:port> <token> <adb> <serial> [seconds-per-stage] --out results/stress.json
```

It adds load in six stages and never removes it until the end: idle; armed and driven at 20 Hz; camera streaming; four
more ground stations and an AI polling at 10 Hz; busy loops on all but two CPU cores, standing in for other apps; then the screen off.
Each stage reports the loop's rate and worst step, link round trip, telemetry gaps, video rate, false failsafes, app
CPU, thermal status and clock speed. Every busy loop has its own time limit, so the phone is never left loaded.

## Known problems with phones as controllers

From the research for this page, ranked by how likely each is to cost a deadline:

| Problem | What we do |
|---|---|
| **Vendors killing the app.** Xiaomi in particular stops apps, foreground services included, to save battery | Connected shows whether the app is exempt from battery limits, with a button to allow it, and on Xiaomi a button for Autostart. Keep the receiver's own failsafe set |
| **Heat.** Phones throttle under sustained load | Android's thermal status is watched: camera frames drop to a half at severe and a quarter at critical. The control loop is never slowed. The phone report records thermal headroom |
| **No real-time scheduling.** Java's top priority is only a nice value of −8 | Time-critical threads (control loop, flight sensors, output) ask for −19, the most an app may have, and report their work to an Android 12+ hint session so the system keeps them on fast cores |
| **Late wake-ups in the background.** Background threads get 40 ms timer slack | The app runs as a foreground service and keeps the screen on |
| **Garbage collection pauses** | Kept short by the runtime. The stress test watches the worst step |
| **Wi-Fi power save** holds packets about 100 ms | The low-latency Wi-Fi mode, which works while the app is in front with the screen on |
| **Camera traffic delaying commands** | Video has its own connection, and stale frames are dropped |
| **Sensor batching and the 200 Hz cap** | Sensors are read with no batching delay. 200 Hz is enough for our loop |
| **USB serial dropouts** | See [USB_HARDWARE.md](USB_HARDWARE.md) |

## Measured on the Redmi Note 13 Pro 5G

After the changes below, six 40-second stages over USB (`results/stress-after.json`), started at 40 °C:

| Stage | Loop, lowest | Worst step, typical / max | Round trip p50 / p99 / max | Telemetry gap, max | Video | False failsafes | App CPU |
|---|---|---|---|---|---|---|---|
| Idle | 100 Hz | 11 / 18 ms | 13 / 69 / 129 ms | 194 ms | n/a | 0 | 122% |
| Armed, driven at 20 Hz | 100 Hz | 11 / 13 ms | 8 / 92 / 154 ms | 255 ms | n/a | 0 | 110% |
| + camera, 10 fps asked | 100 Hz | 11 / 14 ms | 7 / 133 / 198 ms | 233 ms | 8.5 fps | 0 | 124% |
| + 4 clients, AI at 10 Hz | 100 Hz | 13 / 19 ms | 6 / 143 / 194 ms | 284 ms | 8.4 fps | 0 | 234% |
| + 6 of 8 cores busy | 92 Hz | 29 / 90 ms | 19 / 281 / 413 ms | 419 ms | 7.6 fps | 0 | 164% |
| + screen off | 100 Hz | 14 / 50 ms | 7 / 255 / 366 ms | 438 ms | 8.1 fps | 0 | 177% |

App CPU is per core, so 100% is one core of eight.

What this says:
- The control loop keeps 100 Hz under everything the app itself does. Only other apps hammering every core slow it, to about 92 Hz with a 90 ms worst step in this run (189 ms in another). That's under the 500 ms command timeout, but it's why the device's own failsafe must stay set.
- No false failsafes, even with the screen off and the CPU loaded.
- The phone has no hint session: HyperOS doesn't provide one. The priority boost to −19 does apply.

### Mistakes in the first run

The first run, before these changes, reported failsafes while driving, telemetry stalls of up to a second and under 1 fps of video. All three were the test's fault:
- It read the phone's CPU and temperature with a blocking call taking about a second. That paused its own drive commands, so the phone failed safe as designed.
- It didn't acknowledge video frames. The phone sends the next frame only after an acknowledgement, or 1.5 s without one.
- Its CPU load had no time limit of its own, so when the USB link dropped it kept seven cores busy for half an hour.

All three are fixed in `tools/stress.mjs`. The first run's one real finding was CPU: the app used about 1.3 cores idle, mostly drawing the screen.

## Changes

- The screen refreshes 5 times a second instead of 10, and not at all while the app is out of sight.
- While the face shows, nothing else is drawn, and the face runs on a 30 fps timer instead of every screen refresh.
- Time-critical threads get the top app priority (checked: −19 on the phone), with a hint session where Android offers one.
- Low-latency Wi-Fi mode, heat shedding for the camera, and the battery-limit and Autostart checks.
- USB: line settings, FTDI latency, chip checks, and unplug detection. See [USB_HARDWARE.md](USB_HARDWARE.md).

## Still to measure

- A long run with the camera on, for heat. These runs were too short to reach throttling.
- The same test over Wi-Fi and 4G instead of USB.
- App CPU with many clients (234%) is mostly turning state into JSON for each of them. Sharing one encoded message across clients is the next saving.
- `tools/link-probe.mjs` drives while recording the phone's own timestamps, to tell a stalled phone from a link holding messages.

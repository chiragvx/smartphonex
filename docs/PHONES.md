# Phones

Which phones work, and how well. Every row comes from a phone report: **Setup, App health, Download phone report** in
the ground station. The report has the phone's model, sensors, GPS signal and timing. It has no location, serial number or account.

To add your phone, open an issue with the report attached, and say whether GPS was measured indoors or outside.

| Phone | Android | Gyro and accel | Loop, worst step | USB host | GPS signal (top 4) | Unexpected stops | Notes |
|---|---|---|---|---|---|---|---|
| Xiaomi Redmi Note 13 Pro 5G (2312DRA50I) | 14 | 211 Hz, 211 Hz | 100 Hz, 11.4 ms | Yes | 24 to 27 dB-Hz indoors | 0 of 11 recorded | The reference phone. Blocks adb taps, so screen tests run on an emulator. GPS accuracy indoors was 10 to 35 m |
| Android TV emulator (API 36) | 16 | None | 100 Hz | No | None | Not measured | Development only. No battery, so it runs as mains powered |

## What the columns mean

- **Gyro and accel:** the rates the app actually gets, not the sensor's rated maximum. Stabilized modes need at least `ARM_RATE_MIN` (30 Hz by default).
- **Loop, worst step:** the control loop's rate and its slowest step in the report. A worst step over 50 ms counts as a stall.
- **GPS signal:** the average of the four strongest satellites. Under open sky, 35 dB-Hz or more is usual. Under 30 means a poor view of the sky, and missions get less accurate.
- **Unexpected stops:** times Android ended the app other than by an update or a person stopping it, of all the stops it recorded (Android 11 and later). Phones that stop it a lot need battery saving turned off for SmartPhoneX.

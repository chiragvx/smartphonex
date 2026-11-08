# From the phone's USB-C port to the device's pins

The only extra hardware is one USB-UART adapter. This page covers how the data gets from the app to the pins, which
adapter to buy, how to wire it, and what the app does on its side. Nothing here has been tried with a real adapter
yet: claims marked *(unverified)* come from datasheets, the library's source or other people's reports.

## The path

```
App (output thread, every 10 ms)
  -> usb-serial-for-android library -> Android USB host (OTG)
  -> phone USB-C port -> OTG adapter or C-to-C cable
  -> USB-UART adapter (FTDI, CP2102N, CH340 ...) -> TX, RX, GND pins
  -> the device: a serial receiver or ESC (CRSF, iBus, SBUS), serial bus servos, or a toy car's H-bridge
```

Bytes leave the phone as USB packets of up to 64 bytes. The adapter's chip turns them into a serial signal on TX.
A CRSF frame (26 bytes at 420000 baud) takes about 0.6 ms on the wire, an SBUS frame (25 bytes, 8E2 at 100000) about 3 ms.
Expect 2 to 5 ms from the app to the pin, with occasional spikes of 10 to 50 ms when Android is busy *(unverified estimate)*.

## Which adapter

| Chip | Works on Android | CRSF 420000 | SBUS 100000 8E2 | Bus servos 1000000 | Inverted line (SBUS) | Notes |
|---|---|---|---|---|---|---|
| FTDI FT232R, FT-X | Yes | Yes | Yes | Yes | Yes, set once with FTDI's FT_PROG tool on a PC | **Recommended.** The app sets its receive delay to 1 ms |
| CP2102N | Yes | Yes | Yes | Yes | No | Good, apart from SBUS |
| CH340, CH341 | Yes | Yes *(unverified on hardware)* | Yes | Yes | No | Cheapest. Many boards have a 3.3/5 V jumper |
| CP2102 (original, not N) | Yes | **No**: it silently runs at 460800 | **No**: it runs at 115200 | Yes | No | Avoid for CRSF and SBUS. The app warns when it sees a CP210x |
| PL2303 | Yes | Model-dependent | Yes | Yes | No | Many fakes. Avoid |

Any of these can be told apart in the app: **Connected, USB adapters** lists the chip.

## Wiring

- **Ground first.** The adapter's GND must connect to the device's ground.
- **TX to the device's input**, and RX to its output if it sends anything back.
- **Logic level.** Use the 3.3 V setting if the board has a jumper. Most 5 V receivers and servos read 3.3 V as high *(unverified per device)*.
- **Never power motors or servos** from the phone, the adapter's 5 V pin or VBUS. The phone supplies only about 0.5 A over OTG *(varies by phone)*.
- **TX idles high.** Wire it so that an idle line, or an unplugged adapter, can't mean throttle on.
- **SBUS** is inverted: idle low. Either an FTDI adapter set to invert with FT_PROG, or a small inverter, which breaks the one-adapter rule.
- **Bus servos** (Feetech, LX-16A, Dynamixel) use one wire for both directions. Join RX to the data line and TX through a resistor of about 470 Ω to 1 kΩ, or use a ready-made bus servo adapter board.
- **Charging while it runs** needs a USB-C hub with power pass-through. Not every phone allows it, so test yours *(unverified)*.

## Voltages, and keeping the phone safe

There are three separate electrical worlds, and the adapter sits between the first two:

| Part | What's on the wire | Voltage |
|---|---|---|
| Phone to adapter (USB) | Two data lines (D+ and D-), plus 5 V power the phone supplies and ground | Data: USB signalling, about 3.3 V. Power: 5 V from the phone |
| Adapter to device (serial) | TX, RX, and optionally DTR, RTS, CTS | 3.3 V or 5 V, set by the board's jumper (the FT232R's VCCIO pin). 0 V is a 0, the set voltage is a 1 |
| Device to servos and motors (power) | Servo cables: signal, plus (red), ground | Plus is 5 to 8.4 V from a BEC or battery, at amps |

The phone only ever sees USB. Its data lines go into the adapter's USB chip, never to the servo side, so the serial
voltages can't reach the phone directly. What can hurt it is power and ground, so:

1. **Never connect the device's power to the adapter's 5 V or VCC pin.** An ESC's BEC or a servo battery fed into that pin pushes current back into the phone's USB port. Leave the adapter's VCC pin unconnected, or cut the red wire on that cable.
2. **Match the logic level to the device.** Many receivers (ExpressLRS, for one) are 3.3 V parts. Set the adapter to 3.3 V unless the device's documentation says its input takes 5 V. Whether the adapter's own RX tolerates 5 V depends on the board *(check yours)*.
3. **Ground: one shared, short connection, and no motor current through it.** Motor current flowing back through the USB ground causes noise, USB dropouts, and at worst damage. Connect the signal ground near the receiver or controller, and keep motor and battery wiring separate.
4. **For anything with real motors, add a USB isolator** between the phone and the adapter. It's about USD 10, often sold as an ADuM3160 module, and works at the FT232R's full-speed USB. It's the only extra part that's strongly recommended, and it breaks the ground path completely.
5. **A 220 to 470 Ω resistor in series with TX** is a cheap guard against wiring mistakes and static. It doesn't affect speeds up to about 1 Mbaud on short wires *(unverified at 1 Mbaud)*.
6. **The phone limits OTG power** and normally shuts the port off on a short *(varies by phone)*. The adapter draws only about 15 to 25 mA.

**Does it work flawlessly?** The data path does, in principle. It's digital, so as long as the levels match, the bytes arrive
exactly. The risks are wiring mistakes and electrical noise from motors, not the signalling. Items 1 to 4 deal with those.

**Note on servo cables.** The adapter doesn't make PWM. TX carries serial data (CRSF, SBUS, iBus or a servo bus). A
receiver, ESC, servo controller board or bus servo with a serial input reads it and drives the servos. Plugging TX
straight into a standard servo's signal wire won't work (see below).

## How many devices one adapter can drive

A typical FT232R breakout has 6 pins: GND, CTS, VCC, TX, RX and DTR. That's one serial link and two control lines.
It's enough for most projects, because a serial link carries many channels:

| Need | One adapter is enough? | How |
|---|---|---|
| A car, boat or plane | Yes | CRSF or SBUS carries 16 channels on one wire, to a serial receiver, ESC or flight controller |
| Up to 16 standard servos | Yes | An SBUS-to-PWM decoder board, or a serial servo controller such as a Pololu Maestro (up to 24) or Lynxmotion SSC-32U (32) |
| A biped, hexapod or arm | Yes | Serial bus servos daisy-chain on one wire, each with its own ID: up to 253 per bus. A hexapod's 18 or a biped's 12 is easy |
| Two different serial devices, such as servos plus a receiver for a pilot | No | Two adapters on a USB hub. Not every phone powers a hub, so test yours |
| Several separate devices | No | A multi-port adapter: FT2232H (2 ports) or FT4232H (4), both supported by the app's library |
| Many plain on/off outputs | Partly | DTR and RTS give two. A full FT232R board exposes 8 pins usable in bit-bang mode, which the app doesn't use yet |

The limits are speed and power, not pins. At 1 Mbaud, one sync-write packet updating a hexapod's 18 servos takes about 1.3 ms on the wire, so a 100 Hz update has plenty of room. Reading positions back is slower, since each servo answers in turn. Servo power always
comes from its own battery or BEC, sized for all servos moving at once.

## What it can't do well

- **Standard servo PWM from TX.** A servo wants a 1 to 2 ms pulse every 20 ms. A UART adds stop bits to every byte and its timing comes from Android, so the pulse wobbles by around half the servo's range. Use a serial-input servo board or ESC instead.
- **On/off from RTS or DTR** works for simple H-bridge inputs, but the pins are active-low and keep their last state if the app stops while USB stays connected *(unverified)*. Don't use them for anything that must stop.
- **Possible later:** FTDI chips have a bit-bang mode that times up to 8 PWM pins in hardware. It isn't built, and has a known timing erratum on the FT232R.

## What the app does

| What | How |
|---|---|
| Line settings | Each transport in a device description has `baud`, and optional `data_bits`, `parity` (`none`, `even`, `odd`), `stop_bits` and `inverted`. SBUS is `{"baud":100000,"parity":"even","stop_bits":2,"inverted":true}` |
| Inverted lines | Refused on anything but an FTDI adapter, with the reason |
| Unsupported settings | Refused with the chip's reason, instead of failing quietly |
| FTDI receive delay | Set to 1 ms, from the default 16 ms, so servo replies arrive at once |
| Original CP2102 | Named in the adapter list with a warning about 420000 and 100000 baud |
| Unplugged adapter | The read side reports it, the next write fails, and **Connected, Vehicle output** shows the error. The app reopens the adapter when it's back |
| Permission | Plugging in an adapter offers SmartPhoneX. Tick "use by default" and Android won't ask again |
| Output pacing | Its own thread, at the highest priority an app can have, writing every 10 ms. The control loop never waits for USB |
| Hardware failsafe | Keep the receiver's or ESC's own failsafe set. If the phone stops, that's the only thing that will stop the device |

## Still to do in software

1. Filter out our own echo on one-wire bus servo lines.
2. Move to usb-serial-for-android 3.9 or later, which reads and writes on separate threads.
3. Reopen a specific adapter by serial number after a replug, when there are several.
4. Try FTDI bit-bang PWM as an experimental output.

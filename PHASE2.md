# Phase 2 test runbook

Everything here happens after the SIM is in. Results land in `results/cellular/`.
Older files directly in `results/` are invalid or from local tests. Don't use them.

## 1. Install

1. Put the SIM in and turn mobile data on.
2. Try USB install: Developer options > **Install via USB** should now work with a SIM.
   Otherwise install `SmartPhoneX.apk` from the phone's Downloads, as before.
3. Open the app and allow the phone permission. It is only used to label the network as 4G or 5G.
4. Battery saver for SmartPhoneX: **No restrictions**.

## 2. Start the relay

The relay needs a public HTTPS address. For a quick start, it runs on the PC behind a temporary
`cloudflared` tunnel. This exposes the relay's sign-in page to the internet while it runs. The
address changes on every tunnel restart, and the PC must stay on.

```
cd relay
node admin.mjs add-user <you> operator <a-password-you-choose>
node admin.mjs add-ai-key claude
set TRUST_PROXY=1
node server.mjs
cloudflared tunnel --url http://127.0.0.1:8787
```

Connect the phone with a QR code:

1. Sign in at the tunnel address, then click **Show QR code** under **Connect a phone**.
2. Scan it with the phone's camera or scanner app, and open the link.
3. Tap **Open SmartPhoneX**, check the server name, and tap **Connect**.

The phone's screen should show `relay connected`. The code works once and expires after 5 minutes,
and scanning a new one replaces the previous phone's key. `node admin.mjs new-vehicle-key` is not needed.

If the scanner won't open the link, `tools/enroll.mjs` does the same over USB, using a key from
`node admin.mjs new-vehicle-key`. Keep USB tethering off during tests: on this PC it takes over the
default route, and traffic would no longer be a real cellular path.

## 3. Test matrix

Each row is one run of:

```
node tools/measure.mjs <url> <user> <password> <ai-key> --label "<name>" --secs <n> --drive --probe
```

`--drive` holds manual control, which counts false failsafes. Keep the car away from walls by using
zero throttle, as the tool does. Keep the phone's screen on.

| # | Label | Do this during the run | Secs | PRD criterion |
|---|---|---|---|---|
| 1 | `baseline wifi` | Wi-Fi on, for comparison | 180 | Comparison |
| 2 | `4G strong` | Network set to 4G only, good signal | 300 | 4G behavior |
| 3 | `5G strong` | 5G preferred, good signal (if available) | 300 | 4G vs 5G |
| 4 | `weak signal` | Move somewhere with 1 bar or less | 300 | Changing network quality |
| 5 | `moving` | Walk or ride so that cell towers change | 600 | Handovers |
| 6 | `wifi to cellular` | Turn Wi-Fi off, then on, mid-run | 180 | Network switching |
| 7 | `internet loss` | Airplane mode for 30 s, twice | 180 | Complete loss and recovery |
| 8 | `no drive 4G` | Same as 2, without `--drive` | 180 | AI-only use |

Then the demo, from a different network to the phone:

```
node tools/relay-e2e.mjs <url> <user> <password> <ai-key>
node tools/agent-run.mjs <claude.exe> <url> <ai-key> "Arm the vehicle, run the square mission, check on it until it finishes, stop it and report its status."
```

Before the agent run, clear any failsafe or human stop in the ground station: Arm manual, then Disarm.

## 4. Acceptance criteria

| PRD criterion | Evidence |
|---|---|
| Remote browser works over cellular | `relay-e2e`, and driving it by hand |
| AI issues MCP commands remotely | `agent-run`, `mcp_fail` in the CSV |
| Live telemetry remotely | `telemetry_hz`, `telemetry_age_*` |
| Compare 4G and 5G | Rows 2 and 3 |
| Separate timing: command, inference, phone, telemetry | `uplink_*`, `results/phase2-agent.csv`, `phone_proc_*`, `telemetry_age_*` |
| Jitter, loss, bandwidth, reconnect time | `jitter_ms`, `loss_pct_icmp`, `phone_kbps_*`, `phone_outage_max_ms` |
| Network changes and handovers | Rows 4-6, with the changes listed in each run's JSON file |
| Internet loss and recovery | Row 7 |
| Stale and duplicate rejection after reconnecting | `relay-e2e` |
| Local control without internet | Phone's STOP and Drive from this screen in airplane mode |
| Authentication, authorization, limits, sessions | `relay` tests, `relay-e2e` |

`loss_pct_icmp` of 100 with a working link means the network blocks ping, not that all traffic is lost.

## 5. Decisions the data should drive

- **Manual timeout (500 ms):** if real cellular runs show false failsafes, raise it in the ground
  station's settings and re-run. A longer timeout means the car travels further before stopping.
- **Permanent relay host:** the temporary tunnel is fine for testing, but not for normal use.

# Run your own relay

The relay lets you reach your phone over the internet. It's one small Node.js service. This folder runs it with
Docker, and Caddy in front for HTTPS certificates that renew themselves.

You need:
- A server with a public IP. A small VPS is plenty, or a home server with ports 80 and 443 forwarded.
- A domain name pointing at it, such as `relay.example.com`.
- Docker with the Compose plugin.

## Start it

```
git clone <this repository>
cd SmartPhoneX/deploy
DOMAIN=relay.example.com docker compose up -d --build
```

Caddy gets a certificate on first start, which takes under a minute. Then open `https://relay.example.com/login`.

## Add yourself

```
docker compose exec relay node admin.mjs add-user <name> operator
```

This prints a generated password. Add `viewer` accounts for people who should only watch.
For an AI agent: `docker compose exec relay node admin.mjs add-ai-key <name>`.

## Connect the phone

1. Sign in, open **Setup**, and choose **Show QR code** under **Connect**.
2. Scan it with the phone and open the link. Tap **Open SmartPhoneX**, check the server name, and tap **Connect**.
3. The phone shows `relay connected`.

## Keep it running

| Task | Command |
|---|---|
| Update | `git pull && docker compose up -d --build` |
| Logs | `docker compose logs -f relay` |
| Health | `https://relay.example.com/health` says `ok` |
| Back up accounts | `docker compose cp relay:/data ./relay-backup` |
| Restore accounts | `docker compose cp ./relay-backup/. relay:/data && docker compose restart relay` |

Accounts and the vehicle key live in the `relay-data` volume. Back it up: losing it means making accounts again and re-scanning the QR code.

## Without Caddy

Behind your own nginx or another reverse proxy, run only the relay with `TRUST_PROXY=1`. The proxy must append the
client's address to `X-Forwarded-For`, and the relay must not be reachable except through it.
Behind a Cloudflare tunnel, use `TRUST_PROXY=cloudflare`.

Without any proxy, leave `TRUST_PROXY` unset. The relay then needs HTTPS some other way, because the phone refuses a relay without it.

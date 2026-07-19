# SSHWSVpn

SSH-over-WebSocket VPN client skeleton for Android. Imports a `.json` config,
connects over SSH using a "payload" front (WS/TLS), and exposes a local
SOCKS5 proxy on 127.0.0.1:1080 tunneled through the SSH session.

## What works right now
- Config import (.json) — see `sample_config.json`
- SSH connection through the payload/WS front (`PayloadProxy.kt`)
- Local SOCKS5 server forwarding each connection over SSH `direct-tcpip`
  channels (`MyVpnService.kt`)
- TUN interface is created (VpnService.Builder) but **not yet wired** to the
  SOCKS proxy — see "Next step" below.

## Next step: system-wide routing
To route ALL device traffic (not just apps manually pointed at the SOCKS
proxy) you need a tun2socks component that reads raw IP packets from the TUN
file descriptor and turns them into SOCKS5 connections against
127.0.0.1:1080. Don't write this from scratch — use the open-source
**hev-socks5-tunnel** (MIT license): https://github.com/heiher/hev-socks5-tunnel
Grab the prebuilt `.so` for your target ABI (arm64-v8a) from its Releases
page, put it under `app/src/main/jniLibs/arm64-v8a/`, and start it from
`MyVpnService` pointing at the TUN fd and the local SOCKS port.

## Build via Termux + GitHub Actions (no computer needed)

```bash
pkg install git -y
cd ~
git clone https://github.com/<your-username>/<your-repo>.git
cd <your-repo>
# copy this project's files into the repo folder, then:
git add .
git commit -m "SSH-WS VPN skeleton"
git push
```

After pushing, open GitHub → your repo → **Actions** tab → the latest
"Build APK" run → download the `app-debug-apk` artifact once it finishes
(a few minutes). That's your installable APK.

## sample_config.json fields
- `host` / `port` — your VPS domain and the front port (443)
- `username` / `password` — the SSH account you created
- `payload` — WS handshake payload, `[host]`/`[port]`/`[crlf]` get substituted
- `sni` — TLS SNI, usually same as host
- `use_tls` — true if the front is behind stunnel/nginx TLS

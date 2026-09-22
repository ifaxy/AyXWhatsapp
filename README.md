# WA Gateway (Phase 0 spike)

A self-contained Android app that embeds Node.js + Baileys to run a WhatsApp
gateway **entirely on the phone** — no Termux, no server. The Kotlin UI talks to
an in-process Express server on `127.0.0.1`.

Phase 0 proves the whole pipeline works: Node boots inside the APK, Baileys
connects via **pairing code**, and you can send one text message. Everything
else (multi-session, scheduling, auto-reply, contacts, webhooks) builds on top
of this.

## Architecture

```
APK
├─ Kotlin UI (Compose)  ──HTTP──►  127.0.0.1:8765
│      └─ JNI: node::Start()          │
└─ libnode.so (embedded) ◄───────────┘
       └─ Express + Baileys
             auth creds → noBackupFilesDir  (device-bound, excluded from backup)
```

- `nodejs-project/` — the Node engine (`main.js` = Express + Baileys). CI runs
  `npm install` here and zips it into the APK's assets.
- `app/src/main/cpp/` — ~60 lines of JNI that just start Node and pipe
  `console.log` to logcat (tag `NODEJS`).
- `app/src/main/java/ayx/wagw/` — Kotlin: runtime bootstrap, foreground service,
  REST client, Compose UI.

## Build (GitHub Actions)

On-device AndroidIDE can't compile the native part, so builds run on Actions.

1. Create a GitHub repo and push this project (branch `main`).
2. The **Build APK** workflow runs automatically (or trigger it from the Actions
   tab → Run workflow).
3. Download the `wa-gateway-debug` artifact → `app-debug.apk` → install on the
   phone.

CI does the heavy lifting: downloads the prebuilt `libnode.so` from
[digidem/nodejs-mobile](https://github.com/digidem/nodejs-mobile) (Node 24),
`npm install`s Baileys (pure JS — no cross-compiling), bundles the node project,
and builds a debug APK.

## Use

1. Open the app → it starts the engine (first launch extracts the node project,
   takes a few seconds).
2. Enter your number with country code (digits only, e.g. `9199XXXXXXXX`) →
   **Get pairing code**.
3. On the phone with WhatsApp: **Linked devices → Link with phone number** →
   enter the 8-char code.
4. Once "Linked: yes", send a test message.

Watch logs: `adb logcat -s NODEJS NodeRuntime`.

## Known risk points (if the first build/run fails)

- **Linker: `undefined reference to node::Start`** — the exported entry symbol
  differs in this libnode build. Fix in `node_bridge.cpp` (the one forward
  declaration). Everything else is unaffected.
- **APK is large** (~libnode 40MB + Baileys deps). Expected. Ship arm64 only.
- **`npm install` needs git** — Baileys pulls `libsignal` from a git URL. The
  Actions runner has git, so this is fine there.
- **Foreground service** uses type `specialUse` (fine for sideloaded APKs; Play
  Store would want justification).
- **Baileys is pinned to `latest`** in `package.json` — WhatsApp breaks old
  versions, so this tracks current. Pin it once things work for reproducible
  builds.

## Note

Baileys is an unofficial WhatsApp client; WhatsApp can ban the number. Use a
test number, and don't use this for spam/bulk messaging.

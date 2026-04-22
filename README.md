<p align="center">
  <img src="docs/logo.svg" alt="FingerTheGame" width="500"/>
</p>

# FingerTheGame

An Android save-data editor that browses and edits **other apps' files** without root, using
[Shizuku](https://shizuku.rikka.app/) as a privilege bridge. Built to scratch a personal modding itch and then
generalised into something CheatEngine-shaped that works on any app whose data lives under
`/sdcard/Android/data/<pkg>/`.

## What it does

- **Pick an installed app**, browse its `Android/data/.../files` tree
- **Detect format**: text, JSON, XML, SQLite, .NET BinaryFormatter (NRBF), or raw binary
- **Edit in place** without breaking byte offsets
  - Text/JSON/XML — syntax-aware editor
  - SQLite — row-level editing across tables
  - NRBF (any game using `BinaryFormatter`) — every primitive field is parsed, grouped by class, searchable
  - Binary — hex viewer
- **Save back** by force-stopping the target app, writing via Shizuku's shell uid, and keeping a timestamped
  backup in this app's cache

## NRBF editor

The .NET BinaryFormatter parser walks the entire file and records every primitive's byte offset. Edits patch the
existing buffer in place rather than re-serialising, so layout-changing operations (like resizing strings) are
intentionally not exposed.

Fields are organised into:

- **⭐ Likely Cheat Targets** — cross-class section pulling fields whose names match universal game terms
  (money/coin/level/xp/hp/damage/premium/etc.). Hidden if nothing matches; hidden during search to avoid
  duplicate hits.
- **📦 \<ClassName\>** — every actual class in the file, ordered by aggregate cheat-score (most "interesting"
  first). Top three game classes open by default.
- **🛠 System.\*** — collection types pushed to the bottom and collapsed.

A search bar matches across field/class names. Type filters constrain to int/float/bool/string. Quick-fill chips
on each row offer MAX, 999, 100, +10, 0 (or `→ true`/`→ false` for booleans).

## Architecture notes

- Reads and writes go through `Shizuku.newProcess` (reflective — the public Shizuku API doesn't expose it).
  Multi-MB writes pipe through stdin instead of being embedded in argv to dodge `ARG_MAX`.
- The editor parses on `Dispatchers.Default`, debounces patch-application by 250 ms, and lazy-renders rows in a
  collapsed form to keep scroll smooth on saves with tens of thousands of fields.
- A standalone `parser_test/` JVM project lets you reproduce format issues without an emulator:
  ```
  kotlinc parser_test/Main.kt parser_test/Crash.kt -include-runtime -d crash.jar
  java -jar crash.jar path/to/save.bin
  ```

## Requirements

- Android 11+ (the `/sdcard/Android/data/` lockdown is what makes Shizuku necessary)
- Shizuku installed and running, with permission granted to this app

## Install (step-by-step)

### 1 · Download the APK
Grab the latest `app-release.apk` from the
[**Releases page**](https://github.com/FZ1010/FingerTheGame/releases/latest).

### 2 · Allow sideloading
On your phone, open **Settings → Apps → Special access → Install unknown apps**, find your file
manager / browser, and turn on **Allow from this source**.

### 3 · Install Shizuku
FingerTheGame needs Shizuku to read other apps' files without root.

- Install **Shizuku** from the [Play Store](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api)
  or [GitHub](https://github.com/RikkaApps/Shizuku/releases/latest).
- Open Shizuku and follow its setup. Two options:
  - **Wireless debugging** (Android 11+, no PC needed) — easiest. Shizuku has a one-tap pairing flow.
  - **Wired ADB** (`adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`) — works after a reboot
    only until you reboot again.
- Confirm the top of the Shizuku app says **"Shizuku is running"**.

### 4 · Install FingerTheGame
Open the downloaded APK on your phone and tap **Install**.

### 5 · Grant Shizuku permission
Open **FingerTheGame**. The home screen shows the Shizuku status — it should say
**"Shizuku: needs permission"** the first time. Tap **Grant**, then **Allow** in the popup.

When the status reads **"Shizuku: ready"** you're good.

### 6 · Edit a save
1. Tap **Pick an app** and choose a game (apps with a data folder are flagged).
2. Browse into `files/` (or wherever the save lives) and tap the save file.
3. The format is auto-detected. For NRBF saves you'll see grouped sections — tap a row to expand,
   type or use the quick-fill chips, hit **Save**.
4. The app force-stops the target before writing so the game reloads from disk. A timestamped backup
   of the original is kept under FingerTheGame's cache.

## Building from source

```bash
gradle :app:assembleDebug
adb install -r --user 0 app/build/outputs/apk/debug/app-debug.apk
```

`--user 0` matters on devices with Samsung Dual Messenger (user 95) — without it Android may pick a profile and
install twice.

## Caveats

- String editing in NRBF is intentionally disabled — changing a string's length would shift every following
  byte offset and require a full re-serialise.
- The parser is best-effort: when it hits an unknown record type it stops gracefully and the editor shows
  whatever fields were collected before the bail point.
- No root, no Frida, no live memory scanning — this only edits files. Anti-cheat detection on the file isn't
  defeated; that's not the goal.

## License

Source-available — see [LICENSE](LICENSE). Short version: free to use, **not** for sale,
**not** for redistribution or rebranding, contributions back to this repo are welcome.

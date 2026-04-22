<p align="center">
  <img src="docs/logo.svg" alt="FingerTheGame" width="500"/>
</p>

# FingerTheGame

An Android save-data editor that browses and edits **other apps' files** without root, using
[Shizuku](https://shizuku.rikka.app/) as a privilege bridge. Built to scratch a personal modding itch and then
generalised into something CheatEngine-shaped that works on any app whose data lives under
`/sdcard/Android/data/<pkg>/`.

## What it does

- **Pick an installed app**, browse its `Android/data/.../files` tree (with a recents list on the home
  screen so you skip the dance for files you've already opened)
- **Auto-detect format**: text, JSON, XML, SQLite, .NET BinaryFormatter (NRBF), Protobuf wire format,
  base64-wrapped variants of any of the above, or raw binary as a fallback
- **Edit in place** without breaking byte offsets
  - Text/JSON/XML — syntax-aware editor
  - SQLite — row-level editing across tables
  - NRBF (any .NET game) — every primitive parsed, grouped by class, with structural collapses for
    BigInteger/Decimal/DateTime/TimeSpan/KeyValuePair so wrapper noise doesn't bury the actual values
  - Protobuf (no `.proto` needed) — varint/fixed32/fixed64/length-delimited fields editable by number
  - Binary — hex viewer
- **Compare two saves** side by side: pick any sibling save or backup, see exactly which fields changed,
  pull selected differences into the current save with one tap
- **Save back** by force-stopping the target app, writing via Shizuku's shell uid, and keeping a timestamped
  backup in this app's cache

## NRBF editor

The .NET BinaryFormatter parser walks the entire file and records every primitive's byte offset. Edits patch the
existing buffer in place rather than re-serialising, so layout-changing operations (like resizing strings) are
intentionally not exposed.

### Auto-collapsing wrapper structures

NRBF burdens many values inside structural noise — `System.Numerics.BigInteger` is two fields (`_sign` + `_bits`),
`Observable<T>` wrappers expose only `ObservedValue`, `Decimal` is four packed Int32s, etc. The parser collapses
all of these into single editable rows:

- **`System.Numerics.BigInteger`** — `_sign` + `_bits` decoded into a single number; edits re-encode within the
  original bit allocation (won't shift offsets)
- **`System.Decimal`** — 4-int packed form → editable BigDecimal
- **`System.DateTime`** — Int64 dateData with Kind bits → ISO-8601, Kind preserved on write
- **`System.TimeSpan`** — Int64 ticks → ISO-8601 duration
- **`System.Collections.Generic.KeyValuePair`** — dictionary entries get their key in the row label
  (`[5] = 0.75` instead of all reading `value = 0.75`)
- **`Observable<T>` / single-field wrapper accessors** — generic field names (`value`, `_value`, `ObservedValue`)
  borrow the meaningful name from the closest non-generic ancestor by walking the inline context stack and the
  forward-reference graph (handles Real War-style layouts where wrapper objects are forward-referenced and the
  inline stack is empty by the time the inner value is parsed)

### Field organisation

Fields are grouped into sections, ordered by likely usefulness:

- **⭐ Likely Cheat Targets** — cross-class section pulling fields whose names match universal game terms
  (money/coin/level/xp/hp/damage/premium/etc.) in 9 languages: English, Japanese, Korean, Chinese, Spanish,
  Portuguese, German, French, Russian. Hidden if nothing matches; hidden during search to avoid duplicate hits.
- **🔢 By Value (largest first)** — top 50 numeric fields ranked by absolute magnitude. The lifeline for
  obfuscated saves: when names are noise, the largest counters are still the cheat targets. Open by default
  only when keyword scoring found nothing.
- **📦 \<ClassName\>** — every actual class in the file, ordered by aggregate cheat-score (most "interesting"
  first). Top three game classes open by default.
- **🛠 System.\*** — collection types pushed to the bottom and collapsed.

### Smart highlighting

Each row gets emoji badges so you can scan for cheat targets at a glance:
- 🔥 keyword match (likely cheat term)
- 📈 value magnitude ≥ 1M (likely currency/score counter)
- 💯 round-looking value (1000, 5000, … — a human-set default)

A search bar matches across field/class names. Type filters constrain to int/float/bool/string. Quick-fill chips
on each row offer MAX, 999, 100, +10, 0 (or `→ true`/`→ false` for booleans).

### Two-save diff

When you have two snapshots of the same save (different slots, a backup, or before/after spending in-game),
hit the **Compare** button in the top bar. The bottom sheet lists every sibling file plus this app's cached
backups; pick one and a diff sheet shows every changed field with old → new values. Select the changes you
want, hit **Apply**, and they patch into the current save (Save then writes them back).

Pairing is by structural byte offset — works even when names are obfuscated or in a language we don't recognise.

## Protobuf editor

Schema-less wire-format walker. No `.proto` needed because the format is self-describing (`(field_number,
wire_type)` tag pairs). Auto-detects only when an end-to-end parse succeeds with sane field numbers (95% rule).

- **Varint** — int/uint/sint/bool/enum. Editable as long as the new value re-encodes to the same byte length.
- **Fixed32** — fixed/float, always editable in place.
- **Fixed64** — fixed/double, always editable in place. Display shows the raw int and the float interpretation
  side by side so you can tell which it is.
- **Length-delimited** — string/bytes/embedded message. Editable when the new content is exactly the same
  byte length (resizing would shift later fields).

The same two-save diff workflow works on protobuf files too.

## Base64 wrapping

Saves wrapped in base64 (a popular obfuscation among Unity games) are auto-detected, decoded for editing, and
re-encoded on save. Detection is strict (charset, length, parses cleanly to one of the recognised inner
formats) so plain text doesn't get false-positively unwrapped.

## Architecture notes

- Reads and writes go through `Shizuku.newProcess` (reflective — the public Shizuku API doesn't expose it).
  Multi-MB writes pipe through stdin instead of being embedded in argv to dodge `ARG_MAX`.
- Editors parse on `Dispatchers.Default`, debounce patch-application by 250 ms, and lazy-render rows in a
  collapsed form to keep scroll smooth on saves with tens of thousands of fields.
- The two-save diff pairs by byte offset, not by name — so it works through obfuscated/non-English naming.
- A standalone `parser_test/` JVM project lets you reproduce format issues without an emulator:
  ```
  kotlinc parser_test/Main.kt parser_test/Crash.kt -include-runtime -d /tmp/crash.jar
  java -jar /tmp/crash.jar path/to/save.bin
  # Or for the protobuf round-trip test:
  kotlinc parser_test/ProtoCheck.kt -include-runtime -d /tmp/proto.jar
  java -jar /tmp/proto.jar
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

After your first save, that file shows up in the **Recent** list on the home screen — one tap to reopen.

## Building from source

```bash
gradle :app:assembleDebug
adb install -r --user 0 app/build/outputs/apk/debug/app-debug.apk
```

`--user 0` matters on devices with Samsung Dual Messenger (user 95) — without it Android may pick a profile and
install twice.

## Caveats

- String / variable-length-blob editing is intentionally disabled — changing a string's length would shift
  every following byte offset and require a full re-serialise.
- The NRBF parser is best-effort: when it hits an unknown record type it stops gracefully and the editor shows
  whatever fields were collected before the bail point.
- Protobuf detection is heuristic. False positives are possible in theory; in practice the "must parse to end
  with sane field numbers" filter rejects almost everything that isn't actually protobuf.
- No root, no Frida, no live memory scanning — this only edits files. Anti-cheat detection on the file isn't
  defeated; that's not the goal.
- `/data/data/<pkg>/` (where Android stores `shared_prefs/` and the SQLite databases for many apps) is
  unreachable through Shizuku's `shell` uid on stock Android 11+. We can only see what's exposed under
  `/sdcard/Android/data/<pkg>/`.

## License

Source-available — see [LICENSE](LICENSE). Short version: free to use, **not** for sale,
**not** for redistribution or rebranding, contributions back to this repo are welcome.

# GhostLock S26 — Android wrapper (`indev.ghostlock.s26`)

One-click Android wrapper for
[1ndevelopment/ghostlock-s26](https://github.com/1ndevelopment/ghostlock-s26):
GhostLock (CVE-2026-43499) ported to the **whole Samsung Galaxy S26 series**
(Android 16 / GKI 6.12). One APK, three kernel lines, runtime parameter
matching — no per-build app variants.

> Use only on devices you own or are explicitly authorized to test.
> Temp root vanishes on reboot. A second exploit run in the same boot can
> crash the device — reboot before retrying.

## Coverage: the full S26 family

The exploit matches by **kernel line**, not by individual build
(`exploit/src/params_table.c` is authoritative; `ParamsTable.kt` mirrors it
for the UI verdict only):

| Device codename | Marketing | SoC | Kernel line |
|---|---|---|---|
| m1q | Galaxy S26 (SM-S942x) | Snapdragon | `cn` or `intl` (by CSC) |
| m2q | Galaxy S26+ (SM-S947x) | Snapdragon | `cn` or `intl` (by CSC) |
| m3q | Galaxy S26 Ultra (SM-S948x) | Snapdragon | `cn` or `intl` (by CSC) |
| m1s | Galaxy S26 (SM-S942B) | Exynos | `exynos` |
| m2s | Galaxy S26+ (SM-S947B) | Exynos | `exynos` |

Tested builds (17): S9420ZCS4AZG1, S9470ZCS4AZG1, S9480ZCS3AZF1,
S9480ZCS4AZG1, S942BXXS4AZG5, S947BXXS3AZF1, S947BXXS4AZG5,
S942QOPU1AZDE, S942U1UES4AZG3, S942USQS4AZG3, S947USQS4AZG3,
S9480ZHS4AZG1, S948BXXS4AZG5/6, S948NKSS4AZG3, S948U1UES2AZE1,
S948USQS4AZG3.

Unknown OTAs fall back exactly like upstream `params.c`: exact build first,
then same model + 3-char CSC (OTA reuse), then latest same-device entry
(flagged unverified), else **fail-closed** (the app shows UNSUPPORTED and the
native layer exits 2). Completely unknown models are refused — never forced.

## What the app does

One big button — **Root my S26** — runs the whole pipeline and narrates into
the Output card:

1. **Device check** — model/device/incremental/fingerprint plus the series
   verdict (exact / OTA-reuse / unverified guess / unsupported). Unsupported
   builds stop here (fail-closed, no boot-claim burned).
2. **Shizuku** — used automatically when connected (uid 2000 shell, same
   context family as the README's `adb shell` flow). Permission is requested
   once at launch (and again if the server restarts); the Root flow waits for
   the grant instead of bailing. If the server is down the app opens the
   Shizuku manager so you can start it, then falls back to the in-app shell.
   Adding the app to Shizuku's allowlist removes the prompt entirely.
3. **Stage** — copies `preload.so`, `su_daemon`, `ksud` to `/data/local/tmp`
   (`preload.so`, `cve-2026-43499-root`, `ksud`) and `chmod`s them. If you
   already `adb push`ed the files per the upstream README, they are picked up
   in place.
4. **Run** — executes exactly what upstream documents:
   `env LD_PRELOAD=/data/local/tmp/preload.so sh` (the `.so` constructor runs
   the chain and `_exit`s; stdout *is* the exploit log), up to 5 attempts —
   the race is probabilistic. A `BOOT_FORCE=1` switch is available but
   rebooting is safer.
5. **Verify** — confirms `id` reports `uid=0` through **any** channel:
   temp-daemon socket first, then KernelSU-style `su`. This matters because on
   a full success su_daemon **unlinks its socket and exits by design**
   (handover to KernelSU) — a dead temp socket with working `su` means
   rooted, not broken. Prints the boot-claim log tail and reports rooted /
   exit-code advice.

Below that: a single **command field + Run as root** row (one-shot commands
via the su daemon's `C` protocol; interactive PTY is out of scope for v1),
and a small **Reset** link that clears `/data/local/tmp/ghostlock-boot.log`
so a run can be retried without rebooting (upstream warns this may panic —
reboot is the safe path).

## Project layout

```
exploit/                  vendored upstream (Makefile + src/, authoritative)
ksud                      upstream prebuilt KernelSU loader (ARM64 PIE, also in assets)
app/src/main/assets/ksud  staged copy shipped in the APK
app/src/main/assets/      + preload.so / su_daemon after stage-assets.sh
app/src/main/cpp/         optional CMake rebuild of preload.so from exploit/src
app/src/main/java/indev/ghostlock/s26/
  MainActivity.kt         UI (device / stage / run / shell / boot guard)
  ParamsTable.kt          series table mirror (17 builds, 3 lines, 5 codenames)
  DeviceCompat.kt         Build.* identity + series verdict
  ShellRunner.kt          Shizuku (uid 2000) + local fallback, staging
  SuClient.kt             /data/local/tmp/temp_su.sock 'C'-mode client
  GhostlockManager.kt     exit-code interpreter (0/1/2/3/4)
PORTING.upstream.md       porting notes (new firmware = new device_map row)
```

## Build

Requirements: Android Studio (JBR 21) / SDK 35 / NDK r26+ / CMake 3.22.1 /
JDK 17.

```bash
# 1. Build the native payloads with the NDK (upstream flow):
cd exploit && make preload
#   -> build/bin/preload.so, build/embed/su_daemon_aarch64_pie

# 2. Stage them into the APK assets:
./stage-assets.sh

# 3. Build the app:
./gradlew :app:assembleDebug
#   -> app/build/outputs/apk/debug/app-debug.apk
```

`ksud` is already vendored (`ksud` + `app/src/main/assets/ksud`) so step 1–2
only produce the two NDK outputs. The CMake target in
`app/src/main/cpp/CMakeLists.txt` can additionally rebuild `libpreload.so`
from the same sources inside the APK as a fallback.

## On-device build (Termux, aarch64)

The SDK's `aapt2`/NDK are x86_64 and cannot execute on-device. Verified
procedure (SDK at `~/android-sdk`, Gradle 8.9 — AGP 8.5.2 rejects the
system Gradle 9.x):

```bash
# Native payloads with the Termux toolchain (API 35 target, system liblog):
cd exploit
clang -O2 --target=aarch64-linux-android35 -fPIE -pie -Isrc src/su_daemon.c \
  -o build/embed/su_daemon_aarch64_pie
clang -O2 --target=aarch64-linux-android35 -fPIC -Isrc \
  -Wno-unused-parameter -Wno-sign-compare -Wno-unused-function -Wno-macro-redefined \
  src/main.c src/util.c src/bootclaim.c src/slide.c src/fops.c src/attr.c \
  src/root.c src/params.c src/params_table.c src/preload.c \
  -L/system/lib64 -llog -shared -o build/bin/preload.so
cd .. && ./stage-assets.sh

# APK (CMake native step auto-skips without SDK cmake; Termux aarch64 aapt2
# is injected via -P so checked-in files stay workstation-clean):
env ANDROID_HOME=~/android-sdk ANDROID_SDK_ROOT=~/android-sdk \
  JAVA_HOME=$PREFIX/lib/jvm/java-21-openjdk \
  ~/gradle-dists/gradle-8.9/bin/gradle :app:assembleDebug --console=plain \
  -Pandroid.aapt2FromMavenOverride=$(command -v aapt2)
```

## Run

1. Install the APK on the S26 device + install/start
   [Shizuku](https://shizuku.rikka.app/) (wireless debugging or PC).
2. Open GhostLock S26 — Shizuku permission is requested automatically at
   launch; approve it once (it lasts until the Shizuku server restarts).
3. Check the Device card says SUPPORTED/LIKELY for your build.
4. Tap **Stage**, then **Run once**. The race is probabilistic — use
   **Retry ×5**; several attempts are normal.
5. **Check root** → expect `uid=0 …`. Then run commands in the Root shell card.
6. After success, install/enjoy KernelSU Manager (`me.weishu.kernelsu`);
   the daemon late-loads `ksud` automatically (see `su_daemon.c` `K` mode).

Shizuku is optional but strongly recommended: the upstream flow runs from an
`adb shell` (uid 2000, `shell` SELinux context), and the in-app fallback
(`untrusted_app`) is far more likely to be blocked from `LD_PRELOAD`/exec on
`/data/local/tmp`.

## Exit codes (shown after every attempt)

| Code | Meaning | What to do |
|---|---|---|
| 0 | success (socket up, ksud late-load OK) | Check root, use shell |
| 1 | race missed / verify failed | just retry (normal) |
| 2 | unsupported build (fail-closed) | stop; firmware needs a port |
| 3 | carrier/root failed | reboot before next try |
| 4 | already ran this boot | reboot; `BOOT_FORCE=1` overrides but may crash |

## Provenance

- Exploit: `exploit/` + `PORTING.upstream.md` + `ksud` from
  `1ndevelopment/ghostlock-s26` (Apache-2.0; see `LICENSE.upstream`,
  `NOTICE.upstream`). The app links no exploit code into its own process —
  it stages the NDK-built files and spawns the documented `LD_PRELOAD` shell.
- Credits (upstream): Nebula Security (CVE discovery), polygraphene
  (baseline), monovibe (UMH root / boot-claim), lukasmaar (kernelsnitch),
  veritas501 (pipe concept), BuSung-dev (companion-app base).

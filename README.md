# xrnhabit

An Android self-control app: distracting apps (and, in the sideload build, websites) stay
behind a full-screen block until you've done a habit, then open for a few minutes and lock
again on their own. No accounts, no backend; everything stays on the phone.

## What it does

- **Habit blocks.** Unlock by finishing a checklist, running a timer (optionally counted in a
  *different* app, such as a reading app), or both. Checklist items are hold-to-check, and an
  item can be linked to a habit in [xrnloops](#companion-app-xrnloops) so only a real tick
  there counts.
- **Daily limits.** N free minutes a day across a block's apps, then blocked until midnight.
- **Set times.** Block on a weekly schedule, or lock down right now for N minutes.
- **Hardcore mode** (sideload build). A password gate on Android Settings plus Device Admin
  anti-uninstall while a hardcore block is active.
- **Insights.** Per-app screen time and, in the sideload build, per-site time.
- Built to survive reboots, process death and OEM battery killers. Unlock countdowns run on a
  clock that changing the system time doesn't affect.

## Build

Requirements: **JDK 17** (Gradle 8.11.1 doesn't run on newer JDKs) and the Android SDK
(platform 35). Point Gradle at the SDK with `local.properties` (`sdk.dir=…`) or `ANDROID_HOME`.

```sh
./gradlew :domain:test                 # engine unit tests (pure JVM)
./gradlew :app:assembleSideloadDebug   # full build: website blocking + hardcore mode
./gradlew :app:assemblePlayDebug       # Play-safe build: no Accessibility, no Device Admin
./gradlew :app:installSideloadDebug    # build and install on a connected device
```

The two flavors exist because the distribution channel is undecided. Website reading uses an
Accessibility service and anti-uninstall uses Device Admin, and both carry Play Store policy
risk, so the `play` build leaves them out.

Minimum Android version: 8.0 (API 26).

## Permissions

Usage access (to see the foreground app), draw over other apps (the block screen),
notifications, and exemption from battery optimization. On Xiaomi/MIUI also enable
autostart. The Settings screen shows what's missing and links to each one. The website
reader (sideload only) is an Accessibility service that reads only the browser's address bar.

## Companion app: xrnloops

[Loop Habit Tracker](https://github.com/iSoron/uhabits) doesn't let other apps read its data,
so habit-linked checklist items need **xrnloops**, a small GPLv3 fork of Loop kept as a
separate project. It shares today's ticks through a signature-protected content provider and
pings xrnhabit whenever something is ticked. Both apps must be signed with the same key.
xrnhabit itself contains no Loop code.

## Project layout

| Module | |
|---|---|
| `:domain` | The block engine: a pure Kotlin state machine with no Android dependencies, fully unit-tested |
| `:app` | Compose UI, the foreground service that enforces blocks, persistence, permissions |
| `:hardcore` | Device Admin receiver (sideload builds only) |

The engine's rules (states, transitions, invariants I1–I24) are specified in
`docs/domain-contract.md`, which is in Vietnamese.

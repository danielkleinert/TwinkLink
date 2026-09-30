# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

TwinkLink is a Kotlin plugin for Chromatik (LX) that drives Twinkly LED devices in realtime over UDP. LED layouts are loaded from the Twinkly Cloud directly in the fixture's UI.

## Build Commands

Requires a Java 21 JDK (`JAVA_HOME`).

**Build the plugin:**
```bash
cd Plugin
mvn clean package
```

**Build and install to Chromatik:**
```bash
cd Plugin
mvn clean install -P install
```
This copies the JAR to `~/Chromatik/Packages`. Chromatik must be restarted to load it; check `~/Chromatik/Logs` for the `buildTimestamp` of the loaded package.

## Architecture

All classes are in `Plugin/src/main/kotlin/io/twinklink/`:

**TwinkLink.kt** - Plugin lifecycle (`LXPlugin`), tracks TwinklyOutput instances and disposes them on shutdown.

**TwinklyFixture.kt** - The "Twinkly" fixture (`LXFixture`)
- `layoutJson`: the chosen Twinkly Cloud layout object, saved with the project; LED positions, device IP, LED profile and firmware all come from it
- `email`, `savePassword`: saved parameters. The password is deliberately *not* a registered parameter (OSC query would expose it); `save()`/`load()` write it as `twinklyPassword` only when `savePassword` is on
- Transient UI state: `fetch` (TriggerParameter), `layoutSelect`, `accountStatus`, `layoutInfo`
- Fetching runs on a coroutine; results are applied on the engine thread via `lx.engine.addTask`
- `computePointGeometry` uses `LXPoint.set(matrix, LXVector)`. Never `LXPoint.set(LXPoint)`: it copies the source point's `index`, which corrupts the model's point indices
- Builds one TwinklyOutput for the layout's first device; the UDP protocol version is derived from its firmware

**UITwinklyFixture.kt** - Custom inspector controls (`UIFixtureControls<TwinklyFixture>`), auto-registered by Chromatik when the package is loaded. Sections "Twinkly Account" and "Twinkly Layout".

**UIPasswordBox.kt** - Masked `UITextBox` for the password.

**TwinklyCloudAPI.kt** - Twinkly Cloud client (`https://api.twinkly.com`): `/v2/auth` login, then `/v3/objects` for layouts.

**TwinklyCloudFacade.kt** - `LayoutFacade`/`DeviceFacade` wrappers around the cloud JSON; `parseLayouts()` skips objects without a usable layout. Coordinates are decoded lazily (base64 + gzip JSON).

**TwinklyOutput.kt** - `LXBufferOutput` per device; switches the device to "rt" mode, restores the original mode and brightness on stop, sends frames through TwinklyAPI.

**TwinklyAPI.kt** - Device protocol: HTTP challenge-response login and mode/brightness control, UDP realtime frames:
- V1: single packet (Generation I, firmware 1.x)
- V2: single packet (Generation II before firmware 2.4.14)
- V3: 900-byte chunks with sequence numbers (firmware 2.4.14+)

## Coordinate System

1. The Twinkly app stores 3D LED positions normalized to 0.0-1.0
2. The Cloud API returns them base64 + gzip compressed, with `aspectXY`/`aspectXZ`
3. `LayoutFacade` transforms them to `[x*100, y*100/aspectXY*2, z*100/aspectXZ*-1]`
4. `TwinklyFixture` places them with the fixture's transform; point order equals LED order for UDP output

## Dependencies

- **Kotlin stdlib 2.0.21, kotlinx-coroutines 1.9.0** - Shaded into the JAR (not relocated)
- **LX 1.2.0, glxstudio** (HeronArts) - Provided by the Chromatik runtime; glxstudio is compiled against 1.2.1, the closest published version to Chromatik's bundled 1.2.0
- **GSON** - Provided by the Chromatik runtime
- **Java 21**

## Plugin Registration

- `META-INF/services/heronarts.lx.LXPlugin` contains `io.twinklink.TwinkLink`
- `lx.package` (JSON) provides plugin metadata with Maven property injection
- Chromatik scans the JAR and registers all public, non-abstract fixture and UI control classes

## Development Notes

- No test framework is configured. Logic can be checked headlessly by compiling a small Java class against Chromatik's bundled `glxstudio-*-jar-with-dependencies.jar` and the built plugin JAR, and running it with `new LX()`
- `twinkly cloud recording/` contains captured Cloud API requests/responses for reference
- Device IP addresses must be on the local network; the cloud only provides layout metadata

## External References

- **LX source**: https://github.com/heronarts/LX
- **Chromatik GUI (glxstudio)**: no source published; decompile or use `javap` on the JAR bundled with Chromatik
- **Fixture UI example**: `NDIOutFixture` in https://github.com/titanicsend/LXStudio-TE
- **Chromatik guides**: https://chromatik.co/guide/ and https://chromatik.co/develop/
- **Twinkly device API**: https://github.com/xled/xled-docs, reference implementation https://github.com/scrool/xled

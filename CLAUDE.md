# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

TwinkLink is a Kotlin plugin for Chromatik (LX) that drives Twinkly LED devices in realtime over UDP. LED layouts are loaded from the Twinkly Cloud directly in the fixture's UI.

## Build Commands

Requires a Java 25 JDK (`JAVA_HOME`).

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

**Release:** push a `v*` tag; `.github/workflows/release.yml` sets the Maven version from the tag and publishes a GitHub release with the JAR. `.github/workflows/build.yml` builds every push and PR.

## Architecture

All classes are in `Plugin/src/main/kotlin/io/twinklink/`:

**TwinkLink.kt** - Plugin lifecycle (`LXPlugin`), tracks TwinklyDevice sessions and disposes them on shutdown, which restores the devices.

**TwinklyFixture.kt** - The "Twinkly" fixture (`LXFixture`)
- `layoutJson`: the chosen Twinkly Cloud layout object, saved with the project; LED positions, device IP, LED profile and firmware all come from it
- `email`, `savePassword`: saved parameters. The password is deliberately *not* a registered parameter (OSC query would expose it); `save()`/`load()` write it as `twinklyPassword` only when `savePassword` is on
- Transient UI state: `fetch` (TriggerParameter), `layoutSelect`, `accountStatus`, `layoutInfo`
- Fetching runs on a coroutine; results are applied on the engine thread via `lx.engine.addTask`
- `computePointGeometry` uses `LXPoint.set(matrix, LXVector)`. Never `LXPoint.set(LXPoint)`: it copies the source point's `index`, which corrupts the model's point indices
- `device`: a TwinklyDevice session for the layout's first device, replaced only when its IP or protocol changes; `enabled` starts and stops its stream
- Builds one TwinklyOutput per regenerate, sending through the session
- `movies` (TwinklyMovies): the device's stored movies

**UITwinklyFixture.kt** - Custom inspector controls (`UIFixtureControls<TwinklyFixture>`), auto-registered by Chromatik when the package is loaded. Sections "Account", "Layout", "Movies" (rebuilt from `TwinklyMovies.changed`) and "Record Movie", whose storage bar (`UIRecordingStorage`) polls `TwinklyMovies.recordingFit()`.

**TwinklyMovies.kt** - Movie list state (firmware 2.5.6+): refresh, play/stop, delete, record. Requests run on a coroutine; state changes on the engine thread. Playing a movie disables the fixture, as streaming would override it. Recording enables the fixture, attaches a MovieRecorder that TwinklyOutput feeds, polls it from an engine loop task and uploads the movie once complete; the fits-check allows `MOVIE_OVERHEAD` frames per movie.

**MovieRecorder.kt** - Records the frames TwinklyOutput sends, which it encodes a second time without the master brightness (`lx.engine.output.brightness`) while recording, as movies play at the device's brightness; brightness applies before gamma, so encoding again is exact where scaling the bytes isn't. Frames are sampled by time at the movie's fps, as the engine runs faster. The `recordFps` setting is capped at the device's `frame_rate` from `/xled/v1/gestalt` (12.8 → 12, the Twinkly app's rate; a movie stored at 24 fps played stretched at ~12 fps), read once per session with the movie state, and follows it while set to the maximum; Chromatik's engine fps is project-wide, so it is left alone. Records the loop blend beyond the movie's end and fades it into the start: `M[t] = lerp(rec[frames + t], rec[t], t / blendFrames)`.

**UIPasswordBox.kt** - Masked `UITextBox` for the password.

**TwinklyCloudAPI.kt** - Twinkly Cloud client (`https://api.twinkly.com`): `/v2/auth` login, then `/v3/objects` for layouts.

**TwinklyCloudFacade.kt** - `LayoutFacade`/`DeviceFacade` wrappers around the cloud JSON; `parseLayouts()` skips objects without a usable layout. Coordinates are decoded lazily (base64 + gzip JSON).

**TwinklyDevice.kt** - Session with one device, owning its TwinklyAPI and a lock. The device accepts only one token at a time, so the stream and the movie requests all go through it. Switches the device to "rt" mode and restores the original mode and brightness when the stream stops; falls back to "off" when the device refuses movie mode with no movies left (code 1104). `dispose()` restores before returning (bounded), so a new session for the same device can't overlap it.

**TwinklyOutput.kt** - `LXBufferOutput` that sends the fixture's colors as realtime frames through its TwinklyDevice, and offers them to a running MovieRecorder (possibly on the network thread).

**TwinklyAPI.kt** - Device protocol: HTTP challenge-response login (on the first request, and again on a 401, as another client's login invalidates the token), mode/brightness control, movies, UDP realtime frames:
- V1: single packet (Generation I, firmware 1.x)
- V2: single packet (Generation II before firmware 2.4.14)
- V3: 900-byte chunks with sequence numbers (firmware 2.4.14+)

## Coordinate System

1. The Twinkly app stores 3D LED positions normalized to 0.0-1.0
2. The Cloud API returns them base64 + gzip compressed, with `aspectXY`/`aspectXZ`
3. `LayoutFacade` transforms them to `[x*100, y*100/aspectXY*2, z*100/aspectXZ*-1]`
4. `TwinklyFixture` places them with the fixture's transform; point order equals LED order for UDP output

## Dependencies

- **Kotlin stdlib 2.4.20, kotlinx-coroutines 1.11.0** - Shaded into the JAR (not relocated)
- **LX, glxstudio 1.2.2** (HeronArts) - Provided by the Chromatik runtime; keep `lx.version` in line with the installed Chromatik
- **GSON** - Provided by the Chromatik runtime
- **Java 25** - the runtime bundled with Chromatik 1.2.2

## Plugin Registration

- `META-INF/services/heronarts.lx.LXPlugin` contains `io.twinklink.TwinkLink`
- `lx.package` (JSON) provides plugin metadata with Maven property injection
- Chromatik scans the JAR and registers all public, non-abstract fixture and UI control classes

## Development Notes

- No test framework is configured. Logic can be checked headlessly by compiling a small Java class against Chromatik's bundled `glxstudio-*-jar-with-dependencies.jar` and the built plugin JAR, and running it with `new LX()`
- glx calls parameter listeners on the thread that changed the parameter, often the engine thread. UI that rebuilds components in response sets a flag and rebuilds from a loop task (`addLoopTask`), which runs on the UI thread
- glx `UI2dContainer` layouts only position children along their axis (VERTICAL sets y, HORIZONTAL sets x); cross-axis padding is ignored, so give children that offset as their own x/y, as Chromatik's fixture list does
- `twinkly cloud recording/` contains captured Cloud API requests/responses for reference
- Device IP addresses must be on the local network; the cloud only provides layout metadata
- Deleting a single movie (`DELETE /xled/v1/movies/{unique_id}`) is not in the xled docs; it was captured from the Twinkly app, which only offers it for the last movie and switches movie mode off around it. `DELETE /xled/v1/movies` deletes all movies
- Uploading a movie follows xled_plus `upload_movie`: `POST /xled/v1/movies/new` (name, unique_id, descriptor_type `rgb_raw`/`rgbw_raw`, leds_per_frame, frames_number, fps), then `POST /xled/v1/movies/full` with the frames as octet-stream, laid out like realtime frames. Creating a movie changes the current one, so a playing movie is selected again. The device stores uploads at about 56 KB/s (864 KB took 15 s), so the upload's timeout grows with its size

## External References

- **LX source**: https://github.com/heronarts/LX
- **Chromatik GUI (glxstudio)**: no source published; decompile or use `javap` on the JAR bundled with Chromatik
- **Fixture UI example**: `NDIOutFixture` in https://github.com/titanicsend/LXStudio-TE
- **Chromatik guides**: https://chromatik.co/guide/ and https://chromatik.co/develop/
- **Twinkly device API**: https://github.com/xled/xled-docs, reference implementation https://github.com/scrool/xled

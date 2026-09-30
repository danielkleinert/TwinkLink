# TwinkLink

A [Chromatik](https://chromatik.co) plugin that drives [Twinkly](https://twinkly.com) LED lights in realtime.

Map your lights in the Twinkly app, pick the layout inside Chromatik, and every pattern runs on the real LEDs, placed exactly where they hang.

## Features

- **Twinkly fixture** with the 3D LED positions you mapped in the Twinkly app
- **Layouts from your Twinkly account**: sign in from the fixture panel and pick a layout, no export step
- **Realtime output** over the local network using Twinkly's UDP protocol, with the protocol version chosen automatically from the device firmware
- **Restores the device** to its previous mode and brightness when output stops
- **Works offline** once set up: the chosen layout is saved in the Chromatik project

## Requirements

- Chromatik 1.2
- A Twinkly device on the same network as the computer running Chromatik
- A Twinkly app account with the device's layout mapped in the app

## Installation

1. Download `twinklink-<version>.jar` from the [latest release](https://github.com/danielkleinert/TwinkLink/releases/latest).
2. Copy it into `~/Chromatik/Packages`, removing any older `twinklink-*.jar` there.
3. Restart Chromatik.

## Usage

1. In the fixture list, add a **Twinkly** fixture and select it.
2. In **Twinkly Account**, enter the email and password of your Twinkly app account and click **Load Layouts**.
3. In **Twinkly Layout**, choose a layout. The fixture takes its LED positions, the device IP and the LED type from it, and starts sending to the device.
4. Position, rotate and scale the fixture in the **Geometry** section as with any other fixture.

The login is only needed to load layouts. After a restart the fixture keeps working with the saved layout; sign in again only to switch layouts or to pick up changes made in the Twinkly app, such as a new IP address or a re-mapped layout.

By default the password is kept in memory only. Tick **Save password** to store it with the project; it is then written to the project file in plain text.

### Notes

- So far only tested with a single 400 LED RGB string.
- If a layout spans several devices, only the first one is driven. The layout info line shows how many were left out.
- While Chromatik sends to a device, the device is in realtime mode and ignores the Twinkly app. Disabling or removing the fixture hands it back.
- Problems are logged to `~/Chromatik/Logs`.

## Building from source

Requires a Java 21 JDK.

```bash
cd Plugin
mvn clean package            # builds target/twinklink-<version>.jar
mvn clean install -P install # also copies it to ~/Chromatik/Packages
```

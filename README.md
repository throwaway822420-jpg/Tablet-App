# Slate

Use a Samsung Galaxy Tab (S11 + S Pen) as a Wacom-style pen tablet for a Windows 10/11 PC. The
tablet shows a blank surface; you draw on it while looking at the PC screen. The PC sees a real
Windows Ink pen with pressure, tilt, hover, eraser and barrel button, so it works in Krita,
Photoshop, OneNote, Whiteboard and anything else that takes pen input.

This is a pen-input bridge, not a screen mirror. No video goes to the tablet.

```
┌──────────── tablet (Android) ────────────┐          ┌──────────────── PC (Windows) ────────────────┐
│ CanvasView → PenCapture → SlateLink ─────┼── USB ───┼→ UsbTransport  ┐                              │
│  (stylus only, historical samples,       │ adb fwd  │                ├→ Bridge → PenRouter → SyntheticPen
│   hover, eraser, button, tilt)           │── Wi-Fi ─┼→ WifiTransport ┘   (one      (state      (InjectSynthetic-
│                                          │   UDP    │                     session)  machine)    PointerInput)
└──────────────────────────────────────────┘          └──────────────────────────────────────────────┘
```

| Folder | What |
|---|---|
| [`protocol/PROTOCOL.md`](protocol/PROTOCOL.md) | The wire format: 32-byte little-endian packets. Single source of truth. |
| `android/` | Kotlin app, min SDK 29. Only dependency: the Anthropic Java SDK, for write mode. |
| `windows/Slate.Core/` | Portable .NET 8 library: protocol, pen state machine, mapping, USB and Wi-Fi transports. Tested on any OS. |
| `windows/Slate/` | The WinForms tray app: synthetic pen injection, settings window, region picker. |
| `windows/Slate.Core.Tests/` | xUnit tests, including end-to-end USB/Wi-Fi sessions over real sockets with a fake tablet. |

## Getting started

### PC

Needs Windows 10 1809 or later, 64-bit (x64 or ARM64).

```powershell
cd windows
.\scripts\fetch-platform-tools.ps1     # bundles adb next to Slate.exe (or install platform-tools yourself)
dotnet run --project Slate            # or: .\scripts\publish.ps1 for a self-contained folder
```

Slate starts in the notification area. Double-click its icon for settings: which display (or
region) the tablet maps to, the S Pen button action, the pressure curve, and the Wi-Fi pairing code.

### Tablet

```sh
cd android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or open `android/` in Android Studio. The app explains the rest on its first screen:

- **USB (recommended):** turn on Developer options and USB debugging, plug in, accept the
  prompt. The PC runs `adb forward` and connects automatically; the drawing surface opens by itself.
- **Wi-Fi:** tap the PC in the list and enter the 4-digit code shown on the PC. Allow Slate through
  the Windows firewall on private networks. If broadcasts are blocked, use *Connect by IP address*.

### Art apps

Use Windows Ink / pointer input: in Krita, *Settings › Configure Krita › Tablet settings › Windows
8+ Pointer Input*; Photoshop uses Windows Ink by default. Slate can't inject into apps running as
administrator unless Slate also runs as administrator.

## Write mode: handwriting to text

On the drawing surface, the tab on the middle of the right edge switches between **Pen** (the S Pen
drives the PC's pen) and **Write**. In Write mode the ink stays on the tablet. Tap **Enter** and
the PC types what you wrote into whatever text field has focus. Equations come out as Unicode
(`x² + √2 ≤ π`, `(x+1)/(x−1)`). **Undo** and **Clear** edit the ink, and the eraser end or the
held S Pen button erases whole strokes.

- **Recognition** uses Claude Sonnet 5.5 (`claude-sonnet-5-5`) with thinking off
  (`between_tools`), JSON output so the reply is only the transcription, and server-side refusal
  fallback. The ink is rendered black on white, cropped and scaled to at most 1400 px.
- **Convert on pause:** after 0.8 s without the pen touching, the ink is sent for recognition in
  the background. If nothing changed by the time you tap Enter, that result (or the request still
  in flight) is used, so the text usually appears straight away. A new stroke makes the earlier
  result stale, and a new request goes out at the next pause.
- **Cost:** your own Anthropic API key (entered on the tablet's main screen, stored in app-private
  storage), billed to that account. Roughly a third of a cent per conversion, and convert-on-pause
  makes about 1.5–2 requests per Enter. The writing image is sent to Anthropic.
- The PC types with Unicode keystrokes, so any character works regardless of keyboard layout.
  Windows doesn't let it type into apps running as administrator unless Slate does too.

## Tests

```sh
cd windows && dotnet test                 # 46 tests: protocol, state machine, mapping, transports, text
cd android && ./gradlew testDebugUnitTest # 25 tests: protocol, tilt, area layout, ink, text chunks, API request shape
```

Both suites check the same golden packet from `PROTOCOL.md`, so the two ends can't drift apart.
CI runs both on every push (`.github/workflows/ci.yml`).

## Milestones

The code for all six milestones is in place. What's been checked so far is what can run
without the hardware; the rest needs a Windows PC and the tablet.

| # | Milestone | Status |
|---|---|---|
| 1 | Injector alone: pressure-ramped line in Krita | Code done: `Slate.exe --test-stroke` or tray › *Draw test stroke*. Struct layouts verified against the Win32 headers (96/120/152 bytes). **Needs a run on Windows.** |
| 2 | Capture alone: stylus fields in Logcat | `adb shell setprop log.tag.SlatePen VERBOSE`, reopen the drawing surface, then `adb logcat -s SlatePen` shows tool, contact, range, button, x/y, pressure and tilt per sample (works without a PC connected). Tilt conversion and normalization are unit-tested. **Needs the tablet.** |
| 3 | End-to-end over USB | Tested with a fake tablet over loopback TCP (HELLO/CONFIG/PEN/PING, release on socket loss, reconnect). **Needs hardware.** |
| 4 | Tilt, rotation, eraser, barrel, clean leave | State machine tested: eraser → `INVERTED`/`ERASER`, barrel → native barrel while drawing and right-click while hovering (or eraser, or off), tool switch leaves range first. S Pen has no barrel twist, so rotation is only sent if a device reports it. **Needs hardware.** |
| 5 | Wi-Fi | Discovery, pairing code, sequence-number dropping, triple-sent pen-up/leave, 1 s watchdog and 5 s timeout tested over loopback UDP. **Needs a real network test** (the 5-minute no-stuck-strokes check). |
| 6 | Polish | Tray status/icons, display and region picker, barrel mapping, pressure curve with preview; tablet keep-screen-on, immersive mode, dark/light surface, active-area outline, status overlay. **Needs a fresh-user walkthrough.** |

### Things to watch when testing on hardware

- **Tilt direction.** Tilt uses Chromium's Android → Pointer Events conversion; if it comes out
  mirrored in Krita, flip the sign in `PenCapture.tiltXY`.
- **S Pen button while hovering** opens Samsung's Air command unless it's turned off
  (*Settings › Advanced features › S Pen*). The app says so.
- **Latency:** the round trip from PING/PONG shows on the tablet's status line, in the tray
  tooltip and in the settings window. Target is under 20 ms on USB.
- **Scaling:** the PC app is per-monitor DPI aware (PerMonitorV2) and maps in physical pixels.
  Check the mapping on the laptop panel at 125% and 150%.

## Design notes

- **Why synthetic pointer injection:** `CreateSyntheticPointerDevice(PT_PEN)` +
  `InjectSyntheticPointerInput` gives apps a genuine pen (pressure 0–1024, tilt, eraser, barrel),
  which `SendInput` mouse events can't.
- **Stuck strokes:** the PC lifts the pen on socket loss, session end, `BYE`, and after 1 s without
  pen data; the tablet refreshes its pen state every 250 ms while in range and sends pen-up + leave
  when the surface loses focus, pauses or the screen turns off.
- **Hover edges:** Android sends `HOVER_EXIT` just before a touch-down and nothing when the pen is
  lifted straight out of range, so leaving range is decided after a 100–150 ms quiet period.
- **Mapping:** with *Keep aspect ratio* on, the PC sends the target's width/height ratio and the
  tablet letterboxes and outlines the active area to match.
- **Changing the movement ratio:** mapping is always absolute, like a Wacom (a spot on the tablet
  is always the same spot on the screen). To change how far the hand moves per screen distance,
  shrink one side: *Tablet area* on the tablet (25–100%, centred) means less hand movement; a
  screen *region* on the PC means more hand movement and finer detail. A stroke only draws if it
  starts inside the tablet area.
- **Security:** USB needs adb authorization. Wi-Fi uses a 4-digit code on the LAN and isn't
  encrypted, so keep it to networks you trust.

Out of scope for v1: screen mirroring, finger gestures, macOS/Linux hosts, iPad.

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
| `android/` | Kotlin app, min SDK 29. Dependency: the Anthropic Java SDK; bundled marked, KaTeX and Mermaid render answers. |
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
the PC types what you wrote into whatever text field has focus. The page clears the moment you tap
Enter, so you can carry on writing; each Enter's text is typed in order as soon as it's ready, and
anything that can't be read or typed comes back onto the page. Equations come out as Unicode
(`x² + √2 ≤ π`, `(x+1)/(x−1)`). **Undo** and **Clear** edit the ink, and the eraser end or the
held S Pen button erases whole strokes.

- **Recognition** uses Claude Sonnet 5.5 (`claude-sonnet-5-5`) with thinking off
  (`between_tools`), JSON output so the reply is only the transcription, and server-side refusal
  fallback. The ink is rendered black on white, cropped and scaled to at most 1400 px.
- **Fast handwriting** (main screen switch) reads with Claude Haiku 4.5 instead: about twice as fast
  and a third of the price, but it misreads messy writing and harder maths more often.
- **Convert on pause:** after 0.5 s without the pen touching, the ink is sent for recognition in
  the background. If nothing changed by the time you tap Enter, that result (or the request still
  in flight) is used, so the text usually appears straight away. A new stroke makes the earlier
  result stale, and a new request goes out at the next pause. The connection to Anthropic is opened
  as soon as you start writing, so the first conversion doesn't wait for it.
- **Unicode maths:** the prompt asks for real symbols (x², a₁, √, ≤, π), and a converter on the
  tablet (`UnicodeMath.kt`) also turns any `x^2`, `a_1`, `sqrt(…)`, `<=` or LaTeX that slips
  through into Unicode. Superscripts with no Unicode form (e.g. `q`) stay as `x^(q)`.
- **Cost:** your own Anthropic API key (entered on the tablet's main screen, stored in app-private
  storage), billed to that account. Roughly a third of a cent per conversion, and convert-on-pause
  makes about 1.5–2 requests per Enter. The writing image is sent to Anthropic.
- The PC types with Unicode keystrokes, so any character works regardless of keyboard layout.
  Windows doesn't let it type into apps running as administrator unless Slate does too.

## Output modes and the calculator

The x² button on the writing surface cycles how maths is written out:
**Unicode** (`dy/dx`, `x²`, works anywhere), **Equation** (maths pasted as real equations via MathML,
for Word/PowerPoint/OneNote; prose still typed; your clipboard is restored) and **LaTeX**
(`$\frac{dy}{dx}$`, for Overleaf/Notion/Obsidian). Line breaks are typed as Shift+Enter by default
so chat apps don't send half a message (PC setting "Line breaks in handwriting typed as").

**Calc** switches the surface to a calculator: write a calculation and tap **=**. Claude Opus 5.5
reads and solves it (algebra and calculus too, with the key steps); plain arithmetic is re-checked
by the tablet's own evaluator. The answer is copied to the tablet and PC clipboards in the current
output mode; **Type it** types it instead.

## Slate Keyboard

A system keyboard for the tablet itself: write in any app's text field and tap Enter. Turn it on
once from Slate's main screen (Settings › Keyboard list). Works without a PC.

It also has a plain typing layout: **⌨ Type** on the handwriting pad and **✎ Write** on the typing
layout switch instantly inside the one keyboard (no closing and reopening). Its **⌨** key goes to
your other keyboard (Samsung Keyboard) when you want autocorrect or swipe typing.

To get to it from Samsung Keyboard (or any other), turn on the **Slate helper**
accessibility service (main screen › Slate Keyboard › 3): a small **✎ Slate** button then sits
just above any other keyboard while it's open, and one tap switches to Slate Keyboard (Android 11+).
For this it only looks at where the keyboard window is and which keyboard it is. Slate helper also
lets Ask Claude screenshot the tablet without Android's capture prompt (below). ⌨ on Slate Keyboard
switches back. On a sideloaded APK, Android may first need *App info › ⋮ › Allow restricted settings*.

## Screen mode (mirroring and navigation)

**Screen** on the drawing surface shows the PC's screen on the tablet, like a second display:

- H.264 video over the same USB/Wi-Fi link. The PC downloads ffmpeg once (~115 MB) and uses DXGI
  capture plus the fastest encoder that works (NVIDIA, AMD, Intel, Windows Media Foundation, or x264).
- The S Pen is a real pen right on the picture, at any zoom. Fingers navigate: tap = click, hold =
  right-click, drag = drag, two fingers = scroll or pinch-zoom the view, three-finger swipes =
  switch apps / task view / desktop. Or switch fingers to real Windows touch in ⋯ View.
- **Write straight into the PC:** tap a text field on the mirrored screen and a handwriting pad
  slides up over the bottom of the tablet. The PC's screen shrinks into the space above it and
  ignores touches while you write; **Enter** types what you wrote, ↵/⌫/Space press the real keys,
  **✕ Screen** (or Back) goes back to navigating. The PC spots text fields with UI Automation
  (password fields are skipped). Turn it off, or open the pad by hand, in ⋯ View.
- **Resolution** (⋯ View): *Match this tablet* (default) scales the PC's picture down to the tablet's
  screen, *1080p* is lightest; *Stream stats* shows fps sent/received/shown, the delay from the PC's
  encoder to the tablet's screen, encoder and capture method.
- ⋯ View: fit, shrink, zoom, actual pixels, follow the cursor when zoomed, mini-map, monitor and
  quality. Shortcut buttons down the left edge are editable on the main screen.

## Ask Claude about the screen

In Screen mode tap **Ask**: the PC's screen freezes at full resolution. Circle or highlight things,
write your question, then tap **Ask**, **Explain**, **Step by step**, **Just a hint**,
**Check my working** or **Quiz me**. Whatever you circled is also sent as a zoomed crop.

- **Claude Code session** (PC setting, default): each question is a turn in a Claude Code session on
  the PC, streamed back to the tablet. **Open in Claude** opens the session with Remote Control, so
  it's also at claude.ai/code and in the Claude app, and Claude Desktop can pick it up with /resume.
  Needs Claude Code installed and signed in on the PC.
- **Claude Desktop app**: the question is pasted into Claude Desktop as a normal chat (the app pops
  up the first time, then stays in the background); replies stay in the app.
- **Your Claude subscription without the PC:** with Claude Code installed in Termux on the tablet,
  Ask goes to it instead of the API key (main screen › *Claude Code on this tablet* › Set up: paste
  the command into Termux, then run `slate-claude`). Order: PC → Claude Code in Termux → API key
  (the last only if you allow it). Optionally handwriting and calculator go through it too (slower).
  The bridge (`assets/termux/slate-bridge.js`) listens on 127.0.0.1 only and needs a token Slate
  generated at setup; questions go in as one message with the images inline (no tool round trip).
- **Floating chat box:** after you ask, the answer streams into a small chat box on top of whatever
  you're looking at (like a side chat). Keep chatting there: type or handwrite a reply, ✎ draw on the
  answer, drag it by its title, — to collapse, ⤢ to open in History. Needs Android's *Appear on top*
  permission (Slate offers it the first time, or main screen › *Floating chat box…*).
- **History** is also a chat: type (or handwrite with Slate Keyboard) in the message box to carry on
  the conversation, or **+ New chat**. **Follow up** / **✎ Draw** screenshots the answer so you can
  circle part of it and write your question on it. Answers render with maths and diagrams;
  **Export PDF** and **Share Markdown** keep a copy.
- **Ask Claude** (Air command, tile or icon) always captures the screen afresh, even when a previous
  question is still open in Slate.
- **The tablet's own screen**: the "Ask Claude" app shortcut (add it to the S Pen's Air command via *Add shortcuts*), the "Ask Claude" Quick Settings tile (or sharing a screenshot to Slate). With Slate helper on (Android 11+), these capture the screen straight away; otherwise Android asks for screen-capture permission each time. The helper only takes a screenshot when you tap Ask Claude
  asks about whatever is on the tablet. Without a PC these go to Claude Opus 5.5 with your API key.
- The main screen shows this month's approximate API spend.

## Tests

```sh
cd windows && dotnet test                 # 85 tests, incl. video pipeline with real ffmpeg and a fake Claude Code
cd android && ./gradlew testDebugUnitTest # 55 tests: protocol, H.264/SPS, viewport, transcripts, calculator, study store
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
- **Laptops with two GPUs** (Intel + NVIDIA/AMD): fast DXGI capture only works from the GPU the
  screen is wired to. Slate tries every adapter, and if that fails sets Windows' graphics preference
  for its own ffmpeg.exe to *Power saving* (Settings › Display › Graphics) and encodes with Intel Quick
  Sync, or copies frames to the other GPU's encoder. The stream stats show `DXGI` when this works.
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

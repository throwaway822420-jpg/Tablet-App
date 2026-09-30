# Slate wire protocol (v1)

This file is the single source of truth for the packet format. The Android
encoder (`android/app/src/main/java/app/slate/tablet/protocol/Packet.kt`) and
the Windows decoder (`windows/Slate.Core/Protocol.cs`) both implement it,
and both test suites check the golden vector at the bottom.

## Transports and ports

| Port  | Proto | Listener | Purpose |
|-------|-------|----------|---------|
| 47810 | UDP   | tablet   | Discovery: the PC broadcasts `DISCOVER` once a second |
| 47811 | UDP   | PC       | Wi-Fi session: the tablet sends `HELLO`/`PEN`/…; the PC replies to the sender's address |
| 47812 | TCP   | tablet (loopback only) | USB session: the PC runs `adb forward tcp:47812 tcp:47812` and connects to `127.0.0.1:47812` |

Over TCP the stream is a plain concatenation of 32-byte packets (no extra
framing). Over UDP each datagram holds exactly one packet. `TCP_NODELAY` is
set on both ends.

## Packet layout

Every packet is **exactly 32 bytes, little-endian**.

| Offset | Type | Field |
|-------:|------|-------|
| 0  | u8  | `type` (see below) |
| 1  | u8  | `version`, currently `1` |
| 2  | u16 | reserved, 0 |
| 4  | u32 | `seq`, per-connection counter of the sender, starting at 0 and wrapping |
| 8  | 20 bytes | type-specific payload (unused bytes are 0) |
| 28 | u32 | `timestampMs`, the sender's monotonic clock in ms (low 32 bits) |

A receiver that gets a packet whose `version` it doesn't support replies
`BYE(reason=3)` and closes the connection.

### Types

| Value | Name | Direction | Payload (offsets are from the packet start) |
|------:|------|-----------|---------|
| 1 | `HELLO`    | tablet → PC | `8 u16 surfaceWidthPx`, `10 u16 surfaceHeightPx`, `12 u16 pairCode` (0–9999, or `0xFFFF` over USB), `14..27` device name, UTF-8, NUL-padded (14 bytes) |
| 2 | `PEN`      | tablet → PC | see below |
| 3 | `LEAVE`    | tablet → PC | none. The pen left hover range: lift it if down, then remove it from range |
| 4 | `CONFIG`   | PC → tablet | `8 f32 aspect` (target width / height in physical pixels; `0` = use the whole surface), `14..27` PC name, UTF-8, NUL-padded (14 bytes). Sent in reply to an accepted `HELLO` and again whenever the mapping changes |
| 5 | `PING`     | tablet → PC | `8 u16 lastRttMs` (the tablet's last measured round trip, `0xFFFF` = unknown) |
| 6 | `PONG`     | PC → tablet | `8 u32 echoTimestampMs` (the `timestampMs` of the `PING` being answered) |
| 7 | `DISCOVER` | PC → broadcast | `8 u16 udpPort` (normally 47811), `12..27` PC name, UTF-8, NUL-padded (16 bytes) |
| 8 | `BYE`      | either | `8 u8 reason`: 0 normal, 1 wrong pairing code, 2 PC busy with another tablet, 3 unsupported version |
| 9 | `TEXT`     | tablet → PC | One chunk of text for the PC to type: `8 u16 textId`, `10 u8 chunkIndex`, `11 u8 chunkCount` (1–255), `12..27` chunk, UTF-8, NUL-padded (16 bytes) |
| 10 | `TEXT_ACK` | PC → tablet | `8 u16 textId`: the whole message arrived and was typed |

### `PEN` payload

| Offset | Type | Field |
|-------:|------|-------|
| 8  | u8  | `tool`: 0 pen tip, 1 eraser end |
| 9  | u8  | `flags`: bit0 `CONTACT`, bit1 `IN_RANGE`, bit2 `BARREL`, bit3 `HAS_ROTATION`, bit4 `HAS_TILT` |
| 10 | u16 | `pressure`, 0–1024 (0 while hovering) |
| 12 | f32 | `x`, 0..1 across the active area, left → right |
| 16 | f32 | `y`, 0..1 across the active area, top → bottom |
| 20 | i8  | `tiltX`, −90..90 degrees, positive = top of pen leans right |
| 21 | i8  | `tiltY`, −90..90 degrees, positive = top of pen leans toward the user |
| 22 | u16 | `rotation` (barrel twist), 0–359 degrees clockwise |
| 24 | u32 | reserved, 0 |

`CONTACT` implies `IN_RANGE`. `HAS_TILT` / `HAS_ROTATION` tell the PC
whether the tablet reports that axis at all, so it doesn't claim tilt of 0 to
applications when the hardware has none. The S Pen doesn't report barrel
twist, so Android never sets `HAS_ROTATION`.

## Session flow

### USB

1. Tablet listens on `127.0.0.1:47812`.
2. PC runs `adb forward tcp:47812 tcp:47812`, connects to `127.0.0.1:47812`.
3. Tablet sends `HELLO` (`pairCode = 0xFFFF`). PC replies `CONFIG`, or
   `BYE(2)` if another tablet holds the session.
4. Tablet streams `PEN`/`LEAVE`, and `PING` once a second; PC answers each
   `PING` with a `PONG`.

### Wi-Fi

1. PC broadcasts `DISCOVER` to port 47810 once a second (to
   `255.255.255.255` and to each interface's directed broadcast address).
2. Tablet lists PCs by name and source address. The user picks one and enters
   the 4-digit code shown on the PC.
3. Tablet sends `HELLO` with the code to the PC's `udpPort`, repeating every
   500 ms until it gets `CONFIG` or `BYE` (give up after 5 s).
4. Same streaming as USB. The PC drops any packet whose `seq` isn't newer than
   the last one accepted, comparing as `(int32)(seq - lastSeq) > 0` so
   wrap-around works. The tablet sends pen-up/`LEAVE` three times (new `seq`
   each time) since these are state packets and a lost one would leave a stuck
   stroke.

### Liveness and stuck-stroke rules

- While the pen is in range the tablet re-sends its last `PEN` state at
  least every 250 ms, even when nothing changed.
- If the pen is in range or down and the PC gets no `PEN`/`LEAVE` for
  1000 ms, the PC lifts the pen and takes it out of range.
- If the PC gets nothing at all for 5000 ms, it ends the session.
- On socket loss, session end or `BYE` the PC always lifts the pen and takes
  it out of range before doing anything else.
- The tablet sends pen-up + `LEAVE` when its canvas loses focus, pauses or
  the screen turns off.

### Text (write mode)

Write mode turns handwriting into text on the tablet and sends it as a `TEXT` message:

- The text is split into chunks of at most 16 UTF-8 bytes, never inside a character, so each
  chunk is valid UTF-8 on its own. At most 255 chunks (about 4 KB) per message.
- The PC collects chunks by `textId` in any order. Once it has all `chunkCount` of them it
  lifts the pen, types the concatenated text into the focused window (`\n` as Enter), and replies
  `TEXT_ACK`.
- The tablet resends the whole message after 1 s without `TEXT_ACK`, up to 3 times in total.
  The PC remembers the last 32 completed ids per session and acks a repeat without typing it again.

### Rich text in TEXT messages

Private-use characters (never produced by handwriting) mark up a TEXT message:

- `U+E000 mathml U+E004 unicode U+E001`: an equation. The PC pastes the MathML through the
  clipboard (Word/PowerPoint turn it into a real equation) and types the Unicode if it can't.
- A message starting with `U+E002`: set the PC clipboard to the rest instead of typing it,
  optionally followed by `U+E003` and MathML (calculator answers).

## Bulk channel (TCP 47813)

Everything too big for 32-byte packets: screen video, screenshots, control messages, Claude replies.
The PC listens; over USB the tablet reaches it through `adb reverse tcp:47813 tcp:47813` (loopback,
no code needed), over Wi-Fi it connects to the PC's address and must present the pairing code.

Frame: `u32 length` (of what follows) `| u8 kind | body`, little-endian.

| Kind | Name | Body |
|---:|---|---|
| 1 | HELLO | tablet → PC first: JSON `{"code": pairing code or -1, "device": name}` |
| 2 | JSON | a JSON object with a `"t"` (type) field |
| 3 | VIDEO | PC → tablet: `u8 flags` (bit0 keyframe) `| i64 pts µs |` one H.264 Annex-B access unit |
| 4 | BLOB | `u32 json length | JSON header | binary` (images) |

JSON types:

| `t` | Direction | Fields |
|---|---|---|
| `welcome` | PC → tablet | `pc`, `monitors: [{id, name, w, h, primary}]`, `current` |
| `denied` | PC → tablet | wrong pairing code |
| `stream.start` | tablet → PC | `monitor` (id or ""), `fps`, `usb`, optional `bitrate` |
| `stream.stop` | tablet → PC | |
| `stream` | PC → tablet | `state`: setup / starting / running / stopped / error, `message`, and when running `w, h, fps, encoder, monitor` |
| `cursor` | PC → tablet | `x, y` (0..1 of the mirrored monitor) |
| `textfocus` | PC → tablet | `editable` (bool). Sent while streaming when keyboard focus moves into (true) or out of (false) a text field, per UI Automation. The tablet opens its handwriting pad only if the user touched the picture in the last 2.5 s. Password fields count as not editable. |
| `mouse` | tablet → PC | `action`: move / down / up / click / dblclick / rightclick, `x, y` |
| `scroll` | tablet → PC | `x, y`, `dx, dy` in wheel notches (fractions allowed; +dy scrolls down) |
| `keys` | tablet → PC | `combo`, e.g. `Ctrl+Shift+Z`, `Alt+Tab`, `Win+D`, `F5` |
| `touch` | tablet → PC | `contacts: [{id, x, y, phase: down/move/up}]` (Windows touch injection) |
| `shot` | tablet → PC | request a full-resolution screenshot; answered with a `shot` BLOB `{w, h}` + JPEG |
| `ask` (BLOB) | tablet → PC | `{askId, session, new, intent, title, images: [{name, size}]}` + the JPEGs back to back |
| `ask.status` | PC → tablet | `askId`, `state` (thinking / error), `message`, `session` |
| `ask.delta` | PC → tablet | `askId`, `text` (streamed reply) |
| `ask.done` | PC → tablet | `askId`, `session`, `backend` (code / desktop), `title`, `markdown`, `cost` |
| `ask.open` | tablet → PC | `session`: open it in the Claude apps |

Video: no B-frames, a keyframe every second, an access unit delimiter before every frame. When the
link falls behind, the PC drops delta frames until the next keyframe rather than queueing.

## Coordinates and aspect ratio

The PC maps `x, y` onto its target rectangle (a monitor or a user-chosen
region of one) in **physical pixels**. When "preserve aspect ratio" is on,
the PC sends `aspect = width / height` of that rectangle; the tablet
letterboxes an active area of that aspect ratio in the middle of its screen,
draws its outline, and reports `x, y` relative to that area (clamped to
0..1). With `aspect = 0` the whole screen is the active area.

## Golden vector

`PEN`, seq 1, tool pen, flags `CONTACT | IN_RANGE | HAS_TILT` (0x13),
pressure 512, x 0.5, y 0.25, tiltX −30, tiltY 15, rotation 0,
timestampMs 1000:

```
02 01 00 00  01 00 00 00  00 13 00 02  00 00 00 3F
00 00 80 3E  E2 0F 00 00  00 00 00 00  E8 03 00 00
```

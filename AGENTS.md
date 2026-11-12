# BeatPad 节奏鼓台 · io.embodit.beatpad

Full Space (Stage · Mixed passthrough) drum deck for PICO OS 6 (SDK BOM 6.1.9): a curved walnut deck with 8 pads in front
of you, controllers are the sticks (swing speed = loudness), hands and pointing also work; every hit you record lands
on a 16-step beat ring behind the deck and loops. Design package: factory `apps/beat-pad/design-package.json` (gate B
2026-11-12: G4 Full Space, G1 scoring → v2, G2 passthrough only, G3 procedural PBR). Started from spatial-calc
(Stage + one Planar window + LevelGate + board-input template); controller code after pruning-class / qubic-tower.

## Structure

```
Main.kt                     DefaultStage (Mixed) + one Planar WindowContainer "BeatMain" 440 × 540 dp
domain/beat/Model.kt        Pattern (8 pads × 16 steps, 0–3), kits + names, 6 grooves, SavedBeat, constants
domain/beat/Timing.kt       Tempo / quantise, HubDrag (1 cm = 2 BPM, snaps 80/100/120), RingDrag (0.35-slot hysteresis), HitCurve
domain/beat/StickHit.kt     StrikeDetector: tip crosses a pad face from above, interpolated time, rearm 1.5 cm, 50 ms
domain/beat/Layout.kt       spatialLayout in deck-local metres (pads, ring, rec key, labels), reach, window-side yaw
domain/place/Arrange.kt     THE placement function: cold start, every window open (reopen / HUD / recall), [回到面前], panel side
domain/place/HeadSample.kt  D29 sampler: after [回到面前] wait for a turn away from the button + still + level
domain/beat/BeatFlow.kt     the whole state machine D0–D7 × P1–P5; every input (window, 3D keys, sticks, debug) calls it
platform/audio/Mixer.kt     own low-latency mixer + sequencer (24 voices, choke, pan, limiter, count-in, quantise, R1-05b guard)
platform/audio/AudioEngine  AudioTrack LOW_LATENCY + SPATIALIZATION_BEHAVIOR_NEVER, URGENT_AUDIO thread, LATENCY log, decode
platform/input/Trackers.kt  Controllers (poses, A/X B/Y edges, stick x) · Hands (index tip / palm)
platform/Platform.kt        Graph (process store), storage (files/beats.json, prefs), import → files/imports/*.wav
platform/ImportActivity     system document picker host (T13)
ui/stage/StageScene.kt      deck, pads, labels, ring, stones, lanes, knob, playhead, rec key, sticks, rays, fly stones, HUD
ui/stage/Meshes.kt          lathe / torus / deck-body / disc meshes (all round parts ≥ 24 segments)
ui/stage/Textures.kt        procedural walnut / brushed aluminium, text plates for 3D words
ui/stage/input/BoardInput   factory board-input template (tap + drag + hover, names → targets)
ui/window/MainWindowUi.kt   native TabBar 演奏 / 节拍库 / 设置 (+ sub pages), D6 place page, D7 pick page
app/src/debug               DebugCommandReceiver
tools/build_sounds.py       how assets/sounds/*.wav were made from ~/Downloads/cc0-sounds (Kenney CC0)
```

## Debug broadcast

`adb shell am broadcast -a io.embodit.beatpad.DEBUG -p io.embodit.beatpad --es cmd <cmd> --es a "<arg>"`
Logs: tag `BeatPad` (also files/input-log.txt: `adb shell run-as io.embodit.beatpad cat files/input-log.txt`), prefixes
STATE HIT STRIKE REC PLAY SLOT DRAG DROP BPM LOAD SAVE KIT PICK IMPORT PLACE DIAG LATENCY AUDIO WINDOW PAGE CMD DUMP OVERFLOW.

| cmd | what |
|---|---|
| `tap` `pad N` / `slot N` / `hub` / `rec` | pointer tap (N from 1) — same path as a system tap |
| `hit` `N VEL` | a stick strike on pad N (0–1) at the frame being heard (records when recording) |
| `drag` `stone N TO` / `stone N off` / `hub CM` | drag a stone of the focus pad (append `hold` to keep D5 on screen, `drag end` to drop) / turn the knob |
| `rec` · `play` · `stop` · `undo` · `key A|B|X|Y` | transport (A/X = record, B/Y = play/stop) |
| `groove ID` · `card N` · `load` · `save` · `confirm overwrite|clear|load|cancel` | library (ids: garage friday corner dancefloor beach rainy; card 0 blank, 1–6 grooves, 7–18 my slots) |
| `kit acoustic|electronic|hand` · `bpm N` · `metro 0|1|2` · `volume 0-2` · `mute on|off` · `lang zh|en|system` | settings |
| `page play|library|settings` · `sub none|sound|kit|padsound|more|about` | window pages |
| `place` · `turn ±DEG` · `stick X` · `front` · `side left|right` · `placedone` | D6 |
| `pick` · `pickpad N` · `import PATH` · `importdrop` · `importui` · `pickend` | D7 / T13 (`importdrop` = newest file in Android/data/io.embodit.beatpad/files/Music) |
| `hold Dn` / `unhold` | freeze animation time and the count-in (D2 stays on its number) for screenshots of short states (D2, D3 flying stones) |
| `fly N S1,S2…` | launch flying stones from pad N to steps (picture of D3) |
| `notice NAME [ARG]` | show a window notice (IMPORT_OK "kick|1.2", IMPORT_FAIL, IMPORT_LONG, SAVED, FULL …) |
| `exitask` · `closewindow` · `recall` · `wipe` · `dump` | misc (`recall` = settings [叫回面前]: close + level-gated reopen ahead of the head; the deck turns aside via `Arrange.onWindowOpen`) |

## Screenshot list for QA (design states[])

D0 (cold start < 0.5 s) · D1 empty + coach / D1 with「车库排练」(zh, en) · D2 (`rec`, `hold D2`) · D3 (`rec`, hits, `fly`,
`hold D3`) · D4 (`play`) · D5 (`drag stone 1 4 hold`, `drag stone 1 off hold`) · D6 (`place`, zh/en) · D7 (`pick`) ·
P1 in D1/D3/D4 · P2 blank / groove / empty slot / my slot + overwrite confirm · P3 home + sound / kit / pad sound / more /
about (zh, en) · P4 exit confirm, slot confirm · P5 import ok / fail / too long (`notice …`).

## Build

`JAVA_HOME=~/.pico/primer-cli/jdk/jdk-21.0.13+9/Contents/Home ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Unit tests also render `app/build/beatpad-qa/` (loudness, solos per kit, 6 grooves × 30 s) through the app mixer.

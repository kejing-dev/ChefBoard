# ChefBoard 大厨看板 · io.embodit.chefboard

Full Space (Stage · Mixed passthrough) kitchen timers for PICO OS 6 (SDK BOM 6.1.9): a walnut rack with four ceramic
3D timers sits to the right of a recipe window. Look at a timer and pinch → it floats up (5:00, or the step's time);
look at one of five rings on the counter and pinch → it flies there and starts. Pinch it to pause, [+1] / [收回] when
paused; at zero it shakes, pulses amber and rings at its own position; an amber bar in the window stops it without
turning round. Eye + pinch only (no drag / twist / stick). Design package: factory `apps/chef-board/design-package.json`
(r2, gate B 2026-11-12). Built on the factory Stage template (Stage + one Planar window + LevelGate + board-input).

## Structure

```
Main.kt                     DefaultStage (Mixed) + one Planar WindowContainer "ChefMain" 640 × 560 dp
domain/place/Layout.kt      design spatialLayout in the kitchen frame (eye origin, −Z = stove): window az −20°, rack +22° 0.9 m,
                            rings −36…+36° at 0.70 m / eye −0.68 m, stage point, tag / button rows, D35 boxes
domain/place/Arrange.kt     THE placement function: cold start, every window open, [叫回面前]
domain/place/LevelGate.kt   D9 level gate (pitch ignored after 3 s) + ChefBoard's yaw target for reopen (window opens while the user glances at az −20°, 8 s fallback)
domain/place/ColdStart.kt   v0.1.1: first steady head (any pitch / roll) → kitchen at once, stove 20° right of the heading, window straight ahead
domain/place/HeadSample.kt  D29 sampler for [叫回面前] (face the stove, hold still)
domain/timer/Timers.kt      TimerBoard: docked → staged → flying → running ⇄ paused → ringing → stopped → returning; all globalRules
domain/recipe/*             8 built-in recipes (zh / en), CookPages (prep pages + steps), Parser (paste → steps + times)
domain/cook/CookFlow.kt     page layer S00–S11 + settings; every input (window, 3D taps, debug) calls it
platform/Platform.kt        Graph (process store), sounds (Ev → mixer / spatial bell), storage (prefs + files/recipes.json), debug clock freeze
platform/audio/*            AudioTrack + small mixer (variants, ±5 % pitch, one instance per kind, pan, −1 dBFS limiter)
ui/stage/StageScene.kt      rack, timers (lathe body, bezel, rotating dial, progress arc), tags, 3D buttons, rings, arrow, HUD
ui/stage/FoodShelf.kt       v0.1.4 E7 step food: Tripo models of the current cook page on the counter (az −18°, 0.95 m),
                            real size, fade 300 / 180 ms, no colliders; StepModels.kt = catalog (id, size, Tripo prompt) +
                            recipe step → model ids (prep = every ingredient, pasted recipe = basket)
ui/stage/input/BoardInput   factory board-input template (tap + hover, names → targets)
ui/window/MainWindowUi.kt   native TabBar 菜谱 / 做菜 / 设置, paste card, result card, exit confirm, amber bar
app/src/debug               DebugCommandReceiver
tools/prep_food.py          ~/Downloads/chef-board-tripo/<id>.glb (raw Tripo) → assets/models/food/<id>.glb: decimate to
                            12k tris (bin/glb_decimate.py), D25 calibrate (bottom centre at origin, y up, longest side =
                            sizeM), OPAQUE; writes index.json (w/d/h). Run with ~/.factory-venv/bin/python
tools/build_sounds.py       assets/sounds/*.wav from ~/Downloads/cc0-sounds (Kenney CC0)
tools/loudness.py           offline loudness evidence (rendered with the app's gains)
tools/build_icon.py         512 px launcher icon PNG
```

## Debug broadcast

`adb shell am broadcast -a io.embodit.chefboard.DEBUG -p io.embodit.chefboard --es cmd <cmd> --es a "<arg>"`
Logs: tag `ChefBoard` (also `adb shell run-as io.embodit.chefboard cat files/input-log.txt`), prefixes STATE PAGE TIMER
TAP3D BELL PLACE WINDOW HUD DIAG CMD DUMP WHERE OVERFLOW LINES SOUND LANG PASTE FREEZE EXIT.

| cmd | what |
|---|---|
| `state S00…S11` | jump to a design state (S04 = R4 step 3 + [⏱ 10 分]; S05 = 2 timers on rings −18° / +18°; S05p = + pause timer 1; S06 / S07 = timer 1 rings; S06s = rings, stopped, clock frozen on「好了 ✓」) |
| `page recipes\|cook\|settings` · `more` · `recipe R4 [pageIndex]` · `step N` · `next` · `prev` · `finish` · `again` | page layer |
| `stage SEC` · `cancel` · `tap plus1\|plus5\|clear\|pplus1\|back\|plate\|timer N\|spot N` | timer layer, same path as a pinch (N from 0) |
| `timers N` · `pause SLOT` · `ring SLOT` · `stop` | QA shortcuts (only the time is set; transitions are the real ones) |
| `freeze on\|off` | the app clock stands still (timers, flights, bells, animations) for short states |
| `paste sample\|<text>` · `split` · `save` · `edit` · `pasteclose` | S02 |
| `mute on\|off` · `volume 0-2` · `lang zh\|en\|system` · `recall` · `exitask` · `exitcancel` · `exit` | settings |
| `closewindow` · `openwindow` · `wipe` · `dump` · `where` | misc (`where` = world positions of rack / rings / timers) |
| `food` | `FOODDUMP` line: food models on the counter now (id @ kitchen-frame base), templates loaded, failed ids |

## Screenshot list for QA (design states[])

S00 (cold start, or `state S00`) · S01 zh / en (first start shows「捏我计时」3 s) · S02 input + preview (`paste sample`, `split`) ·
S03 R4 step 3 / step 1 / prep / en step 3 · S04 (`state S04`, then `tap plus5`) · S05 (`state S05`, turn the head: they stay) ·
S05p · S06 (`state S06`, record 3 s) · S06s · S07 (`state S07` + turn the emulator camera 120°) · S09 · S10 zh / en · S11.
v0.1.4 step food: every page of every built-in recipe (`recipe R1 0` … `recipe R8 7`), zh and en, camera reset — the food
stands on the counter below the window, left of the rack.
Window = camera reset; the rack and rings are to the right / below (turn the camera right ~25° and down ~30°).

## Build

`JAVA_HOME=~/.pico/primer-cli/jdk/jdk-21.0.13+9/Contents/Home ./gradlew :app:assembleDebug :app:testDebugUnitTest`

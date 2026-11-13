# 大厨看板 ChefBoard

![大厨看板 ChefBoard](docs/cover.jpg)

[中文](#中文) · [English](#english)

## 中文

做菜时手上全是油和面粉，不想碰任何东西。大厨看板是 PICO OS 6 上的混合现实厨房计时器：灶台旁一块木架上放着四只陶瓷计时器，**看一眼、捏一下**，它就浮起来；再看锅边的一个圈、捏一下，它飞过去落在锅边开始走。到点它在锅边晃动、发亮、响铃，窗口底部的琥珀条一捏就停，不用转身。

- **只靠眼手**：注视 + 捏合，不用拖、不用拧、不用手柄
- **计时器放到锅边**：五个圈排在灶台前，每只计时器在自己的位置响铃，几口锅同时开也分得清
- **步骤自带时间**：菜谱一屏一句；带时间的步骤直接给出「⏱ 10 分」，捏一下就拿起对应时长的计时器；浮起时可 +1 / +5 / 清零
- **贴一段文字就成菜谱**：粘贴自己的做法，自动拆成步骤并识别里面的时长
- 8 道内置菜谱，中英双语，全部本机运行，不联网、不需要账号

| | |
|---|---|
| ![两只计时器在锅边走](docs/shot-01.jpg) | ![步骤自带时间](docs/shot-02.jpg) |
| 两只计时器在锅边走，窗口停在下一步 | 「水开了？下面，十分钟。」步骤自带 ⏱ 10 分 |
| ![计时器浮起，挑一个圈](docs/shot-03.jpg) | ![到点响铃](docs/shot-04.jpg) |
| 浮起 15:00，看个圈 · 捏一下；+1 / +5 / 清零 | 到点在锅边响，琥珀条「煮面好了 · 停」 |
| ![挑一道菜](docs/shot-05.jpg) | ![粘贴成菜谱](docs/shot-06.jpg) |
| 菜谱页：菜名 + 时长 + 计时器个数 | 粘贴一段做法 → 6 步 · 3 个计时 |

截图和 Release 里的演示视频都是在 PICO 模拟器里用调试命令驱动拍的，**非真实手势录像**。

### 安装

需要 PICO OS 6 设备（Spatial SDK 6.1.9，minSdk 35）。从 [Releases](https://github.com/kejing-dev/ChefBoard/releases) 下载 `ChefBoard-v0.1.3.apk`：

```
adb install -r ChefBoard-v0.1.3.apk
pico-cli app launch io.embodit.chefboard
```

Release 里的 APK 是 `./gradlew assembleRelease` 出的未签名包；要直接 `adb install`，先用自己的 key 签名，或从源码 `./gradlew :app:installDebug`。

### 从源码构建

```
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

代码结构、调试广播和截图清单见 [AGENTS.md](AGENTS.md)。

## English

When you cook, your hands are covered in oil and flour. ChefBoard is a mixed-reality kitchen timer for PICO OS 6: four ceramic timers sit on a walnut rack beside the stove. **Look at one and pinch** and it floats up; look at a ring by a pot and pinch, and it flies there and starts. When time is up it shakes, glows and rings right by that pot, and one pinch on the amber bar in the window stops it — no turning round.

- **Eyes and pinch only**: gaze + pinch, no dragging, twisting or controllers
- **Timers sit by the pot**: five rings in front of the stove; each timer rings at its own spot, so several pots never get mixed up
- **Steps carry their time**: one recipe sentence per screen; a timed step offers "⏱ 10 min" and one pinch picks up a timer with that time; +1 / +5 / Clear while it floats
- **Paste text, get a recipe**: paste your own method and it is split into steps with the times found
- 8 built-in recipes, Chinese and English, fully offline, no account

| | |
|---|---|
| ![Two timers running by the pots](docs/en/shot-01.jpg) | ![A step with its own time](docs/en/shot-02.jpg) |
| ![A floating timer, pick a ring](docs/en/shot-03.jpg) | ![Ringing by the pot](docs/en/shot-04.jpg) |
| ![Pick a dish](docs/en/shot-05.jpg) | ![Paste a recipe](docs/en/shot-06.jpg) |

Screenshots and the demo video in the Release were captured in the PICO emulator, driven by debug commands — **not real hand-gesture footage**.

### Install

Needs a PICO OS 6 device (Spatial SDK 6.1.9, minSdk 35). Download `ChefBoard-v0.1.3.apk` from [Releases](https://github.com/kejing-dev/ChefBoard/releases):

```
adb install -r ChefBoard-v0.1.3.apk
pico-cli app launch io.embodit.chefboard
```

The Release APK is the unsigned `./gradlew assembleRelease` build; sign it with your own key before `adb install`, or build from source with `./gradlew :app:installDebug`.

### Build from source

```
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Structure, debug broadcast and screenshot list: [AGENTS.md](AGENTS.md).

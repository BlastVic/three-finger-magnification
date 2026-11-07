# 三指双击放大（Triple-Tap Zoom）

[English README](README.md) · [下载 v1.0](https://github.com/BlastVic/three-finger-magnification/releases/tag/v1.0) · [MIT 许可证](LICENSE)

> 把 iOS 上「**三指双击放大屏幕，再次三指双击取消**」的手势带到 Android：全局可用，游戏里也能用，而且**不改变其他任何触摸操作**。

开发者：**Vic Yang**。完全免费开源，献给所有被安卓自带放大手势坑过的人。

---

## 为什么要做这个

安卓自带的「**点按三次放大**」识别的是**单指连续快速点三下**。而在音游、射击游戏里，或者只是快速连点链接时，这正是最常见的操作 —— 于是屏幕在关键时刻莫名其妙地放大了。

iOS 用的是**三根手指同时双击**：这个手势几乎不可能误触。本项目就是在 Android 上复刻它。

三个设计目标，按优先级排列：

1. **任何场景都能响应** —— 桌面、浏览器、视频、全屏游戏。
2. **只处理这一个手势。** 单指点击、长按、滑动、边缘返回、底部上滑回桌面、下拉通知栏，必须和没装这个 App 时**完全一样**。不缓冲、不拦截、不注入任何触摸事件。
3. **不需要 root、不联网、不收集数据。**

> 最早的版本用的是无障碍服务的触摸接管 API，手势确实能识别，但代价是：底部上滑回桌面失效、点图标变成"长按"、随机出现幽灵长按。这条路线已彻底废弃。现在发布的版本改成**只读**触摸事件。

---

## 功能

| | |
|---|---|
| **触发** | 屏幕任意位置，**三根手指双击** |
| **取消** | 再做一次同样的手势，恢复 1.0× |
| **放大中心** | 跟随三指位置（类 iOS，默认）或固定在屏幕中心 |
| **放大倍数** | 1.5× – 5.0×，默认 2.0× |
| **放大时** | 单指点击照常可用；双指拖动平移画面 |
| **覆盖范围** | 全局，包括全屏游戏、沉浸式应用 |
| **附加** | 切换时振动、切换 App 后保持放大、快捷设置磁贴、屏内手势诊断日志 |
| **语言** | 默认英文；系统语言为中文时自动切换中文 |
| **隐私** | 无 `INTERNET` 权限、不读取屏幕内容、无统计埋点，日志只存本机 |

---

## 为什么必须装 Shizuku

`AccessibilityService` 想拿到原始触摸事件，就必须开启 **触摸探索（touch exploration）**。而触摸探索会改写单指语义：点击变成"先探索再激活"、凭空出现长按、系统手势导航直接失效；不少厂商 ROM 也不会把多指手势的事件副本回传给无障碍服务。也就是说，用公开的无障碍 API，「零干扰」和「拿到触摸副本」**只能二选一**。

所以本项目借助 [Shizuku](https://shizuku.rikka.app/)：

- Shizuku 以 **shell（ADB）身份**起一个小进程（**不需要 root**）。
- 这个进程打开触摸屏的 `/dev/input/eventX` 节点，**只读**。内核仍然把同样的事件原封不动交给 App 和 SystemUI。
- 一个很小的状态机在里面识别「三指双击」，识别到了就通知 App。
- 无障碍服务再通过 Android 官方的 `MagnificationController` 开关放大。

**没有任何事件被注入、消费、拦截或缓冲。** Shizuku 在这里纯粹是一个只读传感器。放大本身仍然需要无障碍服务，这是系统要求。

代价：未 root 的设备**每次重启后都要重新启动一次 Shizuku**（这是 Android 系统限制，见 [Shizuku 官方说明](https://shizuku.rikka.app/guide/setup/)）。

---

## 运行要求

- Android **11（API 30）** 及以上，带真实触摸屏
- [Shizuku](https://shizuku.rikka.app/) **已安装并且服务正在运行**（v11.2.0+，已在 13.6.0 上验证）
- 为本 App 开启无障碍服务
- 体积约 100 KB，无需账号、无需登录

---

## 安装与设置（一步一步来）

### 第 1 步：安装 APK

从 [Releases v1.0](https://github.com/BlastVic/three-finger-magnification/releases/tag/v1.0) 下载 `three-finger-magnification-v1.0.apk` 并安装。

> 如果之前装过用其他密钥签名的测试版，会提示签名冲突，请先卸载旧版本。

### 第 2 步：安装并启动 Shizuku

从[官网](https://shizuku.rikka.app/)或 [GitHub Releases](https://github.com/RikkaApps/Shizuku/releases) 安装 Shizuku，然后用下面**任意一种**方式启动服务：

**A. 已 root** —— 打开 Shizuku，点「通过 root 启动」即可。

**B. 未 root、Android 11 及以上（推荐，不用电脑）**

1. 开启「开发者选项」和「USB 调试」，进入「无线调试」并打开开关。
2. 在 Shizuku 里点「配对」；同时在「无线调试」里点「使用配对码配对设备」。
3. 在 Shizuku 弹出的通知里输入 6 位配对码（MIUI 若立刻失败，请把通知样式改成「Android」）。
4. 回到 Shizuku 点「启动」。没反应就把无线调试关掉再打开。
5. 配对只需一次，但**每次重启手机后都要重新点一次「启动」**。

**C. 未 root、有电脑（Android 10 及以下，或任何版本）**

开启 USB 调试并授权电脑后执行：

```bash
adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
```

这是 Shizuku v11.2.0+ 的官方命令。仓库里也提供了一个可直接运行的辅助脚本 [`tools/start-shizuku-adb.sh`](tools/start-shizuku-adb.sh)。同样，**每次重启后需要重新执行**。

**厂商适配提示**（来自 [Shizuku FAQ](https://shizuku.rikka.app/guide/setup/#faq)）：允许 Shizuku 后台运行；MIUI 还要打开「USB 调试（安全设置）」；ColorOS 关闭「权限监控」；Flyme 关闭「Flyme 支付保护」。如果 Shizuku 经常自己停掉，把 USB 模式改成「仅充电 / 不传输数据」，并保持开发者选项开启。

### 第 3 步：在 Shizuku 里给本 App 授权

打开「Triple-Tap Zoom」，会弹出 Shizuku 授权对话框，选择**始终允许**。可以在 Shizuku →「已授权应用」里确认。之前误点了拒绝的话，重开 App 或在 Shizuku 里重新授权。

### 第 4 步：开启无障碍服务

在 App 里点「启用无障碍服务」→ 在设置里找到 **Triple-Tap Zoom** → 打开，并允许它请求的触摸探索与放大能力。

也可以手动：**设置 → 无障碍 → 已安装的服务 / 下载的服务 → Triple-Tap Zoom → 开启**。

> 部分 ROM（尤其 vivo / OriginOS）在应用更新后会悄悄解绑无障碍服务。如果更新后手势失效，把这个开关关掉再打开一次。

### 第 5 步：关掉系统自带的「点按三次放大」

**设置 → 无障碍 → 放大 → 点按三次放大 → 关闭。**

否则打游戏时系统自带的手势还是会误触。App 里的「关闭系统三击放大」按钮可以直接跳到这个页面。

### 第 6 步：验证

回到 App，状态栏应显示：

```
● 服务运行中 · 零干扰（不接管触摸）
Shizuku: 监听中（原始触摸，只读）
```

然后**三根手指双击**。诊断日志里应出现 `★ 三指双击` 和 `→ 放大 2.0×`；再做一次出现 `→ 关闭放大`。

建议再点一次「后台 / 电池优化」，把本 App 和 Shizuku 都设为**无限制**，避免被系统的后台冻结机制杀掉监听进程。

---

## 怎么用

- 三根手指**同时按下并抬起**，再来一次；两次间隔约 **1.1 秒**以内。
- 手指不要滑动，每次按下要短（**600 ms** 以内）。三根手指不必绝对同时落下，轻微先后是允许的。
- **重复同一手势**即可取消放大。
- 放大状态下**双指拖动**平移画面，单指点击仍然正常。
- 倍数、放大中心、振动、「切换 App 保持放大」都可以在 App 里调。
- 下拉快捷设置里有**磁贴**，可以一键开关功能。
- **请保持「零干扰模式」开启。** 界面上保留的旧版「接管模式」只是给少数会回传事件副本的 ROM 做兜底，它会短暂缓冲触摸并可能造成幽灵长按，不建议日常使用。

---

## 常见问题

| 现象 | 排查 |
|---|---|
| 完全没反应 | Shizuku **是否正在运行**？App 状态应显示「监听中」。重启手机后要重新启动 Shizuku。 |
| 提示「服务未运行（请在 Shizuku 中启动）」 | 按第 2 步启动 Shizuku，再重开本 App。 |
| 提示「等待 Shizuku 授权 / 授权被拒绝」 | 在 Shizuku 里给本 App 授权，选择「始终允许」。 |
| 提示「监听器启动失败，code=2」 | 该 ROM 不允许 shell 读取触摸节点，检查 Shizuku 是否真的以 ADB/shell 权限运行。 |
| 日志有 `触摸#… 峰值=3`，但就是不放大 | 点得太慢、手指滑动，或两次间隔超过 1.1 秒。干脆利落地轻点。 |
| 三连点还是会触发系统放大 | 关掉「无障碍 → 放大 → 点按三次放大」（第 5 步）。 |
| 更新 App 后手势失效 | 重新开启无障碍服务；vivo 机型先从桌面启动一次 App 再开启。 |
| 状态偶尔闪过「正在连接 / UserService 断开」 | 重连是异步的，等几秒即可；以日志里的「监听中 / Listening」为准。 |
| 底部上滑、长按又变得奇怪 | 确认「零干扰模式」是开启的，切勿停留在接管模式。 |
| 浏览器 / 桌面正常，但**设置、微信、银行类 App 里失效** | 部分 ROM（尤其 Android 15+ 的 vivo / OriginOS）在「受保护应用」处于前台时，会**临时停用第三方无障碍服务**：`settings get secure accessibility_enabled` 变成 `0`，服务被解绑，`MagnificationController` 随之不可用。手势本身仍被识别（Shizuku 只读 evdev），只是 App 无法执行放大；离开这些界面约 6–10 秒后系统会自动重新绑定，无需手动重开。这是系统安全策略，不是 App 的 bug。若是侧载安装的 APK，还会叠加 Android 15 的「受限设置」限制，建议在 *设置 → 应用管理 → 三指双击放大 → 右上角菜单 → 解除限制 / 允许受限设置* 中解除（[vivo 官方说明](https://www.vivo.com.cn/service/questions/all?categoryId=170&questionId=1836)，可能需要验证锁屏密码或指纹）。 |

深入排查可以看 logcat，标签为 `ZoomTapLog`、`ZoomTapReader`、`ZoomTapBridge`。

---

## 实现原理（给开发者）

```
/dev/input/eventX  ──只读──>  Shizuku UserService（uid = shell）
                                 │  解析 24 字节 input_event（小端）
                                 │  ABS_MT_SLOT / TRACKING_ID / POSITION_X,Y
                                 │  逐指位移判定、峰值手指 >= 3
                                 │  短按 <= 600 ms、两次间隔 <= 1100 ms
                                 ▼
                   Binder 回调 + 显式广播（携带同一个唯一 id）
                                 ▼
                   TripleTapReceiver（按 id 去重）
                                 ▼
                   ZoomAccessibilityService
                     MagnificationController 设置倍数与中心
```

几个关键细节：

- 触摸节点通过 `getevent -p` 自动探测（取第一个暴露 `ABS_MT_TRACKING_ID` / `0039` 且有 X/Y 轴的设备）；`/dev/input/event5` 只是兜底。
- 每根手指只和**自己的按下坐标**比较（6% 容差）。早期实现拿"第一个活跃 slot"做基准，一旦某根手指抬起就会误判成大幅移动。
- 被抖动判失败的三指轮次**不会清掉**已经记下的第一次轻点，因为三根手指很难绝对同时落下。
- shell 侧通过**双通道**投递：Binder 回调 + 带 `FLAG_INCLUDE_STOPPED_PACKAGES` 的显式 `am broadcast`。厂商的后台冻结会杀掉 Binder，但广播会先唤醒 App 进程。两条通道带同一个 id，App 按 id 去重，而不是用时间冷却（冷却曾吞掉真实连击）。
- 无障碍配置请求 `canControlMagnification` 与触摸探索，但零干扰模式下从不接管触摸流。
- shell 进程读不到 App 资源，所以设备语言通过 `ITouchReader.start()` 传进去，保证 shell 日志与界面语言一致。

目录结构：

```
app/AndroidManifest.xml
app/aidl/          AIDL 接口契约（ITouchReader、ITouchCallback）
app/src/           Java 源码（手写的 Binder Stub/Proxy）
app/res/           values/（英文默认）+ values-zh/（中文）
app/libs/          Shizuku 13.1.5 类库（见 THIRD_PARTY_NOTICES.md）
tools/             可选的 adb 辅助脚本
build.sh           aapt2 -> javac -> d8 -> zipalign -> apksigner
```

---

## 从源码构建

不需要 Gradle，也不需要 Android Studio 工程。

**环境**：JDK 17、Android SDK platform `android-35`、build-tools `36.0.0`、bash（Windows 用 Git Bash）。SDK/JDK 不在默认位置时设置 `ANDROID_HOME` 和 `JAVA_HOME`；也可用 `BUILD_TOOLS_VERSION`、`ANDROID_PLATFORM` 覆盖版本。

```bash
git clone https://github.com/BlastVic/three-finger-magnification.git
cd three-finger-magnification
bash build.sh
# -> dist/three-finger-magnification-v1.0.apk（本地调试密钥，仅供自测）
```

用自己的密钥签名可发布版本：

```bash
SIGN_KEYSTORE=/path/to/your.keystore \
SIGN_STORE_PASS='密钥库密码' \
SIGN_KEY_ALIAS=别名 \
SIGN_KEY_PASS='密钥密码' \
bash build.sh
```

**千万不要把密钥库和密码提交进仓库。** 官方 Release 由 Vic Yang 的密钥签名；自己用其他密钥编译的 APK 无法覆盖安装 Release 版（Android 会拒绝签名不一致），需要先卸载。

`app/src` 里的 AIDL Stub 是手写的，因为 Windows 上 `aidl.exe` 对某些路径形式处理有误；`app/aidl` 保留接口定义作为说明。

---

## 隐私说明

App 只声明了 `VIBRATE` 和 Shizuku API 所需权限，**没有网络权限**，不读取屏幕内容，不收集任何数据，诊断日志只存在应用私有目录。原始触摸流只在 shell 进程的内存里解析，不落盘、不外发。

---

## 已知限制

- 未 root 设备每次重启都要重新启动 Shizuku。
- 在会保护敏感界面的 ROM 上（vivo / OriginOS、部分银行与支付类应用），当**系统设置、微信等应用处于前台**时，系统可能解绑第三方无障碍服务，这些界面里无法切换放大；离开后几秒自动恢复。侧载安装的 APK 还可能受 Android 15「受限设置」限制，若服务反复被停用，请在应用的系统设置里解除该限制。
- 限制较严的 ROM 可能禁止 shell 读取 `/dev/input`，或冻结 App 进程；后者已由广播兜底处理。
- 外接触摸屏、副屏、部分折叠屏可能对应不同的 evdev 节点，自动探测只取第一个匹配的设备。
- 放大用的是 Android 系统自身的全屏放大器，个别特殊渲染表面的显示效果可能不同。
- 部分 Android 15 版本切换 App 时会重置放大状态，App 的「保持放大」选项会自动恢复。
- 这是一个无障碍工具，不是通用的输入映射器：除三指双击外，它刻意不碰任何其他手势。

---

## 参与贡献

欢迎提 Issue 和 PR，尤其欢迎**设备兼容性反馈**（请附上机型、Android 版本、Shizuku 版本和相关 `ZoomTapLog` 日志）。如果这个 App 帮到了你，点个 Star 能让更多有同样困扰的人找到它。

## 致谢与许可证

- **作者**：Vic Yang
- [Shizuku](https://github.com/RikkaApps/Shizuku)，RikkaApps 出品，Apache-2.0，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
- Android Open Source Project 提供的放大与无障碍 API

本项目采用 [MIT 许可证](LICENSE)发布。

> 软件按「现状」提供，不作任何担保。它需要 Shizuku 与无障碍这两项本地高权限才能工作；在意权限的话，建议先看过源码再安装。

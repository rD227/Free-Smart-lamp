# 本地开灯

Android 手机直接通过 BLE 广播控制 LampSmart Pro 系列灯的实验性客户端
 支持配对、开关、色温、亮度和定时关灯，采用 GNU v3 许可证
 针对店家提供的 `com.jingyuan.lamp`，协议实现参考 [NicoIIT/ha-ble-adv](https://github.com/NicoIIT/ha-ble-adv)
 不同灯的固件可能使用不同协议变体，仍需到灯旁边验证

## 使用

1. 打开手机上的「质感智灯」，开启蓝牙，允许「附近设备」权限

2. 首次使用选择默认的「智灯 Pro（推荐）」模式

3. 用墙壁开关给灯断电、再上电，5 秒内点击「配对这盏灯」
 配对窗口可能因灯的型号不同而变化，店家的说明优先

4. 观察灯是否闪动两下，再点击「关灯」和「开灯」确认响应

5. 无效时尝试「自动」或模式 1、2、3；每次切换后重新给灯上电并配对

正常控制时墙壁开关需要保持通电
 配对是登记本 App 的控制器编号，不需要在系统蓝牙列表中连接灯

### 色温与定时

色温提供冷白、自然白、暖黄三个快捷按钮，点击即可发送。色温与亮度滑杆共用下方的三档调光方式，按「滑动发送｜松手应用｜按钮应用」排列，切换时选中底块会弹簧滑动，文字轻微拉伸回弹：

- **滑动发送**：边拖动边调节灯光，连续变化只保留最新待发送位置，松手后重复发送最终值。
- **松手应用**：拖动时只更新页面，松手后发送灯光指令。
- **按钮应用**：拖动时只更新页面，再点击「应用灯光」发送；这是默认方式。

只有「按钮应用」显示「应用灯光」按钮，所选方式会保存。键盘和无障碍调节也遵循所选方式。色温百分比表示暖光比例：0% 为冷白，100% 为暖黄；不使用未经灯具标定的开尔文数值。亮度范围为 1–100%。

定时提供 15、30、60 分钟，以及 1–720 分钟的自定义时长。选择时长后点击「设置」，点击「取消定时」可撤销。定时指令由灯具执行，完成发送后可以关闭应用；页面保存并显示预计关灯时间，重新打开仍可查看。预计时间根据发送时间计算，请以灯的实际反应为准。

开关、配对和普通调光发送时显示 Material 3 波浪进度条。滑动发送期间使用完整长度的连续波浪，不因每个广播重新填充；灯光发送期间仍可拖动滑杆。App 不修改手机屏幕亮度，也不阻止息屏，按系统设置运行。

### 配色

「个性化」默认开启「跟随系统配色」：支持动态配色的 Android 12 及以上手机使用系统 Material 3 颜色，其他设备使用内置蓝色主题。关闭该选项后可选择蓝、绿、紫、玫瑰、琥珀色包，也可以使用双环选择主题颜色：内圈选色相，外圈调节白度，中心实时预览，松手应用。红色加白可以得到粉色；白度设到 100% 时仍保留色相，调低白度可恢复。两个环都支持键盘与无障碍调节。开启系统配色时触碰色环，会提示先关闭系统配色。换色后在首帧绘制前恢复滚动位置，配色会保存，浅色和深色外观跟随系统。

「已发送」表示 Android 已启动广播并完成发送，不代表收到灯的确认
 App 不读取灯的状态
 广播过程中请保持 App 在前台

随机控制器编号、指令计数及兼容模式保存在 SharedPreferences。卸载或清除 App 数据后需要重新配对，安装更新保留数据。

协议来源及许可证见 [NOTICE.md](NOTICE.md) 和 [UPSTREAM-LICENSE](UPSTREAM-LICENSE)。

## Nvim 与命令行构建

不需要 Android Studio，也不需要系统安装 `gradle` 或 `kotlinc`。需要 JDK（本机使用 21）、Android SDK Platform 36、Build Tools 36.0.0，以及安装真机时使用的 ADB。

项目固定使用 Gradle 9.3.0、Android Gradle Plugin 9.0.1；AGP 自带 Kotlin 支持。首次构建会下载缺失的编译器、插件及依赖，以后可复用 `~/.gradle/` 中的缓存。

在项目根目录创建 `local.properties`，填写本机 SDK 路径，或者设置 `ANDROID_HOME`。`local.properties` 不提交到 Git。

```sh
cd ~/androidProject/android-lamp-control
./gradlew assembleDebug                # 生成已签名、已对齐的 APK
./gradlew assembleRelease              # 生成签名后的 R8 压缩版 APK
./gradlew :app:compileDebugKotlin       # 检查 Kotlin 编译，包括 Android API 引用
./gradlew testDebugUnitTest lintDebug   # 协议测试与 Android 静态检查
./gradlew installDebug                 # 安装到已连接的真机
adb shell am start -n org.openlamp.local/.MainActivity
```

最终 APK：`app/build/outputs/apk/debug/app-debug.apk`。也可以直接安装：

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

在 Nvim 中打开项目根目录后，可用 `:!./gradlew :app:compileDebugKotlin` 或 `:!./gradlew assembleDebug`。LSP 用于补全和即时诊断，实际编译结果以 Gradle 为准。本项目不修改你的 Nvim 配置。

保持 `.keys/debug.keystore`，覆盖安装就能保留已有的控制器编号。新检出且没有密钥时，构建会生成本地调试密钥；不同密钥的 APK 通常无法覆盖安装之前的版本。包名、SharedPreferences 名称和原有字段与旧版一致，当前版本号为 0.3.0 / versionCode 4。0.3.0 首次启动会选择新增的「智灯 Pro」模式，保留控制器编号；之后保留用户选择的模式。

### Release 版本

Release 开启 R8 代码精简、优化、混淆及资源精简，使用 Android 默认的优化规则；App 没有反射加载自身类的代码，因此不添加整包保留规则。Release 关闭调试，沿用 `.keys/debug.keystore` 中现有的本地密钥，便于覆盖安装之前的 debug 版并保留控制器编号。

```sh
./gradlew assembleRelease
./gradlew testDebugUnitTest lintRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

最终文件为 `app/build/outputs/apk/release/app-release.apk`，已签名，可直接安装。混淆映射保存在 `app/build/outputs/mapping/release/mapping.txt`，排查该版本的崩溃日志时应保存对应映射。

### 在 Windows 上编译

复制完整项目及隐藏目录 `.keys/`。签名文件为 `.keys/debug.keystore`，存储密码和密钥密码均为 `android`，密钥别名为 `androiddebugkey`。它被 Git 忽略，因此仅复制 Git 中的文件不会包含密钥。

Windows 需要安装 JDK 21、Android SDK Platform 36 和 Build Tools 36.0.0；不需要单独安装 Kotlin 或 Gradle。修改本机 `local.properties` 中的 `sdk.dir`，指向 Windows 的 SDK 目录，路径可使用正斜杠，例如：

```properties
sdk.dir=C:/Users/你的用户名/AppData/Local/Android/Sdk
```

在项目根目录打开 PowerShell：

```powershell
.\gradlew.bat assembleRelease
```

Windows 首次构建会下载所需的 Gradle 和依赖；Linux 的 `.gradle/`、`build/` 及 `app/build/` 不必复制。

## 项目结构

```text
app/src/main/java/org/openlamp/local/
    MainActivity.kt                 页面、控制器和 Android 生命周期的连接
    LampScreen.kt                   完整界面、交互、弹窗、色环与波浪进度
    LampTheme.kt                    ColorPack 枚举、ThemeSettings 和 M3 配色
    ThemeColor.kt                   自选色与白色混合、保留色相
    LightingUpdates.kt              调光方式枚举、待发送位置合并
    LampController.kt               蓝牙发送、设置保存与定时记录
    LampCommand.kt                  开关、配对、色温和定时指令
    LampCodec.kt                    四种协议编码
app/src/main/res/values/strings.xml        界面文字
app/src/main/res/values/styles.xml         Material 3 样式
app/src/main/res/values/colors.xml         默认浅色配色
app/src/main/res/values-night/colors.xml   默认深色配色
app/src/main/AndroidManifest.xml          权限与入口
app/src/test/                            协议回归测试
app/build.gradle.kts                     Android 构建配置
settings.gradle.kts                      模块与依赖仓库
```

界面使用 Material Components 的 Material 3 按钮、滑杆、卡片、选项、开关、弹窗和波浪进度条。完整页面放在 `LampScreen.kt`，通过 `LampScreenCallbacks` 接收操作、通过 `LampState` 更新显示；重写 UI 时可以替换这个文件，保留蓝牙和协议实现。`ColorPack` 枚举定义预设色包，`ThemeSettings.useSystemM3` 决定跟随系统还是使用自选主题。

`LampScreen.kt` 顶部的 `SEND_WAVE_SPEED_DP_PER_SECOND` 控制波浪移动速度（当前 15 dp/秒），`SEND_WAVELENGTH_DP` 控制波长（28 dp），`SEND_WAVE_AMPLITUDE_DP` 控制振幅（3 dp），两种波浪显示共用这些参数。填充时长来自控制器的实际发送批次：`LampController.kt` 的 `RELIABLE_HOLD_MILLIS` 为每包 500 毫秒，`PREVIEW_HOLD_MILLIS` 为滑动预览的每包 160 毫秒，包间隔 `PACKET_GAP_MILLIS` 为 20 毫秒。

滑杆拖动光圈绘制在轨道上方，保留不透明的滑块；`LIGHTING_HALO_MAX_ALPHA` 限制光圈最大不透明度为 0.5，并在开始、结束拖动时淡入淡出。

## 实现与验证

`LampController.kt` 使用 Android 原生 BLE 广播 API，将协议字节作为 16 位服务 UUID 列表发送。采用与绿色智灯 Pro 一致的可连接广播，包含 Flags 字段；26 字节协议报文加上 UUID 列表头和 Flags，正好占用传统广播的 31 字节。
 随机种子生成时排除重复 UUID，避免 Android 去重改变报文
 每个普通报文广播 500 毫秒，配对发送 8 次，其他指令发送 4 次；滑动预览每个报文广播 160 毫秒，每次发送一轮，松手后按普通指令重复发送。「自动」模式按智灯 Pro、模式 2、模式 3、模式 1 的顺序轮换。

| 界面模式 | 编码 |
| --- | --- |
| 模式 1 | LampSmart V1 |
| 模式 2 | LampSmart signed V3 |
| 模式 3 | LampSmart unsigned V2 |
| 智灯 Pro（推荐） | LampSmart signed V3/s2，报文前缀 `30 82 00` |

协议转换代码位于 `app/src/main/java/org/openlamp/local/LampCodec.kt`。开灯命令为 `0x10`，关灯为 `0x11`，配对为 `0x28`；色温与亮度使用 `0x21`，冷、暖通道分别为 0–255；定时使用 `0x41`，时长以分钟计，低字节在前，零表示取消。

0.3.0 修复了绿色「智灯 Pro」`com.jingyuan.lamp` 1.4.1 的兼容问题：此前三个模式均未覆盖它使用的 V3/s2 参数。新增模式以 `0x82` 参与签名，并选择对应的加密表，同时调整广播类型和重复发送方式。

2026-10-11 已在连接的 PLK110 手机上安装 0.3.0，保留原控制器编号。用户实灯验证确认：配对闪动两下，关灯和开灯均正常。该结果对应上述手机和灯具组合，其他型号仍需实测。

本次新增功能保持版本号 0.3.0。用户实灯确认冷白、暖黄色温切换正常；设置 1 分钟定时并退出应用后，灯正常自动关闭。Release 构建、协议测试和 Android Lint 检查通过；已在手机上检查色包、色环、深色外观、波浪进度、定时输入校验及设置在重新打开后的保留。

同日换至 OnePlus 7 Pro（GM1911，Android 16），保留原控制器编号；用户实灯确认「松手应用」及「滑动发送」的色温、亮度调节正常。新增测试覆盖连续变化合并、松手后的最终发送、取消队列，以及红色加白变为粉色、白色端点保留色相和旧主题色读取。

编码测试将原有三种模式的 324 组报文与上游逐字节比较，将智灯 Pro 模式的 276 组配对、开关报文和 76 组色温、定时报文与手机上原厂编码库的实际输出比较，全部一致。共 676 组，覆盖不同编号、计数回绕、冷暖通道组合、定时取消及分钟数跨字节；另检查亮度、色温和时长的边界及非法输入。原厂对照数据的生成方式见 [tests/README.md](tests/README.md)。

普通测试直接执行 `./gradlew testDebugUnitTest`，使用已保存的 676 组对照报文。若要重新生成上游对照报文，则需要 Python 3、OpenSSL 和指定版本的上游检出：

```sh
git clone https://github.com/NicoIIT/ha-ble-adv /tmp/openlamp-reference
git -C /tmp/openlamp-reference checkout 10fb6edac13930cc78a56811147aa8d262d49eb9
python3 tests/check_codec.py /tmp/openlamp-reference
./gradlew testDebugUnitTest
```

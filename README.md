# 本地开灯

Android 手机直接通过 BLE 广播控制 LampSmart Pro 系列灯的实验性客户端
 支持配对、开灯、关灯，采用 GNU v3 许可证
 针对店家提供的 `com.jingyuan.lamp`，协议实现参考 [NicoIIT/ha-ble-adv](https://github.com/NicoIIT/ha-ble-adv)
 不同灯的固件可能使用不同协议变体，仍需到灯旁边验证

## 使用

1. 打开手机上的「本地开灯」，开启蓝牙，允许「附近设备」权限

2. 首次使用选择默认的「智灯 Pro（推荐）」模式

3. 用墙壁开关给灯断电、再上电，5 秒内点击「配对这盏灯」
 配对窗口可能因灯的型号不同而变化，店家的说明优先

4. 观察灯是否闪动两下，再点击「关灯」和「开灯」确认响应

5. 无效时尝试「自动」或模式 1、2、3；每次切换后重新给灯上电并配对

正常控制时墙壁开关需要保持通电
 配对是登记本 App 的控制器编号，不需要在系统蓝牙列表中连接灯

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
    MainActivity.kt                 蓝牙发送和交互逻辑
    LampCodec.kt                    四种协议编码
app/src/main/res/layout/activity_main.xml  界面布局
app/src/main/res/values/strings.xml        界面文字
app/src/main/res/values/styles.xml         配色和样式
app/src/main/AndroidManifest.xml          权限与入口
app/src/test/                            协议回归测试
app/build.gradle.kts                     Android 构建配置
settings.gradle.kts                      模块与依赖仓库
```

此次迁移保留配对、开灯和关灯功能；亮度、色温控制尚未加入。使用 Android 原生控件，不依赖 Compose 或第三方界面库。

## 实现与验证

`MainActivity.kt` 使用 Android 原生 BLE 广播 API，将协议字节作为 16 位服务 UUID 列表发送。采用与绿色智灯 Pro 一致的可连接广播，包含 Flags 字段；26 字节协议报文加上 UUID 列表头和 Flags，正好占用传统广播的 31 字节。
 随机种子生成时排除重复 UUID，避免 Android 去重改变报文
 每个报文广播 500 毫秒，配对发送 8 次，开关指令发送 4 次。「自动」模式按智灯 Pro、模式 2、模式 3、模式 1 的顺序轮换。

| 界面模式 | 编码 |
| --- | --- |
| 模式 1 | LampSmart V1 |
| 模式 2 | LampSmart signed V3 |
| 模式 3 | LampSmart unsigned V2 |
| 智灯 Pro（推荐） | LampSmart signed V3/s2，报文前缀 `30 82 00` |

协议转换代码位于 `app/src/main/java/org/openlamp/local/LampCodec.kt`，开灯命令为 `0x10`，关灯为 `0x11`，配对为 `0x28`

0.3.0 修复了绿色「智灯 Pro」`com.jingyuan.lamp` 1.4.1 的兼容问题：此前三个模式均未覆盖它使用的 V3/s2 参数。新增模式以 `0x82` 参与签名，并选择对应的加密表，同时调整广播类型和重复发送方式。

2026-10-11 已在连接的 PLK110 手机上安装 0.3.0，保留原控制器编号。用户实灯验证确认：配对闪动两下，关灯和开灯均正常。该结果对应上述手机和灯具组合，其他型号仍需实测。

0.3.0 已通过 Release APK 构建、协议测试和 Android Lint 检查。编码测试将原有三种模式的 324 组报文与上游逐字节比较，并将新增模式的 276 组报文与手机上原厂编码库的实际输出比较，全部一致；覆盖配对、开关、不同编号和计数回绕，另检查非法输入是否被拒绝。原厂对照数据的生成方式见 [tests/README.md](tests/README.md)。

普通测试直接执行 `./gradlew testDebugUnitTest`，使用已保存的 600 组对照报文。若要重新生成上游对照报文，则需要 Python 3、OpenSSL 和指定版本的上游检出：

```sh
git clone https://github.com/NicoIIT/ha-ble-adv /tmp/openlamp-reference
git -C /tmp/openlamp-reference checkout 10fb6edac13930cc78a56811147aa8d262d49eb9
python3 tests/check_codec.py /tmp/openlamp-reference
./gradlew testDebugUnitTest
```

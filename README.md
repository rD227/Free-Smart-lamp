# 本地开灯

Android 手机直接通过 BLE 广播控制 LampSmart Pro 系列灯的实验性客户端 
 支持配对、开灯、关灯，采用 GNU v3 许可证 
 针对店家提供的 `com.jingyuan.lamp`，协议实现参考 [NicoIIT/ha-ble-adv](https://github.com/NicoIIT/ha-ble-adv) 
 不同灯的固件可能使用不同协议变体，仍需到灯旁边验证

## 使用

1. 打开手机上的「本地开灯」，开启蓝牙，允许「附近设备」权限 
 
2. 首次使用先选默认的「自动」兼容模式 
 
3. 用墙壁开关给灯断电、再上电，5 秒内点击「配对这盏灯」 
 配对窗口可能因灯的型号不同而变化，店家的说明优先 
 
4. 观察灯是否闪烁，再点击「关灯」和「开灯」确认响应 
 
5. 自动模式无效时，分别选择模式 1、2、3；每次切换后重新给灯上电并配对 
 

只需手机和灯，不需要 STM32、Linux 电脑、原厂 App 或 Android root 
 正常控制时墙壁开关需要保持通电 
 配对是登记本 App 的控制器编号，不需要在系统蓝牙列表中连接灯 
 

「已发送」表示 Android 已启动广播并完成发送，不代表收到灯的确认 
 App 不读取灯的状态 
 广播过程中请保持 App 在前台 
 

## 隐私与本机数据

- Manifest 没有 INTERNET 权限；没有账号、服务器、广告或统计 SDK 
 
- 只申请蓝牙相关权限，不申请蓝牙扫描、定位、存储、摄像头或麦克风权限 
 
- 随机控制器编号、指令计数及兼容模式保存在本机 SharedPreferences，关闭系统备份 
 
- 卸载或清除 App 数据会删除控制器编号，之后需要重新配对 
 安装更新保留数据 
 
- 不包含原厂 APK 的代码或原生库 
 协议来源及许可证见 [NOTICE.md](NOTICE.md) 和 [UPSTREAM-LICENSE](UPSTREAM-LICENSE) 
 

## Nvim 与命令行构建

不需要 Android Studio，也不需要系统安装 `gradle` 或 `kotlinc`。需要 JDK（本机使用 21）、Android SDK Platform 36、Build Tools 36.0.0，以及安装真机时使用的 ADB。

项目固定使用 Gradle 9.3.0、Android Gradle Plugin 9.0.1；AGP 自带 Kotlin 支持。首次构建会下载缺失的编译器、插件及依赖，以后可复用 `~/.gradle/` 中的缓存。App 本身仍无联网权限。

本机的 `local.properties` 已配置 `sdk.dir=/opt/android-sdk`。其他电脑在项目根目录创建该文件，填写自己的 SDK 路径，或者设置 `ANDROID_HOME`。`local.properties` 不提交到 Git。

```sh
cd ~/androidProject/android-lamp-control
./gradlew assembleDebug                # 生成已签名、已对齐的 APK
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

保持 `.keys/debug.keystore`，覆盖安装就能保留已有的控制器编号。新检出且没有密钥时，构建会生成本地调试密钥；不同密钥的 APK 无法覆盖安装之前的版本。包名、SharedPreferences 名称和字段与旧版一致，版本号为 0.2.0 / versionCode 2。

## 项目结构

```text
app/src/main/java/org/openlamp/local/
    MainActivity.kt                 蓝牙发送和交互逻辑
    LampCodec.kt                    三种协议编码
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

`MainActivity.kt` 使用 Android 原生 BLE 广播 API，将协议字节作为 16 位服务 UUID 列表发送
 随机种子生成时排除重复 UUID，避免 Android 去重改变报文 
 默认自动模式依次发送三个协议变体，各广播 650 毫秒 
 

| 界面模式 | 编码 |
| --- | --- |
| 模式 1 | LampSmart V1 |
| 模式 2 | LampSmart signed V3 |
| 模式 3 | LampSmart unsigned V2 |

协议转换代码位于 `app/src/main/java/org/openlamp/local/LampCodec.kt`，开灯命令为 `0x10`，关灯为 `0x11`，配对为 `0x28`
 

旧版 0.1.0 已在 Android 16 真机上安装运行并完成开灯广播。此次 Kotlin 迁移版本在电脑端检查构建、签名和协议编码；手机当前没有连接，新版仍需安装后确认界面和广播。灯具不在用户身边，实际配对和开关灯尚待验证。

Kotlin 版已通过 APK 构建、签名与对齐检查、协议测试和 Android Lint 检查。编码测试将三种模式、三种命令、不同编号、计数和种子的 324 个组合与上游生成的报文逐字节比较，全部一致，另检查非法输入是否被拒绝。依赖下载完成后，本机已验证可用 `--offline` 构建和测试。
 

普通测试直接执行 `./gradlew testDebugUnitTest`，测试使用已保存的 324 组上游报文，不依赖原厂 App 或上游仓库。若要重新生成对照报文，则需要 Python 3、OpenSSL 和指定版本的上游检出：

```sh
git clone https://github.com/NicoIIT/ha-ble-adv /tmp/openlamp-reference
git -C /tmp/openlamp-reference checkout 10fb6edac13930cc78a56811147aa8d262d49eb9
python3 tests/check_codec.py /tmp/openlamp-reference
./gradlew testDebugUnitTest
```

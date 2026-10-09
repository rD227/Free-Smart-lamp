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
 

## 构建与安装

要求 Python 3、JDK（本机使用 21）、Android SDK Platform 36 和 Build Tools 36.0.0 
 Android 最低版本为 8.0，手机还需支持 BLE 广播 
 构建使用已安装的 SDK，不使用 Gradle，不下载依赖 
 

在 Android 项目目录执行：

```sh
cd ~/androidProject/android-lamp-control
python3 build.py
adb install -r build/openlamp-local.apk
adb shell am start -n org.openlamp.local/.MainActivity
```

SDK 默认目录为 `/opt/android-sdk`，其他位置可通过 `OPENLAMP_SDK` 环境变量指定 
 脚本在 `.keys/` 创建本地调试签名密钥；保留该文件即可继续覆盖安装 
 生成的 APK 位于 `build/openlamp-local.apk` 
 

## 实现与验证

`MainActivity.java` 使用 Android 原生 BLE 广播 API，将协议字节作为 16 位服务 UUID 列表发送 
 随机种子生成时排除重复 UUID，避免 Android 去重改变报文 
 默认自动模式依次发送三个协议变体，各广播 650 毫秒 
 

| 界面模式 | 编码 |
| --- | --- |
| 模式 1 | LampSmart V1 |
| 模式 2 | LampSmart signed V3 |
| 模式 3 | LampSmart unsigned V2 |

协议转换代码位于 `src/org/openlamp/local/LampCodec.java`，开灯命令为 `0x10`，关灯为 `0x11`，配对为 `0x28` 
 

已验证 APK 构建及签名、在连接的 Android 16 手机上安装运行，以及开灯广播成功完成 
 编码测试将三种模式、三种命令、不同编号、计数和种子的 324 个组合与上游编码器逐字节比较，全部通过 
 尚未在用户的灯旁边验证配对和实际开关灯 
 

重新运行编码比较需要独立的上游检出和 OpenSSL：

```sh
git clone https://github.com/NicoIIT/ha-ble-adv /tmp/openlamp-reference
git -C /tmp/openlamp-reference checkout 10fb6edac13930cc78a56811147aa8d262d49eb9
python3 tests/check_codec.py /tmp/openlamp-reference
```

# 协议对照数据

`app/src/test/resources/upstream-vectors.txt` 是固定上游版本生成的 324 组报文，由 `check_codec.py` 重新生成。

`app/src/test/resources/jingyuan-v141-vectors.txt` 是绿色智灯 Pro（`com.jingyuan.lamp`，versionName 1.4.1，versionCode 41）的 ARM64 `libencodeV3.so` 在真实 Android 手机上生成的 276 组报文。每行格式为：

```text
mode command controllerId sequence seed payloadHex
```

生成时使用设备类型 `0x0100`、分组 0，覆盖四个控制器编号、配对/开灯/关灯命令以及超过 256 次调用后的计数回绕。该库的初始计数为 `0x10`，此次调用产生的种子为 11091。

来源文件 SHA-256：

```text
base.apk       d225c156c3761ea956db0e7adcc2a1d18c2d2795bdbfbc38eb750262ef0d8277
libencodeV3.so 73cef4db2eb6168d0ffafb4b1845259753b197b15382f926df7ceed9cb2e27a7
```

`VendorVectors.java` 是用于生成这组数据的诊断工具。它通过 `DexClassLoader` 加载本机已有 APK，反射调用 `ToolV3.blevbBinding` 和 `ToolV3.blevbLampOnOff`，只生成字节，不启动蓝牙广播。原厂 APK 和原生库不作为项目文件或应用资产分发。

重新生成时，将此 Java 文件用 Android SDK 的 `android.jar` 编译，再用 Build Tools 的 D8 转为 DEX。将生成的 DEX 和从对应 APK 解出的 ARM64 `libencodeV3.so` 放到手机 `/data/local/tmp/openlamp-protocol/`，然后执行：

```sh
adb shell pm path com.jingyuan.lamp
adb shell 'CLASSPATH=/data/local/tmp/openlamp-protocol/runner.dex app_process /system/bin VendorVectors <上一步返回的APK绝对路径> /data/local/tmp/openlamp-protocol'
```

标准输出为 276 行对照数据。使用新的独立进程，确保原生库计数从初始值开始。仅在确认 APK 版本、哈希和调用参数一致后更新测试文件。

`jingyuan-controls-v141-vectors.txt` 另保存了同一编码库输出的 76 组色温和定时关灯报文。色温覆盖冷、暖通道的 0、127、255 组合；定时覆盖取消、1 分钟、常用时长、255/256 分钟边界及 65535 分钟上限，均覆盖四个控制器编号。

生成这组数据时，在上述 `VendorVectors` 命令的末尾添加 `controls`。每行格式为 `mode command controllerId sequence seed param arg0 arg1 arg2 payloadHex`。诊断工具只调用编码函数，不操作灯具。

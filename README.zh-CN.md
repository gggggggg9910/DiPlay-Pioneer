# DiPlay-Pioneer

[English](README.md) · [简体中文](README.zh-CN.md)

**[DiPlay](https://github.com/shihabal3amri/DiPlay)（Android 车机有线/无线 CarPlay）在先锋（Pioneer）代工丰田原厂车机平台上的适配版。**

基于上游 DiPlay **v0.2.22**（GPL-3.0）分叉。上游面向比亚迪 DiLink 车机；本分支承载丰田系先锋平台车机的适配工作，已在**第八代凯美瑞** A 屏导航版车机上实车验证。平台适配详情见
[docs/PIONEER_HEADUNITS.md](docs/PIONEER_HEADUNITS.md)。

> 下文未覆盖的通用功能请参考上游文档：[v0.2.22 上游 README](docs/UPSTREAM-README-DiPlay-0.2.22.zh-CN.md)、[安装](docs/INSTALL.md)、[兼容性](docs/COMPATIBILITY.md)。

## 已验证硬件

| | |
|---|---|
| 车型 | 第八代凯美瑞（XV70）A 屏导航版 |
| 车机 | 先锋代工 OEM（`jp.pioneer.ceam.*` 平台），固件 `2.0.0-rfp-rc6` |
| SoC | NXP i.MX6 DualLite，4× Cortex-A9 |
| 系统 | Android 7.1.2（API 25） |
| 屏幕 | 1280×720 |
| 视频解码 | Freescale VPU：H.264 最高 1080p，**无 HEVC 硬解** |

其他搭载先锋平台的丰田车型（亚洲龙、RAV4 等）未经验证，但本分支的适配是面向先锋平台而非凯美瑞车型编写的。

## 本分支的主要改动

- **音频路由**：有界音源切换 + 音频焦点重申（`PioneerMediaRoute`）、媒体音源选择（`PioneerMediaSource`）、蓝牙/USB 音源切换（`PioneerBluetoothRoute`、`PioneerUsbRoute`）、后排音区与 OEM 功放行为、401 媒体槽激活。
- **麦克风仲裁**：会话级麦克风角色持有，防止 OEM 语音助手抢占（`PioneerMicrophoneRole`、`PioneerRecordFocus`）、上行等待录音就绪、首选输入设备选择、2 声道/48 kHz 音轨整形与语音间隙功放保持。
- **USB 传输**：bulk 传输改用直接缓冲（`UsbBulkTransfer`，弱 CPU 上避免 GC 卡顿）、NTB16 出站批处理（2048 字节上限，`Ntb16Codec`）。
- **用户态 IPv6 协议栈**：lwIP 2.2.1 进程内编译（`UserspaceLwip*`、`shared/src/main/cpp/`），应对固件对内核网络路径不友好的车机。
- **视频**：FFmpeg 7.1.1 纯解码回退（H.264），面向无 HEVC 硬解的单元（`FfmpegVideoDecoder`、`HevcSupport`）、20/25 fps 渲染选项。
- **操控**：有线 seek/语音/MODE 方控键（`WheelKeyService`、`CarPlayMediaKeys`）、USB 自动确认服务。
- **调试**：可选的带外调试桥（`DebugBridge`），用于丢弃入站 TCP/ICMP 的固件；仅在自行创建标记文件时启用。

完整差异即上游 `v0.2.22` 之上的那一个提交。

## 安装说明

部分 OEM 车机的安装白名单按包名校验。本分支的 `applicationId` 为 `jp.pioneer.ceam.test`（已验证车机白名单中未被占用的标识）。若你的车机不同，请在构建前修改
`mobile/build.gradle.kts` 中的 `applicationId` 为你的车机允许的包名。

## 构建

与上游相同（`./gradlew :mobile:assembleDebug`），另需：

- NDK **27.2.12479018** 与 CMake（本分支原生层为 CMake 构建；上游 0.2.22 用 ndk-build）。
- FFmpeg 静态解码库已预编译置于 `shared/src/main/cpp/prebuilt/`（FFmpeg 7.1.1，仅 H.264
  解码的精简配置——LGPL-2.1+，见 [docs/THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md)）。
  可用 `scripts/build-ffmpeg-android.sh` 自行重建。

## 状态与免责声明

- 已在一台凯美瑞车机上经 `perf-35…47` 迭代实车验证；其他车机、固件或 iOS 版本上的行为不做保证。
- 沿用上游声明：本项目**未经 Apple MFi 认证**，捆绑来自公开 Carlinkit 固件的实验性配件身份，同样的合规不确定性在此适用。
- 请勿在驾驶中操作或配置本软件。

## 许可证

GPL-3.0，继承自 [xcertplay](https://github.com/shilapi/xcertplay) 与
[DiPlay](https://github.com/shihabal3amri/DiPlay)。见 [LICENSE](LICENSE) 与
[docs/THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md)。上游 v0.2.22 的 README 保留于
[docs/UPSTREAM-README-DiPlay-0.2.22.md](docs/UPSTREAM-README-DiPlay-0.2.22.md)。

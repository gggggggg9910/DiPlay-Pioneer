# Pioneer-based Toyota head units

Platform notes for the DiPlay-Pioneer fork. This fork targets the Pioneer-manufactured Toyota
OEM head-unit platform; every claim below was verified on one head unit:

| | Verified unit |
|---|---|
| Vehicle | Toyota Camry XV70 (8th gen), A-screen navigation trim |
| Platform | Pioneer OEM (`jp.pioneer.ceam.*` preinstalled software family) |
| Firmware | `2.0.0-rfp-rc6` |
| SoC | NXP i.MX6 DualLite (SABRESD-MX6DQ board family), 4× Cortex-A9 |
| Memory | ~3.8 GB physical, ~2.1 GB usable |
| Display | 1280×720 |
| GPU | Vivante — weak; frame pacing must avoid GPU-bound paths |
| Video decode | Freescale VPU: H.264 up to 1080p, no HEVC |
| Android | 7.1.2 (API 25), release-keys |
| Connectivity for CarPlay | Wired USB (iPhone occupies the only high-power port) |

Other Toyota models using Pioneer-made units should behave similarly, but nothing below is
verified beyond the listed unit.

## Platform constraints that shaped the fork

1. **Install allow-list.** The unit rejects APK installs for any package name not on an internal
   allow-list (`INSTALL_FAILED_VERIFICATION_FAILURE`). This fork therefore sets `applicationId`
   to `jp.pioneer.ceam.test`, an allow-listed identifier that ships unused on the verified unit.
   If your unit has a different allow-list, change `applicationId` in `mobile/build.gradle.kts`
   to a name your unit accepts before building.
2. **No HEVC hardware decode.** The VPU decodes H.264 only, so `HevcSupport` disables HEVC paths
   and `FfmpegVideoDecoder` adds an FFmpeg 7.1.1 software H.264 fallback
   (decode-only trim, see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and
   `scripts/build-ffmpeg-android.sh`). Frame-rate options 20/25 fps exist for headroom on the
   Cortex-A9.
3. **Single USB port.** While the iPhone is connected it is the only USB device, so adb-over-USB
   is unavailable exactly when you need it. `DebugBridge` provides an opt-in outbound TCP relay
   to local adbd for diagnostics during CarPlay sessions; it is off unless you create its marker
   file (`diplay_debugbridge.txt` in the app's external files directory, containing `host:port`).
4. **Hostile network firmware.** The unit drops inbound TCP/ICMP on `wlan0`, and the NCM/IPv6
   path the iPhone exposes needs a userspace termination. This fork compiles **lwIP 2.2.1**
   in-process (`UserspaceLwip`, `UserspaceLwipEngine`, `shared/src/main/cpp/`) and bridges it to
   the USB NCM device (`Ipv6NcmBridge`, `NcmUsbBridge`).
5. **OEM audio stack interplay.** The Pioneer audio service owns zones/amplifiers and runs its
   own assistant recorder. The adaptation layer negotiates with it rather than fighting it:
   see below.

## Adaptation layer map

| Area | Files |
|---|---|
| Media routing & focus | `PioneerMediaRoute`, `PioneerMediaSource`, `PioneerBluetoothRoute`, `PioneerUsbRoute`, `AudioWatchdog`, `EnforcedAudioChannel` |
| Microphone arbitration | `PioneerMicrophoneRole`, `PioneerRecordFocus`, `MicrophoneUplink` (recording-ready wait, preferred input device), `MicrophoneCaptureStats` (OEM-assistant visibility) |
| Speech path | `MicrophonePacketizer` (24 kHz voice uplink), OEM401 2ch/48 kHz track shaping, amplifier held across speech gaps |
| USB transport | `UsbBulkTransfer` (USBDEVFS_BULK on direct buffers), `Ntb16Codec` (batched NTB16, 2048 B cap), `IphoneUsbHost` |
| Userspace networking | `UserspaceLwip(.java/.kt)`, `UserspaceLwipEngine`, `UserspaceLink`, `Ipv6Datagram`, `OrderedPacketSender`, `HybridWifi`, `shared/src/main/cpp/` (lwIP 2.2.1, JNI glue) |
| Video | `FfmpegNative`, `FfmpegVideoDecoder`, `VideoDecoderEngine`, `VideoDecodeQueue`, `VideoRenderObserver`, `HevcSupport`, `diagnostics/VideoPipelineTrace` + `FrameTimeline` + `StallBuffer` |
| Controls | `WheelKeyService` (wired seek/voice/MODE), `CarPlayMediaKeys`, `CarPlayMediaButton`, `UsbAutoConfirmService` |
| Debugging | `DebugBridge`, `AsyncDiagnosticLog`, `CodecFrameTrace`, `AudioChannelProbe`, `TouchLatencyProbe` |

## Behaviour notes

- **Boot**: DiPlay is not launched at boot on this platform; the head unit opens it when the
  driver does. The upstream auto-start-on-boot behaviour is intentionally not used.
- **Media keys**: the Pioneer unit exposes seek/voice/MODE through its own wheel-key service;
  `WheelKeyService` bridges those into CarPlay media events.
- **Source switching** is bounded and re-asserts audio focus, because unbounded switches made the
  OEM mixer stick on the rear zone.

## Versioning

This fork versions as `<upstream-version>-pioneer.<n>` (e.g. `0.2.22-pioneer.1`) and keeps
`versionCode` ahead of any previously installed internal builds so upgrade installs work.

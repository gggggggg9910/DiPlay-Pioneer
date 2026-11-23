# DiPlay-Pioneer

[English](README.md) · [简体中文](README.zh-CN.md)

**[DiPlay](https://github.com/shihabal3amri/DiPlay) — wired & wireless CarPlay for Android head units — adapted for Pioneer-made Toyota OEM head units.**

Forked from DiPlay **v0.2.22** (GPL-3.0). Upstream targets BYD DiLink units; this fork carries the
adaptation work for the Pioneer platform found in Toyota vehicles, field-tested on an
**8th-generation Toyota Camry** A-screen navigation unit. See
[docs/PIONEER_HEADUNITS.md](docs/PIONEER_HEADUNITS.md) for the full platform write-up.

> Upstream documentation still applies to everything not listed below — start with the
> [upstream README for v0.2.22](docs/UPSTREAM-README-DiPlay-0.2.22.md), [Install](docs/INSTALL.md)
> and [Compatibility](docs/COMPATIBILITY.md).

## Verified hardware

| | |
|---|---|
| Vehicle | Toyota Camry XV70 (8th gen), A-screen navigation trim |
| Head unit | Pioneer OEM (`jp.pioneer.ceam.*` platform), firmware `2.0.0-rfp-rc6` |
| SoC | NXP i.MX6 DualLite, 4× Cortex-A9 |
| Android | 7.1.2 (API 25) |
| Display | 1280×720 |
| Video decode | Freescale VPU: H.264 up to 1080p, **no HEVC hardware decode** |

Other Pioneer-based Toyota units (Avalon, RAV4, …) are unverified but the adaptation is written
against the Pioneer platform rather than the Camry specifically.

## What this fork changes

- **Audio routing**: bounded source switching + audio-focus re-assertion
  (`PioneerMediaRoute`), media source selection (`PioneerMediaSource`), Bluetooth/USB source
  switching (`PioneerBluetoothRoute`, `PioneerUsbRoute`), OEM amplifier behaviour on the rear
  zone, 401 media-slot activation.
- **Microphone arbitration**: session-level microphone-role hold so OEM assistants cannot steal
  the input mid-session (`PioneerMicrophoneRole`, `PioneerRecordFocus`), uplink recording-ready
  wait, preferred-input-device selection, 2-channel/48 kHz track shaping with amplifier routing
  held through speech gaps.
- **USB transport**: bulk transfers on direct buffers (`UsbBulkTransfer`, avoids GC pauses on the
  weak CPU), NTB16 outbound batching with a 2048-byte cap (`Ntb16Codec`).
- **Userspace IPv6 stack**: lwIP 2.2.1 compiled in-process (`UserspaceLwip*`,
  `shared/src/main/cpp/`) for head units whose firmware is hostile to the kernel network path.
- **Video**: FFmpeg 7.1.1 decode-only fallback (H.264) for units without HEVC hardware decode
  (`FfmpegVideoDecoder`, `HevcSupport`), 20/25 fps rendering options.
- **Controls**: wired seek/voice/MODE steering-wheel keys (`WheelKeyService`,
  `CarPlayMediaKeys`), USB auto-confirm service.
- **Debugging**: opt-in out-of-band debug bridge (`DebugBridge`) for firmware that drops inbound
  TCP/ICMP; enabled only by a marker file you create.

The full list is the diff of one commit on top of upstream `v0.2.22`.

## Install note

Some OEM head units enforce an install allow-list keyed by package name. This fork sets
`applicationId` to `jp.pioneer.ceam.test`, an allow-listed identifier that ships unused on the
verified unit. If your unit differs, change `applicationId` in `mobile/build.gradle.kts` to a
package name your unit permits before building.

## Build

Same as upstream (`./gradlew :mobile:assembleDebug`), plus:

- NDK **27.2.12479018** and CMake (the native build is CMake-based in this fork; upstream 0.2.22
  uses ndk-build).
- FFmpeg static decode libraries are included prebuilt under `shared/src/main/cpp/prebuilt/`
  (FFmpeg 7.1.1, configured to an H.264-decode-only trim — LGPL-2.1+, see
  [docs/THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md)). Rebuild them for your own ABIs
  with `scripts/build-ffmpeg-android.sh`.

## Status and disclaimers

- Field-tested on one Camry unit across the `perf-35…47` iteration line; behaviour on other
  units, firmware versions or iOS versions is not guaranteed.
- As upstream states: this is **not an Apple-certified product** and bundles an experimental
  accessory identity recovered from public Carlinkit firmware. The same unresolved MFi questions
  apply here.
- Do not operate or configure this software while driving.

## License

GPL-3.0, inherited from [xcertplay](https://github.com/shilapi/xcertplay) and
[DiPlay](https://github.com/shihabal3amri/DiPlay). See [LICENSE](LICENSE) and
[docs/THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md). Upstream's README for v0.2.22 is
preserved in [docs/UPSTREAM-README-DiPlay-0.2.22.md](docs/UPSTREAM-README-DiPlay-0.2.22.md).

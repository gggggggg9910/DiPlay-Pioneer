# Credits and license notices

## Receiver

DiPlay is a modified version of [xcertplay by shilapi](https://github.com/shilapi/xcertplay). The upstream receiver is licensed under GNU GPL version 3; the full text is in `LICENSE` and the original README is retained in `docs/UPSTREAM-README.md`.

DiPlay-Pioneer is in turn a fork of [DiPlay v0.2.22](https://github.com/shihabal3amri/DiPlay) (GNU GPL version 3). Upstream's README for that release is preserved in `docs/UPSTREAM-README-DiPlay-0.2.22.md` (with Simplified Chinese and Traditional Chinese translations). All GPL-3.0 obligations, including attribution and source availability, pass through unchanged.

Upstream credits [LIVI](https://github.com/f-io/LIVI) and [Showcase](https://github.com/amineross/showcase) for protocol research. Existing source comments and attribution are preserved.

## Home and settings UI

`common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt` adapts the palette, visual arrangement and interface copy of the [DiAuto project](https://github.com/shihabal3amri/DiAuto). DiAuto's source is licensed under AGPL version 3. The UI file is marked AGPL-3.0-only; its license text is included in `docs/licenses/DiAuto-AGPL-3.0.txt`.

## CarPlay icon

The unmodified icon was obtained from Apple's developer site at:

https://developer.apple.com/assets/elements/icons/carplay/carplay-96x96_2x.png

CarPlay and the CarPlay icon are Apple Inc. marks/assets. This asset is not covered by the project's open-source code license. Its use here does not imply Apple approval or certification.

## Runtime dependencies

- AndroidX and Jetpack Compose — Android Open Source Project; Apache License 2.0.
- Kotlin standard library — JetBrains; Apache License 2.0.
- Bouncy Castle 1.79 — The Legion of the Bouncy Castle Inc.; Bouncy Castle license (MIT-style).
- JmDNS 3.6.3 — JmDNS contributors; Apache License 2.0.
- SLF4J — QOS.ch; MIT license.

Gradle dependency declarations and version catalog accompany the source. License files available in the resolved artifacts are included under `docs/licenses/dependencies/`.

## Native call echo cancellation

DiPlay vendors a subset of [SpeexDSP 1.2.1](https://github.com/xiph/speexdsp/tree/SpeexDSP-1.2.1) from Xiph.org Foundation and its contributors, including the acoustic echo canceller, preprocessor and FFT/filterbank support. It is compiled into `libspeex_echo.so` for the optional experimental **Call echo cancellation** feature.

SpeexDSP uses a BSD-style three-clause license. The full copyright notices, redistribution conditions and disclaimer are retained in [the vendored COPYING file](../shared/src/main/jni/speexdsp/COPYING). Component source headers retain their additional notices, including KISS FFT's Mark Borgerding attribution. The [vendoring notes](../shared/src/main/jni/speexdsp/README.md) identify the source version and build configuration.

## Software Opus microphone encoder

DiPlay vendors [Concentus](https://github.com/lostromb/concentus), Logan Stromberg's pure-Java port of the Opus reference library from Xiph.Org Foundation, Skype Limited, Microsoft Corporation and other contributors. It encodes the CarPlay microphone on head units whose Android has no MediaCodec Opus encoder.

Concentus uses the Opus BSD-style license. The full copyright notices, redistribution conditions and disclaimer are retained in [the vendored LICENSE file](../shared/src/main/java/org/concentus/LICENSE). The [vendoring notes](../shared/src/main/java/org/concentus/README.md) identify the source commit.

## Experimental authentication data

The public preview APK includes an accessory certificate/key pair recovered from public Carlinkit C2Air Allwinner V821 firmware during the owner's local investigation. These data are not newly generated Apple-issued credentials for DiPlay and are not relicensed as project source code. They are bundled in the preview APK to reproduce the offline experiment; continued acceptance and suitability for general distribution are unresolved. The source archive does not contain the private key, and the separate Android APK-signing key is never distributed.

## Download website

The static site layout, CSS and generator adapt DiAuto (AGPL-3.0). The AGPL license text is included with the source.

## BYD HUD maneuver icons

Required Notice: Copyright AndyShaman (https://github.com/AndyShaman/BYDMate)

The maneuver PNGs under `shared/src/main/assets/byd-hud-icons` were imported from BYDMate. Its PolyForm Noncommercial 1.0.0 terms and required notice are included alongside the assets. These files are separate from the project code license; upstream describes them as donor assets and their original provenance is not independently established. The validated DiLink5.1 windshield path uses factory turn codes rather than these images.

## FFmpeg (software H.264 fallback)

DiPlay-Pioneer vendors [FFmpeg 7.1.1](https://ffmpeg.org), compiled to static `libavcodec.a` +
`libavutil.a` per Android ABI and stored under `shared/src/main/cpp/prebuilt/<abi>/ffmpeg/`. It
backs `FfmpegNative`/`FfmpegVideoDecoder` — the software H.264 decode fallback for head units
without HEVC hardware decoding.

The libraries are built with a decode-only trim: `--disable-everything --enable-decoder=h264
--enable-parser=h264 --enable-parser=hevc`, no network, no GPL components (`--enable-gpl` is not
used), so they are distributed under the **GNU Lesser General Public License 2.1 or later**.
FFmpeg's general license information is at https://ffmpeg.org/legal.html. The exact build recipe
is in `scripts/build-ffmpeg-android.sh`; combined with the public source of this application, it
provides everything needed to relink modified FFmpeg versions as the LGPL requires. FFmpeg's
copyright belongs to the FFmpeg project and its contributors.

## lwIP (userspace IPv6 stack)

[lwIP 2.2.1](https://github.com/lwip-tcpip/lwip) (Lightweight IP) is vendored under
`shared/src/main/cpp/lwip` and compiled in-process into the userspace network stack used on head
units whose firmware interferes with kernel-level IPv6 handling. lwIP is licensed under a
BSD-style three-clause license; the full copyright notice, conditions and disclaimer are retained
in the vendored [COPYING file](../shared/src/main/cpp/lwip/COPYING).

## Conscrypt

The Gradle dependency `org.conscrypt:conscrypt-android` 2.5.3 (Apache License 2.0) provides the
TLS provider used on old-Android head units whose platform TLS stack is outdated. Its native
library `libconscrypt_jni.so` ships under `shared/src/main/cpp/prebuilt/` as part of that
dependency's own distribution.

# Third-party source notices

Lumen's project license remains Apache-2.0. The following adapted source retains its upstream attribution and license.

| Component | Adopted version | Upstream attribution | Local license |
|---|---|---|---|
| Acrylic backdrop color preservation and source invalidation | Bilibili Innocent Lab next: [e92737b4](https://github.com/jichuo1/Bilibili_Innocent_Lab/commit/e92737b4da832c7f4e93adacb7598eaaa159590b), [56600c9e](https://github.com/jichuo1/Bilibili_Innocent_Lab/commit/56600c9ef321f7a16050b941ef3d36146085c9d5), merged by [15a31d8f](https://github.com/jichuo1/Bilibili_Innocent_Lab/commit/15a31d8f63bbc5bf98845b03396e6b8f8bf9221b) | Copyright 2026 LiJiaHua1024; inclusion under Apache-2.0 with contributor permission confirmed by the project maintainer on 2026-10-16 | [Project Apache-2.0 license](LICENSE) |
| Viewport-source cropping and shared Drawable recording | Bilibili Innocent Lab next: [ba9e4e4e](https://github.com/jichuo1/Bilibili_Innocent_Lab/commit/ba9e4e4ef59a1d2a0d524df811eb84928e26f936), [fc840a5a](https://github.com/jichuo1/Bilibili_Innocent_Lab/commit/fc840a5aa6e9021c41dd7f24ab1ec68f2d873b90), [222e0ba9](https://github.com/jichuo1/Bilibili_Innocent_Lab/commit/222e0ba9f9f8a43c0c215b5d1025146509453b29) | Copyright 2026 LiJiaHua1024; inclusion under Apache-2.0 with contributor permission confirmed by the project maintainer on 2026-10-16 | [Project Apache-2.0 license](LICENSE) |
| Kyant AndroidLiquidGlass refraction shader | [65ab177e90e5c1d8c62e70cf7755841982da65f6](https://github.com/Kyant0/AndroidLiquidGlass/tree/65ab177e90e5c1d8c62e70cf7755841982da65f6) | Copyright 2025 Kyant; Apache-2.0 | [Kyant-AndroidLiquidGlass-LICENSE.txt](third_party/Kyant-AndroidLiquidGlass-LICENSE.txt) |
| AndroidLiquidGlassView shader stability and linear-sRGB saturation adaptations | [v1.0.5 / 28ab7121f4f03fc78ba05a914fa3a6d59eb2c80a](https://github.com/QmDeve/AndroidLiquidGlassView/tree/28ab7121f4f03fc78ba05a914fa3a6d59eb2c80a) | Copyright ©️ 2025 Donny Yale; MIT | [AndroidLiquidGlassView-LICENSE.txt](third_party/AndroidLiquidGlassView-LICENSE.txt) |
| AOSP overscroll sampling compensation | See root NOTICE and source comments | Copyright 2010, 2021 The Android Open Source Project; Apache-2.0 | [Project Apache-2.0 license](LICENSE) |
| Optional Lottie SDK | com.airbnb.android:lottie:6.7.1 | Airbnb; Apache-2.0 | [Lottie license](third_party/Lottie-6.7.1-LICENSE.txt) |
| Optional PAG SDK and Demo vector fixture | com.tencent.tav:libpag:4.5.98-noffavc; [v4.5.98](https://github.com/Tencent/libpag/tree/fb26af08fd2d097c18d4178b326cf1a832887bf5) | Tencent; Apache-2.0 and bundled third-party notices | [PAG license and notices](third_party/PAG-4.5.98-LICENSE.txt) |
| Optional Rive SDK | app.rive:rive-android:11.14.0; [11.14.0](https://github.com/rive-app/rive-android/tree/663c3b05fb444100eb3b4ef09fc7c87b80944eca) | Rive; MIT | [Rive SDK license](third_party/Rive-11.14.0-LICENSE.txt) |
| Rive Demo and test fixtures | [11.14.0](https://github.com/rive-app/rive-android/tree/663c3b05fb444100eb3b4ef09fc7c87b80944eca) | Rive; MIT | [Rive fixture license](third_party/Rive-11.14.0-LICENSE.txt) |

The shared recording abstraction derives from the source project's `HostCardAcrylicScene` / `HostCardMaterialScene` and API 29 implementations; viewport cropping derives from `FollowFeedStatusContentSource` / `HostChromeViewportSource`. The adaptation exposes host-neutral inputs and retains the contributor attribution. The maintainer's 2026-10-16 confirmation covers inclusion of these contributions under Apache-2.0 in Lumen; it does not change the license of Bilibili Innocent Lab as a whole. The source commits and authorship remain identified above and in NOTICE.

The later source commits retain the same attribution and permission. Their acrylic backdrop patch is adapted through internal binding/job flags rather than adding a constructor/copy parameter to the released `LumenSurfaceSampling` data class. Ordinary FROSTED illumination remains unchanged; software source invalidation retains the source wrapper's host-neutral ownership boundary.

The AndroidLiquidGlassView name and copyright above are taken from the adopted historical release. They are not replaced with the attribution of a later upstream version.

The engine AAR carries these records at `assets/lumen/licenses/`: LICENSE, NOTICE, this file, and the `third_party/` license texts. The sources archive carries the same relative file layout. The release check verifies these attachments.

The five new optional effect/asset modules carry the same records in their AAR and sources archives. Vendor SDKs remain separate Maven dependencies, retain their own licenses, and are not embedded or repackaged in Lumen's AARs. The core and motion modules do not acquire these runtimes.

Demo `sample/src/main/assets/p2/circle.json` is authored for this project under Apache-2.0. `minimal.pag` is the actual Git LFS binary from `assets/0.pag` at the PAG commit above (SHA-256 `69fc9a0e735aba25af7d7b8920e62dad89d54528f273ae31e0e7380327e379a9`, 2362 bytes). `circle_move.riv` comes from `app/src/main/res/raw/circle_move.riv` at the Rive commit above (SHA-256 `24f4b3a1bd73cc5cf979589bb3ce7aaa17694099ec1f3d9144d8962d0413257d`, 269 bytes). These sample bytes are bundled only in the Demo, not the engine libraries.

The instrumented-test-only `state_machine_configurations.riv` is from `kotlin/src/androidTest/res/raw/state_machine_configurations.riv` at the same Rive commit (SHA-256 `bfedde7821a207cd09758190735621618ce33efe1da8293be6f7f14568a0aefb`, 494 bytes), under the same MIT license. It is not included in the Demo or library AARs.

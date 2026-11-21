# Emoji Replace for Microcord

Emoji Replace lets you change how built-in emojis look in Microcord on your device. The selected style appears in chat and the emoji picker. On Android 11 and later it also applies to built-in emoji reactions.

It includes 13 offline packs (Apple, BlobMoji, Facebook, Google, Huawei, JoyPixels, Microsoft, Microsoft-3D, OpenMoji, Samsung, Samsung-Old, Toss, and WhatsApp). If a pack is missing an emoji, Microcord shows its usual artwork for that emoji.

## Install from GitHub Releases

1. Choose one of the APKs from the [latest GitHub release](https://github.com/mmaarij123/emoji-replace-microcord/releases/latest). If you have v1.4.1 or earlier installed, uninstall that version first.
2. Open the downloaded APK on your Android device and install it.
3. Open **Microcord → Settings → Plugins** and enable **Emoji Replace**. Approve the signing certificate if Microcord prompts you.

### APK versions

- **Full quality (141.7 MiB)** uses the same higher-quality artwork as v1.4.0. [Download full-quality APK](https://github.com/mmaarij123/emoji-replace-microcord/releases/download/v1.4.2/Emoji-Replace-1.4.2-Full.apk)
- **Compressed (49.1 MiB)** includes the same packs in a smaller download and fits Microcord’s 50 MiB plugin browser limit. [Download compressed APK](https://github.com/mmaarij123/emoji-replace-microcord/releases/download/v1.4.2/Emoji-Replace-1.4.2.apk)

## Change your emoji pack

Tap the gear next to **Emoji Replace** and open **Emoji pack**. Apple is selected by default. Choose **Default (Discord)** to restore Microcord’s usual emojis.

If emojis aren’t changing, open the picker once and then tap **Check emoji replacement** in the plugin settings.

## Technical details

Emoji Replace is a native Kotlin plugin for Microcord plugin API level 2 or later. Both APKs use the Android package `io.github.mmaarij123.emojireplace`.

The plugin uses Microcord’s message decorator API to replace built-in emoji images in chat and updates the native picker grid as its cells are bound. On Android 11 and later, it also uses Android’s `ResourcesLoader` to supply pack artwork for Microcord’s bundled emoji resources, including reactions.

### Build from source

Use JDK 21, Android SDK Platform 36.1, and Python with Pillow. From the plugin repository root, fetch the pinned artwork checkout and generate the assets locally. The builder bundles the artwork as indexed WebP images. The default build uses up to 46 px per side. The full-quality build uses up to 96 px per side.

```sh
git init ../BetterDiscordAddons
git -C ../BetterDiscordAddons remote add origin https://github.com/mwittrien/BetterDiscordAddons.git
git -C ../BetterDiscordAddons config remote.origin.promisor true
git -C ../BetterDiscordAddons config remote.origin.partialclonefilter blob:none
git -C ../BetterDiscordAddons fetch --depth=1 --filter=blob:none origin 9ea61dfba5599b1c8205d261fbbe35bf683120ab
git -C ../BetterDiscordAddons sparse-checkout set Themes/EmojiReplace/emojis
git -C ../BetterDiscordAddons checkout --detach FETCH_HEAD
python -m pip install Pillow==13.0.0
python tools/build_emoji_art.py ../BetterDiscordAddons
```

For the full-quality APK, add `--full` to the artwork generation command before building.

Run `./gradlew assembleDebug` on macOS or Linux, or `gradlew.bat assembleDebug` on Windows. The APK is built under `build/outputs/apk/debug`.

Run the picker-cell regression tests with `./gradlew testDebugUnitTest` or `gradlew.bat testDebugUnitTest`.

## License

The original Emoji Replace plugin code and local artwork builder are licensed under MIT. Copyright © 2026 Maarij (mmaarij123). You may use, copy, modify, and redistribute the code, including for commercial use. Include the copyright notice and permission notice with copies or substantial portions of the code. The code is provided as is, without warranty. The full terms include a limitation of liability. [Read the MIT license](LICENSE)

The emoji artwork in the release APKs follows the terms for each pack. See the notices for the artwork source, WebP conversion, and OpenMoji attribution. [Third-party notices](THIRD_PARTY_NOTICES.md)

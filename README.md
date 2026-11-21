# Emoji Replace for Microcord

Emoji Replace changes the artwork of standard Unicode emojis in chat messages and the emoji picker grid. The change is local to Microcord on your device. On Android 11 and later, Microcord's native emoji resources also use the bundled pack artwork, including when a reaction chip renders it.

Install the APK, open **Microcord → Settings → Plugins**, and enable **Emoji Replace**. Approve the signing certificate if Microcord prompts you. Open the plugin's **Settings → Emoji pack** to switch styles. Apple is selected by default. **Default (Discord)** restores Microcord's usual artwork.

The selector includes Apple, BlobMoji, Facebook, Google, Huawei, JoyPixels, Microsoft, Microsoft-3D, OpenMoji, Samsung, Samsung-Old, Toss, and WhatsApp.

All available artwork from the 13 upstream packs is included for offline use. An emoji keeps Microcord's built-in art if the selected pack has no matching image. Server emoji uploads use their own artwork.

After opening the emoji picker, use **Settings → Check emoji replacement** in the plugin to see active native resources, chat matches, picker cells and image decoding errors.

## APK versions

**v1.4.0** has better emoji image quality, especially for large emojis in chat. It keeps artwork up to 96 px per side, but its APK is roughly 140 MiB (149 MB) and requires manual installation. [Download v1.4.0](https://github.com/mmaarij123/emoji-replace-microcord/releases/tag/v1.4.0)

**v1.4.2** keeps all 13 packs offline with artwork up to 46 px per side. Its 49.1 MiB APK fits Microcord’s 50 MiB plugin browser limit. [Download v1.4.2](https://github.com/mmaarij123/emoji-replace-microcord/releases/tag/v1.4.2)

If you already have an older Emoji Replace version, remove it before enabling v1.4.2. Android installs v1.4.2 separately because it uses the new package name `io.github.mmaarij123.emojireplace`.

## Build from source

Use JDK 21, Android SDK Platform 36.1, Python and Pillow. From the plugin repository, fetch the pinned artwork checkout and generate the assets locally.

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

Run `./gradlew assembleDebug` on macOS or Linux, or `gradlew.bat assembleDebug` on Windows. The APK is built under `build/outputs/apk/debug`. The plugin requires Microcord plugin API level 2 or later.

Run the picker-cell regression tests with `./gradlew testDebugUnitTest` or `gradlew.bat testDebugUnitTest`.

## License

The original plugin source is available under the [MIT License](LICENSE). The Android project uses the [Microcord plugin template](https://github.com/microcord/plugin-template). The Gradle wrapper carries its own license in its JAR.

The pack builder draws artwork from [DevilBro's EmojiReplace project](https://github.com/mwittrien/BetterDiscordAddons/tree/9ea61dfba5599b1c8205d261fbbe35bf683120ab/Themes/EmojiReplace) and converts the images to WebP at up to 46 pixels per side to fit Microcord's 50 MiB plugin browser limit. The public repository contains plugin source and the local artwork builder. Vendor artwork retains its respective terms.

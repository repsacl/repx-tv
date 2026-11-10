# Repsac Launcher

A calm, fast, ad-free home screen for **Chromecast with Google TV** (and other Google TV / Android TV devices), written in Kotlin with no dependencies.

Google's own home screen is heavy on recommendations and ads and gets slow on devices with 2 GB of RAM. Repsac Launcher replaces it with your apps, a big clock and your own wallpapers — nothing else.

> Add a screenshot here: `docs/home-screen.png`

## Features

- **Your apps in a dock** – a single row that scrolls sideways, with an optional rounded background. Hold **OK** on an app to move it, remove it or open app info.
- **Glass row** – All apps, Play Store, Wallpaper, Settings and Add app, with simple line icons.
- **Big clock and date** – choose any font, and optionally a **frosted glass** look where the wallpaper shows through the digits.
- **Your own wallpapers** – drop pictures into `assets/wallpapers/` (plain black if there are none).
- **Animated focus outline** – Running light, Glow, Pulsing, Solid or Off, drawn just outside the card so it's visible on white banners too.
- **Screensaver** – a slideshow of your pictures with crossfade, slow zoom and the clock.
- **Now playing from Spotify** – what you're listening to on any of your devices, with album art and progress, on the home screen and the screensaver.
- **Quick icons** – Bluetooth (Remotes & Accessories), Wi-Fi and TV settings in the top right corner.
- **Settings on the TV** – card size, row backgrounds, outline style and distance, fonts, clock style and screensaver, all previewed live as you scroll.
- **English and Norwegian** – follows the TV's language. Add a `values-xx/strings.xml` to translate.
- **Light on resources** – the app list is only reloaded when apps change, banners are scaled down once, pictures are decoded off the main thread, and animations stop when the home screen isn't visible.

## What you need

- A Chromecast with Google TV (or another Google TV / Android TV device)
- A computer with [Android Studio](https://developer.android.com/studio) (free)
- `adb` – included with Android Studio, or on a Mac: `brew install --cask android-platform-tools`
- The TV and the computer on the same Wi-Fi

## Install

### 1. Turn on developer mode on the TV
**Settings → System → About**, select **Android TV OS build** and press OK 7 times.
Then **Settings → System → Developer options → Wireless debugging → On**.

### 2. Pair your computer with the TV (once)
On the TV, choose **Pair device with pairing code**. In a terminal:
```
adb pair <TV-IP>:<PAIRING-PORT>     # enter the 6-digit code shown on the TV
adb connect <TV-IP>:<PORT>          # the port shown on the main Wireless debugging screen
```
The port changes when wireless debugging is turned off and on, so always use what the TV shows.

### 3. Build and install
Open this folder in Android Studio (**File → Open**), wait for the Gradle sync, pick the TV in the device list and press **▶ Run**.
The launcher opens on the TV and also appears in the app list, so you can try it first.

### 4. Make it the home screen
```
./set-as-home.sh <TV-IP>:<PORT>
```
This turns off Google's home screen (nothing is deleted) and grants the two permissions the launcher uses:

| Permission | Used for |
|---|---|
| `WRITE_SECURE_SETTINGS` | The Bluetooth button (opens *Remotes & Accessories*) and turning on the screensaver |
| `WRITE_SETTINGS` | «Start after» in the screensaver settings |

**Undo at any time:** `./restore-home.sh <TV-IP>:<PORT>`

A Google system update can turn Google's home screen back on. Just run `set-as-home.sh` again.
When everything works you can turn wireless debugging off; turn it back on when you want to update.

## Customize

**On the TV:** open the **Settings** tile in the glass row. Changes are previewed as you scroll; OK keeps them, Back undoes them.

**In code:** [`Config.kt`](app/src/main/java/no/repsac/launcher/Config.kt) has all the defaults: favorite apps, sizes, colors, outline, fonts and more. Change a value and press ▶ Run.

| What | Where |
|---|---|
| Your own wallpapers | `app/src/main/assets/wallpapers/` (.jpg, .png, .webp) – also used by the screensaver |
| Your own fonts | `app/src/main/assets/fonts/` (.ttf, .otf) – show up in the font lists |
| Line icons | `LineIcon.kt` |
| Translations | `app/src/main/res/values-xx/strings.xml` |

Your own pictures and fonts are ignored by git (see `.gitignore`), so they stay on your machine. Only the two `sample-*` wallpapers are shared.

## Screensaver

Settings → **Screensaver** → **Use my pictures as screensaver: On**. Use **Preview** to see it right away.

**Turn off TV after** sets how long the screensaver runs before the Chromecast goes to sleep. With HDMI-CEC on (TV settings → Display & Sound → HDMI-CEC), the TV turns off with it.

Under Settings → **Wallpaper → Change picture** the home screen can switch to a random picture from your wallpapers every few minutes or hours, like the screensaver, or keep the same one.

Google TV may keep its own Ambient mode. If the preview works but the TV still shows Google's screensaver, turn off Ambient mode (or run `tools/debloat.sh`, which turns it off).

## Spotify «now playing»

Shows what you're playing on Spotify – on your phone, computer or the TV – in the top right corner of the home screen and the bottom right of the screensaver. It only appears while music is playing.

1. Go to [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard), log in and **Create app**:
   - Redirect URI: `http://127.0.0.1:8888/callback`
   - API: **Web API**
2. Copy the app's **Client ID**.
3. With the TV connected over adb, run on your computer:
   ```
   python3 tools/spotify-login.py --client-id <CLIENT_ID> --device <TV-IP>:<PORT>
   ```
   Log in to Spotify in the browser window that opens. The script sends the login to the TV.

The login is stored only on the TV, never in this project, and only adb can deliver it. Turn it on or off, or disconnect, under Settings → **Spotify**.

Spotify apps in development mode only work for a few allowed accounts. If the login says your account isn't allowed, add your Spotify e-mail under **User Management** in the app's dashboard.

## Optional: free up memory

`tools/debloat.sh <TV-IP>:<PORT>` turns off a few Google services that run in the background (Ambient mode, recommendations, feedback, Play Games, TalkBack, printing). Nothing is deleted; `tools/restore-debloat.sh` turns them back on. Read the list in the script first and remove anything you use.

## Project layout

```
app/src/main/java/no/repsac/launcher/
  MainActivity.kt     the home screen: clock, rows, menus, settings
  Config.kt           your defaults
  Prefs.kt            saved settings
  FocusOutline.kt     the animated outline around the focused card
  GlassText.kt        the frosted glass clock
  Screensaver.kt      the screensaver (Android «Dream») and its preview
  Spotify.kt          «now playing» from Spotify: login receiver, polling and the card
  Images.kt           wallpapers: loading and blur
  Fonts.kt            the font list
  LineIcon.kt         line icons drawn in code
  DateTime.kt         clock and date in the TV's language
set-as-home.sh        make it the home screen
restore-home.sh       bring Google's home screen back
tools/                Spotify login and optional debloat scripts
```

## Notes

- Tested on a Chromecast with Google TV (4K). Other Google TV devices should work; some Bluetooth and screensaver details may differ.
- The package name is `no.repsac.launcher`. If you fork the project, you can change `applicationId` in `app/build.gradle.kts` (and the package name in the scripts).
- This is a personal project, not affiliated with Google. Use at your own risk; everything can be undone with the scripts above.

## Credits

The design and line icons are based on [TV Detox](https://github.com/ansub/tv-detox) by Ansub Khan (MIT license).

## License

[MIT](LICENSE)

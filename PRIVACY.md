# Privacy model — REA HeliBoard fork

This is a private fork of [HeliBoard](https://github.com/HeliBorg/HeliBoard) (GPL-3.0).
It exists to make a Gboard-style keyboard whose data handling is *enforced by the build*,
not merely promised in a README, and to add colour themes.

Upstream already ships without the `INTERNET` permission. The point of this fork is to
keep it that way permanently and to close the remaining local-data surfaces.

## Threat model

What we defend against:

| Adversary / risk | Defence |
| --- | --- |
| A dependency, asset or future commit silently adds network access | Build-time privacy guard (below) |
| A vendor/OS silently pulling keyboard data off-device | `android:allowBackup="false"` |
| The keyboard leaking clipboard contents into whatever text field is focused | clipboard suggestions off by default |
| A copy of what you copy being retained on disk | clipboard history off by default, file retention off |
| Typing/gesture traces being collected and mail-exported | gesture data gathering disabled, `GestureFileProvider` removed from the manifest |
| The keyboard reading your address book | `READ_CONTACTS` removed from the manifest entirely |

Out of scope: a compromised OS or root attacker, and physical device access. Android's own
`InputMethodService` boundary means anything you type already passes through the keyboard;
this fork does not change that, it only makes sure nothing is stored or transmitted.

## Enforced invariant (the "guard")

`app/build.gradle.kts` defines `PrivacyGuardTask` and wires it into every
`assemble<Variant>` task. It reads the **merged** manifest (so library manifests count too)
and fails the build if it finds:

- `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_NETWORK_STATE`,
  `CHANGE_WIFI_STATE`
- `READ_CONTACTS`, `READ_CALL_LOG`
- `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`

It also fails if `android:allowBackup` is not `"false"`.

Run it directly:

```bash
./gradlew privacyGuardDebugNoMinify
```

CI runs this as a named step, so the check is visible in the log before the APK is built.

## Changes vs upstream

**Manifest** (`app/src/main/AndroidManifest.xml`)

- `android:allowBackup="true"` → `"false"`.
- `READ_CONTACTS` permission removed; the "use contacts" toggle was removed from
  Text correction settings so there is no dead switch.
- `GestureFileProvider` (the mail-export provider for collected gesture traces) removed.

**Defaults** (`latin/settings/Defaults.kt`)

- `PREF_ENABLE_CLIPBOARD_HISTORY` → `false`
- `PREF_SUGGEST_CLIPBOARD_CONTENT` → `false`
- `PREF_CLIPBOARD_USE_FILES` → `false`
- `PREF_USE_CONTACTS` was already `false`; it is now unreachable because the permission is gone.

**Gesture data gathering** (NLNet data-collection feature)

- `GestureDataGatheringSettings.isBackgroundGatheringEnabled()` and
  `isInActiveGatheringMode()` now return `false` unconditionally, so no typed word or gesture
  trace is ever written to the local database regardless of stored preferences.
- The "Data gathering" entry was removed from the main settings screen, and the promotion
  dialog was removed from `SettingsActivity`.
- The review/export screens still exist in the tree but are unreachable; the file provider
  they need is gone, so export is not merely hidden but unavailable.

**Fork identity**

- `applicationId` → `com.rea.heliboard` (debug builds get `.debug`), so this installs
  side by side with upstream HeliBoard without a signature or provider-authority clash.
- `clipboard_provider_authority` → `com.rea.heliboard.clipprovider`.
- `versionName` → `4.2-rea.1`.

**Themes** (`keyboard/KeyboardTheme.kt`, `res/values/strings.xml`)

Six dark palettes added: Midnight, Nord, Gruvbox, Solarized Dark, Terminal Green, Sunset.
They appear in *Appearance → Colors* next to the built-in ones.

## Not done yet ("tweak later")

These are known open items, listed honestly rather than implied as finished:

1. **Delete the gesturedata package** rather than leave it inert
   (`latin/utils/GestureData*.kt`, `settings/screens/gesturedata/`). Upstream plans the same
   removal; disabled-but-present code is a smaller attack surface than active code, but it
   still is dead weight.
2. **Personal dictionary / learned words** are still stored locally and grow as you type.
   They never leave the device, but they are readable on a rooted device. Consider making
   `use personalized dictionaries` default-off, or encrypting the dictionary DB.
3. **Release signing.** CI produces a debug-signed APK (installable, but the signing key is
   regenerated per run, so each install needs an uninstall first). For updates that just
   work, add a keystore as a repo secret and a signing config.
4. **Crash reports**: the app can still write crash reports to its own files dir and export
   them by hand. Local-only, but worth deleting if you never use it.
5. **Obfuscation check**: verify with a decompiler that builds contain no unexpected
   network/analytics strings.

## Building

You cannot build this on Termux — no Android/Gradle toolchain there. Build on GitHub Actions:

- Push to `main`, open a PR, or run the **Build APK** workflow manually.
- Download the `heliboard-rea-debug` artifact, install it, enable the keyboard in
  *Settings → System → Languages & input*.

## Renaming

- App id: `applicationId` in `app/build.gradle.kts`.
- Display name: `english_ime_name` in `app/src/main/res/values/strings.xml`.
- Kotlin package / namespace: leave as `helium314.keyboard.*`. Renaming it is a large
  mechanical change for no functional gain.

## Upstream

Do **not** open pull requests against HeliBoard with these changes. The project's
`AI_USAGE.md` explicitly prohibits AI-generated contributions and asks for human-to-human
communication. This fork is private and is not intended for upstream consumption.

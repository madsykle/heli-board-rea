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
| The keyboard reading your address book | `READ_CONTACTS` removed from the manifest entirely |
| Typing/gesture traces being collected and mail-exported | gesture data gathering disabled, `GestureFileProvider` removed from the manifest |

Out of scope: a compromised OS or root attacker, and physical device access. Android's own
`InputMethodService` boundary means anything you type already passes through the keyboard;
this fork does not change that. What it changes is what the keyboard is *able* to send
anywhere, and which of its data-retaining features are on out of the box.

### Deliberately not mitigated

These are known, accepted exposures. Listing them here so the rest of this document is not
read as a stronger claim than it is:

- **Clipboard.** Clipboard history, retention of copied files, and offering clipboard
  content as a word suggestion are all **on by default, matching upstream**. That means
  whatever you copy — including passwords and 2FA codes — can be offered as a suggestion in
  any focused text field, and is retained in the local database for the retention period.
  This was an explicit choice for this fork; the single line to change is
  `PREF_SUGGEST_CLIPBOARD_CONTENT` in `latin/settings/Defaults.kt`.
- **Personal dictionary / learned words** are stored locally and grow as you type.
- **Keyboard input itself** is visible to the OS and to any app with an accessibility
  service; no keyboard, including this one, can prevent that.

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

### The guard has been shown to actually trip

A guard that only ever passes proves nothing. A throwaway branch
(`verify/privacy-guard-trips`, since deleted) added
`android.permission.INTERNET` to the manifest and was built with
`gh workflow run "Build APK" --ref verify/privacy-guard-trips`. The build failed at
the guard step with:

```
> Privacy guard failed: the merged manifest declares forbidden permission(s):
  android.permission.INTERNET. Remove them or update PrivacyGuardTask deliberately.
Caused by: org.gradle.api.GradleException
```

To repeat it:

```bash
git checkout -b verify/privacy-guard-trips
# add <uses-permission android:name="android.permission.INTERNET" /> to the manifest
git commit -am "TEMP: positive control" && git push -u origin verify/privacy-guard-trips
gh workflow run "Build APK" -R <you>/heli-board-rea --ref verify/privacy-guard-trips
```

## Verification of the built artifact

Checked on `HeliBoard_4.2-rea.1-debugNoMinify.apk` from CI run 39006856748
(commit `b2a06ed4`), read straight out of the APK rather than the source, using
`apkanalyzer` (pure Java, so it runs on Termux) plus direct `resources.arsc` inspection:

```
package="com.rea.heliboard.debug"     versionName="4.2-rea.1"
android:allowBackup="false"
uses-permission: READ_USER_DICTIONARY, RECEIVE_BOOT_COMPLETED, VIBRATE, WRITE_USER_DICTIONARY
providers: ClipboardContentProvider, androidx-startup      (no GestureFileProvider)
signing: APK Signature Scheme v2, CN=Android Debug
```

The declared permission list above is the *complete* set the installed app can request.
`com.rea.heliboard.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` is also present;
that is AGP's own self-defined signature-level permission for Android 13+ receivers,
not a capability granted by the system.

Also confirmed present in `resources.arsc`, and the upstream value confirmed absent:

- `com.rea.heliboard.debug.clipprovider` present, `helium314.keyboard.debug.clipprovider` gone
- `HeliBoard (REA) debug` present, `HeliBoard debug` gone
- the six `theme_name_rea_*` strings present

Not yet verified:

- The **release** variant is untested. It has its own proguard config and no signing
  config, so `assembleRelease` currently produces an unsigned APK that cannot be
  installed until item 3 below is done.
- No decompiler pass has been run over `classes*.dex` to look for unexpected
  network/analytics code (item 5 below).

## Changes vs upstream

**Manifest** (`app/src/main/AndroidManifest.xml`)

- `android:allowBackup="true"` → `"false"`.
- `READ_CONTACTS` permission removed; the "use contacts" toggle was removed from
  Text correction settings so there is no dead switch.
- `GestureFileProvider` (the mail-export provider for collected gesture traces) removed.

**Defaults** (`latin/settings/Defaults.kt`)

- Clipboard defaults are **unchanged from upstream** (`ENABLE_CLIPBOARD_HISTORY`,
  `SUGGEST_CLIPBOARD_CONTENT` and `CLIPBOARD_USE_FILES` are all `true`) — see
  "Deliberately not mitigated" above. An earlier revision of this fork set all three to
  `false`; that was reverted at the maintainer's request.
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
- `clipboard_provider_authority` → `com.rea.heliboard.clipprovider` in
  `res/values/clip_provider.xml`, **and** in the build-type overrides
  `res/values/clip_provider.xml` under `src/debug/` and `src/debugNoMinify/`.
  Those overrides exist upstream and hardcode `helium314.keyboard.debug.clipprovider`;
  leaving them alone would have given debug builds a non-fork authority and collided
  with upstream's own debug build.
- Display name → "HeliBoard (REA)" / "HeliBoard (REA) debug"
  (`values/donottranslate.xml` plus the two debug `strings.xml` overrides), so the IME
  picker shows which keyboard is which when both are installed. The translatable
  in-app titles ("HeliBoard Settings", "HeliBoard Spell Checker") were deliberately
  left as upstream to avoid churning ~100 translation files.
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
3. **Crash reports**: the app can still write crash reports to its own files dir and export
   them by hand. Local-only, but worth deleting if you never use it.
4. **Decompiler pass over the APK**: the manifest and resource table are verified, but no
   pass has been made over `classes*.dex` to look for unexpected network/analytics code.
   The manifest permission list already makes network use impossible without a permission
   being present, but the dex check is the remaining belt-and-braces step.

## Release signing

`app/build.gradle.kts` reads the keystore from the environment
(`REA_KEYSTORE_PATH`, `REA_KEYSTORE_PASSWORD`, `REA_KEY_ALIAS`, `REA_KEY_PASSWORD`) and only
creates a `release` signing config when that file actually exists. If it is absent the
release variant is left **unsigned** on purpose — it does not silently fall back to AGP's
throwaway debug key.

CI decodes `KEYSTORE_BASE64` into `$GITHUB_WORKSPACE/rea-release.jks` and, only when that
secret is present, runs `assembleRelease`, calls `apksigner verify --print-certs` on the
result, and uploads it as `heliboard-rea-release`. The release package is `com.rea.heliboard`
(no `.debug` suffix), so it installs alongside the debug build but is the one to actually use.

Repo secrets:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 -w0 rea-release.jks` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias (e.g. `rea`) |
| `KEY_PASSWORD` | key password |

**If you lose this keystore you lose the ability to update an installed release build.**
Keep a copy somewhere durable outside the repo. A committed keystore would be a permanent
compromise, so it is deliberately not in version control.

## Building

You cannot build this on Termux — no Android/Gradle toolchain there. Build on GitHub Actions:

- Push to `main`, open a PR, or run the **Build APK** workflow manually.
- Download the `heliboard-rea-release` artifact if signing is configured (recommended — it
  installs over previous builds), otherwise `heliboard-rea-debug`.
- Install, then enable the keyboard in *Settings → System → Languages & input*.

## Renaming

- App id: `applicationId` in `app/build.gradle.kts`.
- Display name: `english_ime_name` in `app/src/main/res/values/strings.xml`.
- Kotlin package / namespace: leave as `helium314.keyboard.*`. Renaming it is a large
  mechanical change for no functional gain.

## Upstream

Do **not** open pull requests against HeliBoard with these changes. The project's
`AI_USAGE.md` explicitly prohibits AI-generated contributions and asks for human-to-human
communication. This fork is private and is not intended for upstream consumption.

# iOS 27 Dark theme (REA fork)

This fork carries a restyle ported from the maintainer's Figma design, supplied as a
React/CSS keyboard mock (`theme.tsx`). The design is a *keyboard* design; the note app and
status bar around it in the mock are the demo harness, not part of HeliBoard, so they are
not reproduced.

Everything the design specifies for the keyboard itself is implemented, with the deviations
noted under *Theme-local overrides*, *Font* and *Icons* — in short: the action key is a lighter
neutral grey than the mock paints it, hints are off, and the enter key is an arrow where the mock
writes the word "return". This document records the mapping and, importantly, the things the
platform will not do, so a later tweak does not have to rediscover them.

## Where it lives

| Piece | File |
| --- | --- |
| Palette | `keyboard/KeyboardTheme.kt` — `THEME_IOS27` (`rea_ios27`) |
| Panel + key geometry | `res/drawable/keyboard_background_lxx_base_border.xml`, `btn_keyboard_key_*_lxx_base_border.xml` |
| Suggestion strip | `res/drawable/keyboard_suggest_strip_lxx_base_border.xml`, `res/drawable/suggestions_strip_divider.xml` |
| Borderless spacebar | `res/drawable/btn_keyboard_spacebar_base.xml` **and** `res/drawable-v26/btn_keyboard_spacebar_base.xml` — the `-v26` copy wins on every modern device, so keep the two in step |
| Popup keys / panel | `res/drawable/btn_keyboard_key_popup_lxx_base.xml`, `keyboard_popup_panel_background_lxx_base.xml` |
| Theme-local hooks | `latin/common/Colors.kt` (`DefaultColors`), `keyboard/KeyboardView.java` (hint gate) |
| Bundled font | `res/font/inter_regular.ttf`, `KeyboardTypeface.kt`, `assets/licenses/Inter-OFL-1.1.txt` |
| Icon set | `res/drawable/ic_ios27_*.xml`, `keyboard/internal/KeyboardIconsSet.kt` |
| It ships as the default | `latin/settings/Defaults.kt` |

The palette is offered as **iOS 27 Dark** in *Appearance → Colors* and is the shipped
default for both day and night. Key borders are on by default because the iOS look depends
on keys having a visible rounded shape, which is the bordered drawable path — with borders
off HeliBoard paints keys through a different, flat drawable.

## Palette

The design's layers are translucent (`#696b75aa`, `#202127cc`, …) because they composite
over the app behind them. The palette stores the colours **already composited** onto the
design's own panel, i.e. the hue you see in the Figma mock; the design's translucency is
put back by the glass layer described below.

| Design token | Design value | Theme field | Value used |
| --- | --- | --- | --- |
| accent (swipe trail, selection highlights) | `#f7ce69` | accent, gesture | `#f7ce69` |
| keyboard panel ramp | `#45495788` → `#202127cc` → `#27282ee6` | background | `#2c2f38` |
| key ramp | `#696b75aa` → `#4b4d57bb` | keys, spacebar | `#56585f` |
| functional key ramp | `#30333dcc` → `#282a32dd` | functional keys | `#2f323c` |
| pressed key (`.key:active`) | `#888b99` | `pressedKeyColor` | `#888b99` |
| action/return key | — (design keeps the functional ramp) | `actionKeyBackground` | `#6f727c` |
| `:root` foreground | `#f5f5f7` | key text, suggestion text | `#f5f5f7` |
| footer icon / hint | `#b4b5bf` | hint text | `#b4b5bf` |
| trackpad hint | `#c5c7d0` | spacebar text | `#c5c7d0` |

### Theme-local overrides

Three small hooks exist so this theme can differ from HeliBoard's hardcoded behaviour
without changing any other theme. All three are `null`/`true` by default, so a theme that
does not pass them behaves exactly as upstream:

- `pressedKeyColor` — HeliBoard computes a pressed colour by moving the resting colour one
  small step (`brightenOrDarken(colour, true)`), which is a much smaller jump than the
  design's. The design's pressed key is `#888b99`, a large step lighter, and the design
  gives every key family the same pressed colour (its `.utility`/`.return`/`.space` rules
  never override `.key:active`). This theme states `#888b99` and it is used for character,
  functional, spacebar **and** action keys. Applied to the *state lists*, so it reaches both
  the opaque and (for this theme) the translucent SRC_IN path.
- `actionKeyBackground` — the action key (enter / search / done / send …) is painted with
  the theme *accent* upstream, which here would be the gold of the swipe trail. The design
  wants a neutral key, so the action key has its own grey (`#6f727c`, deliberately lighter
  than the character keys so it still reads as the primary button). This is a deliberate
  deviation from the mock, which paints `.return` with the same functional ramp as the other
  bottom-row keys; the brief asked for a lighter neutral key. The gold accent now reaches
  only the swipe trail and the selection highlights (selected emoji category, pinned
  clipboard entries, enabled toolbar key). This also drives the action key's *pressed*
  colour and its icon contrast decision.
- `showKeyHints` — whether the small hint labels/icons in the key corners are drawn at all.
  `KeyboardView` now requires **both** the user's `PREF_SHOW_HINTS` and the theme's
  `showKeyHints`. For this theme it is `false`, so no hint glyph appears in any key corner,
  and because hint visibility also decides whether an off-centre key label is drawn in the
  corner rather than centred, key labels come out centred — which is what the design shows.
  This is a `Colors` property rather than a settings key on purpose: it is a property of the
  design, not of the user, and it must not leak into other themes or into the settings UI.

## Font

The design is set in SF Pro. SF cannot legally be shipped, so **Inter 4.1 (Regular)** is bundled
as the closest free stand-in: the same kind of neo-grotesque, close proportions, `SIL OFL 1.1`.
It applies to **this theme only**, and only while the user has not supplied a font of their own.

- File: `app/src/main/res/font/inter_regular.ttf` — the *static* instance, so it works from the
  API 21 floor; the variable font in the same release needs API 26 variation support.
- Licence: `app/src/main/assets/licenses/Inter-OFL-1.1.txt`, i.e. the OFL text ships inside the
  APK next to the font. Source: `github.com/rsms/inter`, release `v4.1`,
  `extras/ttf/Inter-Regular.ttf`.
- Hook: `KeyboardTypeface.loadThemeTypeface` asks `KeyboardTheme.getCurrentColorThemeName`
  whether the theme brings a font, and otherwise leaves the platform typeface in place, exactly
  as upstream. A font file the user supplied (*Appearance → Custom font*) is tried first and
  still wins, because the theme font is only consulted when no custom font file exists.
- Because it is applied where the app already resolves its keyboard typeface, everything that
  goes through `KeyboardTypeface` follows it: key labels, hint labels, the spacebar language
  label, suggestions, clipboard rows and the emoji search. The Material settings screens are
  untouched, and the emoji font (`Settings.getCustomEmojiFontFile`) is a separate setting.
- Cache: `KeyboardTypeface` resolves the font once per process, so `KeyboardSwitcher.setThemeNeedsReload()`
  now clears that cache. Without it, leaving the theme would have kept Inter until the process
  was killed.

**Sizes.** The design's key letters are 24px in a 43px key = **55%**, which is exactly what
HeliBoard's bordered style already asks for (`config_key_letter_ratio_lxx = 55%`), so the ratio
is left alone and nothing is resized to compensate for Inter. Inter's cap height (≈0.727em) is
about 4% taller than SF Pro Text's (≈0.70em), so at the same ratio the letters read a hair
larger than the mock — that is the only size difference, and it is stated rather than hidden.

**What it does not cover.** Inter has no Arabic, Hebrew or CJK glyphs. Android's per-character
fallback still draws those scripts, so a non-Latin layout keeps working, but it does not get the
design's font while this theme is active.

## Icons

Shift, delete, globe, mic and enter use the design's own glyphs, ported from the inline SVGs in
the mock, for **this theme only**:

| Key | Drawable | Design source |
| --- | --- | --- |
| shift | `ic_ios27_shift.xml` | `ShiftIcon`, outline |
| shift, active | `ic_ios27_shift_shifted.xml` | `ShiftIcon`, `fill="currentColor"` |
| shift, locked | `ic_ios27_shift_locked.xml` | the filled arrow plus the caps-lock bar (the mock has no locked state) |
| delete | `ic_ios27_delete.xml` | `DeleteIcon` |
| globe | `ic_ios27_globe.xml` | `GlobeIcon` |
| mic | `ic_ios27_mic.xml` | `MicIcon` |
| enter | `ic_ios27_return.xml` | not in the mock, see below |

- The icon set in HeliBoard is chosen by the theme *style* (`PREF_ICON_STYLE`), which this colour
  theme shares with every other theme, so the override is keyed on the colour theme instead:
  `KeyboardIconsSet.themeIconIds` merges `iconsIos27` over the style's icons when
  `KeyboardTheme.getCurrentColorThemeName` says `rea_ios27`. Icons the user customised still win
  over it, and every other theme still renders its style's icons.
- They are stroked, never filled (except the filled states the design itself fills), and drawn
  white so HeliBoard's `MODULATE` tint turns them into the theme's key colour like every other
  icon in the app.
- The mock's viewBoxes are 28×30, 32×24, 26×26 and 24×28. Each is ported into a 24×24 viewport
  with a scaling group, so the aspect ratio is the design's and the **intrinsic size stays 24dp**,
  the size every other keyboard icon has. HeliBoard draws a key icon at its intrinsic size, so key
  geometry and the 55% letter ratio are untouched: 24dp in this device's ≈45dp key row is ≈53%,
  against 25/43 ≈ 58% in the mock — a touch smaller than the design, not larger.
- `strokeLineJoin`/`strokeLineCap` (the design's round joins and caps) are API 24+ attributes;
  below that they are ignored, which leaves slightly spikier corners on API 21–23 only. No crash
  and no fallback to the old icon.
- **Enter is a vector here where the mock writes the word "return".** The brief asked for a
  vector, so it is SF Symbols' `return` shape: down on the right, left along the bottom, ending in
  a 45° arrowhead. To go back to the design's text label, that is a layout `label` change, not a
  drawable one.
- `shortcut_key_disabled` (the crossed-out mic for when voice input is unavailable) is **not**
  ported: a plain mic there would claim voice input works. It keeps the Material glyph until
  someone draws the slashed variant.

## How the gradients work

`Colors` paints every key by tinting a drawable with a single colour using
`PorterDuff.Mode.MULTIPLY` (see `setColor` in `latin/common/Colors.kt`). A normal
two-tone gradient would therefore be flattened to one flat colour.

It survives anyway, because MULTIPLY *multiplies* rather than replaces: a **greyscale
ramp** baked into the drawable is scaled by the tint. So each key drawable carries a
white-to-grey ramp, and the theme colour becomes the light end of the rendered gradient:

```
drawable   #FFFFFF  ──────────────►  #CCCCCC
tint       #56585F  ×              ×  #56585F
rendered   #56585F  ──────────────►  #45474D      <- the design's two stops
```

The suggestion strip uses the same trick with three stops: white (1.0), `#A8A8A8` (0.66) at
45% height, then `#DEDEDE` (0.87). Tinting with `#2c2f38` reproduces the design's
`#454957 → #202127 → #27282e` ramp.

That ramp only ever shows on the suggestion strip, and only while no background image is
set (with an image, the strip's own background is transparent so the image shows through).
The panel *behind the keys* is not drawn from `keyboard_background_lxx_base_border.xml` at
all: `KeyboardView.setKeyboard` replaces the main keyboard view's XML background with
`Color.TRANSPARENT` ("actual background color/drawable is applied to main_keyboard_frame"),
and `InputView` gives `main_keyboard_frame` a white background carrying the `background`
colour as a `MODULATE` filter. So the panel behind the keys is one flat `#2c2f38` — or the
user's background image, when one is set — and only the strip carries a ramp. The
`keyboard_background_lxx_base_border.xml` restyle still matters for the emoji *search*
keyboard, which does draw that drawable.

This is why the drawables hold greyscale values and not the design's colours: **changing a
theme's colours must not require editing the drawables, and editing a drawable changes
every theme.** If you want to retune the ramp, edit the greyscale stops; if you want to
retune the hue, edit the palette in `KeyboardTheme.kt`.

## Glass: real translucency for this theme only

The design composites the keyboard over the app behind it. `PorterDuff.Mode.MULTIPLY`
cannot express that, no matter what alpha the tint carries: it composites alpha as
`Sa + Da - Sa*Da`, so an opaque drawable stays opaque. Real translucency therefore needs a
different compositing mode, and it is opt-in per theme:

- `GlassAlphas` (in `latin/common/Colors.kt`) is a per-key alpha bag passed to
  `DefaultColors`. **Only `THEME_IOS27` passes one**; every other theme — and every
  user-defined theme — leaves it `null` and takes the untouched `MULTIPLY` path, so they
  render bit-for-bit as before.
- Keys / functional keys / spacebar / action keys are tinted with `PorterDuff.Mode.SRC_IN`
  instead. `SRC_IN` composites as `Sa * Da`, so the tint's alpha becomes real alpha, and
  the drawable's own alpha channel masks it — which is what keeps the rounded corners and
  the transparent "empty key" state.
- Only keys are glassed. The panel is not a translucent surface: it is the user's background
  image when one is set, and the flat panel colour otherwise. See *Background image* below.
- Alphas are **scaled, not overwritten**: a colour that already carries alpha keeps its
  relative transparency, so a theme that states a deliberately faint colour is not made
  opaque by the glass layer.

Alphas used:

| Surface | Alpha |
| --- | --- |
| character keys | `0xAA` (design's own `#696b75aa`) |
| character keys, pressed | `0xBB` |
| functional keys / pressed | `0xB4` / `0xC6` |

The panel is **not** in this table: it is opaque by design. A translucent panel would show
the IME window's background, which is a platform colour this app does not control
(`<application>` declares no theme), so the panel is either the user's background image or
the flat `#2c2f38` — never a translucent slab.

### What you get, and what you give up

With a **keyboard background image** set (HeliBoard's own feature, *Appearance → Keyboard
background image*) the effect is the one the design wants: the image is the panel, the keys
are genuinely translucent panes over it, and the suggestion strip is fully transparent over
it, so the image shows through the keys and at full strength in the strip and in the gaps
between keys. The text stays fully opaque, because the translucency lives in the key
*background* drawable, not in the view.

Trade-offs, stated plainly:

1. **The baked colour ramps stop being visible on glassed surfaces.** `SRC_IN` takes only
   the tint's colour, so a key becomes one flat translucent colour per state instead of a
   light-to-dark ramp. The design's key ramp is subtle and its *alpha* ramp is kept (via the
   pressed state), but the RGB ramp on keys is gone. This is unavoidable without giving this
   one theme its own copies of the key drawables and selecting them in code — the drawables
   are chosen by the keyboard *style*, which is shared with every other theme, so an
   alpha-ramped drawable there would flatten every other theme's keys instead.
2. **Panel without a background image.** With no image, the panel is the flat `#2c2f38`
   colour. It has to be opaque: behind the panel is the IME window's own background, an
   opaque platform colour the app does not control (`<application>` declares no theme), so a
   translucent panel would show an unpredictable colour rather than the app. Two ways to get
   the design's material look instead: set a background image (then the image *is* the
   panel), or accept the flat panel as the fallback.
3. **The panel is the image.** When a background image is set, `Colors` puts the image
   itself behind the keys. The panel is therefore not a translucent scrim *on top of* the
   image — the image is not dimmed. If you want a frosted slab over the image instead (the
   image visible but dimmed), that is a small change to the `MAIN_BACKGROUND` branch.
4. **Only the keyboard view.** The emoji and clipboard panels declare
   `android:background="@android:color/transparent"` in their layouts, so they were never
   coloured by the panel path and are unaffected either way.
5. **API floor.** `SRC_IN` composites the same way from API 21 up. `BlendModeColorFilterCompat`
   is still used for the key icons and text, which is where the tint alpha actually has to be
   honoured on old APIs. The panel no longer uses a colour filter at all, so it has no API
   dependence.

To go back to fully opaque rendering, delete the `glassAlphas = GlassAlphas(…)` argument
from the `THEME_IOS27` branch in `KeyboardTheme.kt`. Nothing else needs to change.

## Background image

HeliBoard has its own *Appearance → Keyboard background image* setting, and this theme uses
it as its material: with an image set the panel **is** that image, and the keys are
translucent panes over it (see *Glass* above).

**Where the file goes.** The picker copies whatever you choose into *device-protected* app
storage, under these four names:

| Orientation | Day | Night |
| --- | --- | --- |
| Portrait | `custom_background_image` | `custom_background_image_night` |
| Landscape | `custom_background_image_landscape` | `custom_background_image_landscape_night` |

i.e. `/data/user_de/0/com.rea.heliboard/files/` on this device. That directory is
device-protected storage: the app can read it before first unlock, and a normal file manager
or `adb pull` cannot reach it without root. **The picker is the only route in** — there is no
"choose a path" setting. Landscape falls back to the portrait file, and night falls back to
the day file, so one portrait image is enough to see the effect.

**The image is stretched, not cropped.** `Colors` rasterises it with
`Bitmap.createScaledBitmap(bitmap, width, height, true)`: one non-uniform scale to exactly
the panel size. No cropping, no letterboxing, no tiling. The only way to avoid distortion is
to give the image the panel's aspect ratio.

**Panel size.** Computed from `res/values/config.xml` (+ `values-land`) for this device
(RMX2001, 1080×2400, 480 dpi). The panel is the whole keyboard frame, including the
suggestion/toolbar strip:

| Situation | Panel | Aspect | Give it |
| --- | --- | --- | --- |
| Portrait, no number row | 1080×787 | 1.37:1 | 1080×787, or 2160×1574 for a higher-res source |
| Portrait, number row on | 1080×940 | 1.15:1 | 1080×940 |
| Landscape | ≈2400×605 | ≈4.0:1 | a separate `_landscape` file — the portrait aspect cannot serve this |

Supply at least the panel's own pixel size: a larger image is downscaled with filtering
(fine), a smaller one is upscaled and looks soft. Two caveats on the numbers: they follow
the user's keyboard-height slider (`mKeyboardHeightScale`, default 1.0), which changes the
height and therefore the aspect; and in landscape the width can be reduced by system-bar
insets, so the landscape aspect is approximate. Because portrait is ~1.4:1 and landscape is
~4:1, no single image is ideal for both — pick the one you care about, or supply both.

A practical note: since the image is stretched and never cropped, a *photo* will distort.
A texture, gradient or pattern is what reads as intended.

**Fallback when the image is absent or broken.** No image → the panel is the flat `#2c2f38`
panel colour; the theme still renders, with no translucency and nothing broken. A file that
exists but cannot be decoded (corrupt, or not an image) also lands on the flat panel:
`Settings.readUserBackgroundImage` returns `null` for a bitmap that failed to decode, the
picker refuses such a file (deleting it and showing the existing "file read error" dialog,
as it already did for a file it could not read), and `panelBackgroundOrNull` returns `null`
when the view is not laid out yet or the bitmap is null. Before this, an undecodable file
crashed the keyboard: `BitmapDrawable` holds a null bitmap happily, and the NPE landed later
inside `Drawable.toBitmap()` during the panel's first layout (a zero-sized view threw
`IllegalArgumentException` from `createScaledBitmap`). Upstream produces such a file
whenever the picked file is readable but not decodable.

**The first show is not lost.** The panel is rasterised when `setBackground(…, MAIN_BACKGROUND)`
first runs, which is before the keyboard view has been measured, so that first call gets a
zero-sized panel and cannot rasterise. The image is therefore *kept* and the flat panel is used
until a later call can do it, rather than marking the setup as done and dropping the image for
the rest of the session.

## Approximated or not reproducible

Stated plainly, because these are the places where the build will not look exactly like
Figma:

1. **No backdrop blur.** `backdrop-filter: blur(40px) saturate(140%)` cannot be done by an
   IME. The keyboard surfaces are genuinely translucent instead (see *Glass* above), so
   what is behind them shows through, but it is not blurred. Android 12+ does have
   cross-window blur (`WindowManager.LayoutParams.setBackgroundBlurRadius`), which could
   approximate it on supporting devices, but it is device-dependent and was not attempted.
2. **No inset top highlight on keys.** `inset 0 1px 0 #ffffff25` needs a highlight
   *lighter* than the key colour. MULTIPLY can only darken, so the highlight would render
   as the key colour and vanish. Omitted rather than faked.
3. **Pressed keys follow the design.** Solved for this theme by `pressedKeyColor` (see
   *Theme-local overrides*): pressed keys use the design's own `#888b99` instead of
   HeliBoard's small brighten step. Because the keys are glassed, the rendered pressed key
   is `#888b99` at `0xBB` alpha, i.e. `#888b99` over whatever is behind the keyboard, not
   solid `#888b99`.
4. **Gradient angle.** CSS `160deg`/`150deg` are near-vertical; Android gradients only take
   45° steps, so both are rendered as `270` (straight top to bottom). Very close, not
   identical.
5. **The ramp restarts at the suggestion strip.** The strip is a separate view with its own
   background, so it restarts the ramp rather than continuing it. The strip sits at the
   light end of the ramp, so this reads as a lit top edge rather than a seam.
6. **Key icons/labels are the design's glyphs, drawn by HeliBoard's layout code.** Shift, delete,
   globe, mic and enter are ported from the mock's inline SVGs (see *Icons*); every other icon
   (go, search, send, done, emoji, clipboard, …) is still HeliBoard's Material glyph, and the
   layouts and spacing are HeliBoard's, not iOS's. The font is Inter rather than SF (see *Font*).
7. **The settings UI is untouched.** The design covers the keyboard only. The Material 3
   settings screens keep HeliBoard's theming, which on Android 12+ follows the system
   accent (see `res/values-v31/colors.xml`). To force the design's gold accent app-wide,
   override `highlight_color_lxx_dark` in `values/colors.xml` and
   `values-v31/colors.xml`.

## Reverting to stock appearance

In `latin/settings/Defaults.kt`:

```kotlin
const val PREF_THEME_COLORS = KeyboardTheme.THEME_IOS27   → THEME_LIGHT
const val PREF_THEME_COLORS_NIGHT = KeyboardTheme.THEME_IOS27 → THEME_DARK
const val PREF_THEME_KEY_BORDERS = true                    → false
```

The drawable restyle is not behind a flag: it applies to the bordered look of the Material
and Rounded styles for every theme. Those drawables are referenced only by
`themes-lxx-base-border.xml` and `themes-rounded-base-border.xml`, so nothing else in the
app reads them.

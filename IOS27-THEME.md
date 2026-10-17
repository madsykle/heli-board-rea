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
| Palette | `keyboard/KeyboardTheme.kt` — `Ios27Palette` (one object, read by `THEME_IOS27`) |
| Panel + key geometry | `res/drawable/keyboard_background_lxx_base_border.xml`, `btn_keyboard_key_*_lxx_base_border.xml` |
| Suggestion strip | `res/drawable/keyboard_suggest_strip_lxx_base_border.xml`, `res/drawable/suggestions_strip_divider.xml` |
| Borderless spacebar | `res/drawable/btn_keyboard_spacebar_base.xml` **and** `res/drawable-v26/btn_keyboard_spacebar_base.xml` — the `-v26` copy wins on every modern device, so keep the two in step |
| Popup keys / panel | `res/drawable/btn_keyboard_key_popup_lxx_base.xml`, `keyboard_popup_panel_background_lxx_base.xml` |
| Theme-local hooks | `latin/common/Colors.kt` (`DefaultColors`), `keyboard/KeyboardView.java` (hint gate, key shading) |
| Bundled font | `res/font/inter_regular.ttf`, `KeyboardTypeface.kt`, `assets/licenses/Inter-OFL-1.1.txt` |
| Icon set | `res/drawable/ic_ios27_*.xml`, `keyboard/internal/KeyboardIconsSet.kt` |
| It ships as the default | `latin/settings/Defaults.kt` |

The palette is offered as **iOS 27 Dark** in *Appearance → Colors* and is the shipped
default for both day and night. Key borders are on by default because the iOS look depends
on keys having a visible rounded shape, which is the bordered drawable path — with borders
off HeliBoard paints keys through a different, flat drawable.

## Palette

Every colour the theme uses is named in **one object**, `Ios27Palette` in
`keyboard/KeyboardTheme.kt`, and the theme's `DefaultColors(...)` call reads only that object. A
tweak is therefore a one-line edit in that object and nothing else has to be touched. The values
are written the way the design writes them — as ARGB, with the opacity inside the colour
(`#CC202127`, not "`#202127` at 80%") — so the transparency of a surface is part of its colour.

| Palette entry | Value | Paints |
| --- | --- | --- |
| `PANEL` | `#CC202127` | the panel — and the suggestion strip, which is part of the same slab |
| `KEY` | `#665B5F68` | character keys and the spacebar |
| `PRESSED` | `#997D828D` | any key surface while it is held down |
| `MODIFIER` | `#992B2D35` | functional keys: shift, delete, globe, mode keys, `?123`, comma, period |
| `ACTION` | `#FF626570` | the action key: enter, search, done, send |
| `CHARACTER` | `#FFF5F5F7` | key letters and suggestion text |
| `SECONDARY_ICON` | `#B8C0C9` | corner hints, toolbar icons, spacebar label |
| `DIVIDER` | `#30FFFFFF` | the rules between the suggestion areas |

Three scalars sit next to the colours and scale their alphas; 255 means "use them exactly as
written":

| Scalar | Default | Effect |
| --- | --- | --- |
| `PANEL_ALPHA` | 200 | how opaque the panel material is; lower shows more of the backdrop |
| `KEY_ALPHA` | 255 | how opaque every key surface is |
| `SCRIM_INTENSITY` | 0 | an extra dim layer between the background image and the panel; 0 = off |

`PANEL_EDGE_SHADE`, `KEY_EDGE_SHADE`, `KEY_TOP_HIGHLIGHT`, `KEY_RADIUS_DP` (9) and
`PANEL_RADIUS_DP` (28) describe the soft shading and the corner radii, see *Soft edges*.

The palette is applied through the theme's colour slots, so it reaches everything at once, without
each surface needing its own hook: the panel, the keys, the spacebar, the suggestion strip and its
rules, long-press popups and the keys inside them, the more-suggestions popup, the symbols and
numbers layouts, the emoji page and every alternate-language layout.

The design's layers are translucent because they composite over the app behind them, and this
palette keeps that: see *Glass* below for how the translucency is put back, and *Soft edges* for
the one thing a tint cannot draw.

### Theme-local overrides

Three small hooks exist so this theme can differ from HeliBoard's hardcoded behaviour
without changing any other theme. All three are `null`/`true` by default, so a theme that
does not pass them behaves exactly as upstream:

- `pressedKeyColor` — HeliBoard computes a pressed colour by moving the resting colour one
  small step (`brightenOrDarken(colour, true)`), which is a much smaller jump than the
  design's. This theme states the palette's `PRESSED` (`#997D828D`), one large step lighter
  than any resting key, and uses it for character, functional, spacebar **and** action keys,
  because the design gives every key family the same pressed colour (its
  `.utility`/`.return`/`.space` rules never override `.key:active`). It is applied to the
  *state lists*, so it reaches both the opaque and the translucent SRC_IN path, and it is
  taken at the opacity of the key it presses (`max` of the two alphas) so that pressing the
  opaque action key lightens it instead of fading it.
- `actionKeyBackground` — the action key (enter / search / done / send …) is painted with
  the theme *accent* upstream, which here would be the gold of the swipe trail. The design
  wants a neutral key, so the action key gets the palette's `ACTION` (`#FF626570`,
  deliberately lighter than the character keys so it still reads as the primary button). This
  is a deliberate deviation from the mock, which paints `.return` with the same functional
  ramp as the other bottom-row keys; the brief asked for a lighter neutral key. The gold
  accent now reaches only the swipe trail and the selection highlights (selected emoji
  category, pinned clipboard entries, enabled toolbar key). This also drives the action key's
  *pressed* colour and its icon contrast decision.
- `dividerColor` — the suggestion rules take their drawing from the strip *style*, which every
  theme shares, so a theme that wants its own colour states it here. The rule is then drawn from
  a colourless copy of the divider (`suggestions_strip_divider_tinted.xml`, white) and tinted
  with the stated colour: a tint composites with the source alpha, so white is what makes the
  rendered colour exactly the stated one. `null` — every other theme — keeps the style's own
  drawable untouched.
- `popupKeysBackground`, `popupPanelBackground` — long-press popups and the more-suggestions
  popup are painted from a *brightened variant of the panel* upstream, which would have made them
  out-of-palette panels rather than keys. This theme states the palette's `KEY` and `PANEL`, so a
  popup is the same material as the keyboard it opened from, including the pressed colour (and
  the glass alphas). `null` for both leaves upstream behaviour alone.
- `panelOverImage`, `panelScrim` — see *Glass* below: the panel colour drawn over the user's
  background image, instead of the upstream behaviour where the image *is* the panel.
- `showKeyHints` — whether the small hint labels/icons in the key corners are drawn at all.
  `KeyboardView` now requires **both** the user's `PREF_SHOW_HINTS` and the theme's
  `showKeyHints`. For this theme it is `false`, so no hint glyph appears in any key corner,
  and because hint visibility also decides whether an off-centre key label is drawn in the
  corner rather than centred, key labels come out centred — which is what the design shows.
  This is a `Colors` property rather than a settings key on purpose: it is a property of the
  design, not of the user, and it must not leak into other themes or into the settings UI.

## Soft edges (edge shading, top highlight)

The design gets its soft edges from CSS `box-shadow`s: `inset 0 1px 0 #ffffff25` for the hairline
highlight inside a key's top edge, and a dark shadow around the panel and under the keys. Two
platform facts rule out the obvious ways of doing that on Android:

- A hardware-accelerated canvas cannot draw a shadow *for a shape* (`Paint.setShadowLayer` is text
  only), so the design's shadows cannot be painted as shadows.
- The key surfaces are painted by *tinting* a drawable with the theme's key colour, and a tint can
  only multiply (for glass keys: only replace) that colour. It can never produce anything lighter
  than the surface, so a baked-in highlight renders as the key colour itself and disappears.

So the shading is a **layer of its own**, drawn over the surface and never tinted. Keys and the
panel want different things from it, so there are two shapes:

- `SurfaceShading` (`latin/common/Colors.kt`) holds the colours and the corner radii. For the
  **panel** it builds one `GradientDrawable` — a soft `PANEL_EDGE_SHADE` falling off from the lower
  edge, clipped to the panel's own rounded corners — and the panel gets it as a layer of its own
  composition.
- A **key** is flat: its surface is one translucent fill, and the design puts only a faint hairline
  inside its top edge. `KEY_EDGE_SHADE` is therefore `0`, and the key's fill drawable is a plain
  white tinted by the key colour (see *How the gradients work*). The hairline is one design pixel
  tall, which a `GradientDrawable` cannot draw — it spreads its stops over the whole surface, which
  is the bevel the design removes — so it is `TopEdgeHighlightDrawable`: a `KEY_TOP_HIGHLIGHT`
  rectangle, clipped to the key's rounded corners so it never pokes out beside them.
- `KeyboardView` builds the key layer once per theme in its constructor (like the key background
  drawables themselves) and draws it over every keycap whose surface really is that rounded
  rectangle: character, functional, action and spacebar keys, popup keys, the more-suggestions
  words, and the symbols/numbers and alternate-language layouts. It skips the surfaces that are a
  different shape altogether — the emoji grid, which has no key surface at all, and the *oval*
  action key of the long-press popup.

## Geometry: what was changed and what was left to the user's settings

Nothing in the layout was changed for this theme. Everything the brief asked for under spacing and
radii is either already the design's value or an existing HeliBoard setting, and HeliBoard's
settings are the right place for it because they are per-layout-type and per-orientation:

| Design intent | Where it lives | Status |
| --- | --- | --- |
| key corner radius 9dp | `btn_keyboard_key_*_lxx_base_border.xml`, `btn_keyboard_key_popup_lxx_base.xml`, `btn_keyboard_key_action_normal_lxx_base.xml` | already 9dp everywhere a keycap is drawn, including popup keys |
| panel / strip top radius 28dp | `keyboard_background_lxx_base_border.xml`, `keyboard_suggest_strip_lxx_base_border.xml` | already 28dp |
| key height | *Appearance → Keyboard height scale* (`prefs_keyboard_height_scale`) | user setting, per layout type and orientation |
| row / key gaps | *Appearance → Key gap scale* (`prefs_key_gap_scale`) | user setting; scales `horizontalGap`/`verticalGap` of the style |
| bottom row height | *Appearance → Bottom row scale* (`prefs_bottom_row_scale`) | user setting |
| bottom padding | *Appearance → Bottom padding scale* (`prefs_bottom_padding_scale`) | user setting |
| side padding | *Appearance → Side padding scale* (`prefs_side_padding_scale`) | user setting |
| suggestion strip height | `config_suggestions_strip_height` (40dp; 36dp in landscape) | matches the design's 45px on a 393px-wide panel |
| suggestion areas | `config_center_suggestion_percentile` (36%) + `config_suggestions_count_in_strip` (3) + subtle dividers | three areas, as the design's `repeat(3, 1fr)`; the design is the thirds case of a percentile that also has to serve one- and two-word strips |

The one geometry-adjacent thing that *is* theme-local is the divider: the rules between the three
suggestion areas are now the palette's `DIVIDER` (`#30FFFFFF`) instead of the previous fixed
`#18FFFFFF`, via the `dividerColor` hook described above. The divider drawable's size (1dp × 24dp)
is unchanged, so the strip still measures and lays out exactly as before.

Two things the design shows that HeliBoard's layout does not have were left alone deliberately:
the mock's bottom row is five keys (utility, mode, globe, space, return), while HeliBoard's is a
nine-key row that also carries the emoji/numpad/language switch keys, and the mock's strip spans
its three areas with no edge keys while HeliBoard puts the toolbar/mic keys at the strip's ends.
Both are layout decisions rather than styling, and both change what the keys do, so they are not
part of a restyle.

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

## How the key fill is painted

`Colors` paints every key by tinting a drawable with a single colour using
`PorterDuff.Mode.MULTIPLY` (see `setColor` in `latin/common/Colors.kt`). MULTIPLY *multiplies* rather
than replaces, so a **greyscale** image baked into a drawable is scaled by the tint. That is how a
drawable can carry a two-tone ramp, and why any ramps live in the drawables as greyscale values
rather than as the design's colours.

The design's key is **flat** — one translucent fill, its shape coming only from the radius and the
hairline (see *Soft edges*) — so the key drawables are flat white and a tint alone decides the key
colour:

```
drawable   #FFFFFF
tint       #56585F  ×
rendered   #56585F          <- the design's key colour, one flat fill
```

The suggestion strip is the one surface that still carries a ramp, and only while no background
image is set (with an image, the strip's own background is transparent so the image shows through).
It uses three stops: white (1.0), `#A8A8A8` (0.66) at 45% height, then `#DEDEDE` (0.87). Tinting
with `#2c2f38` reproduces the design's `#454957 → #202127 → #27282e` ramp.

The panel *behind the keys* is not drawn from `keyboard_background_lxx_base_border.xml` at all:
`KeyboardView.setKeyboard` replaces the main keyboard view's XML background with
`Color.TRANSPARENT` ("actual background color/drawable is applied to main_keyboard_frame"), and
`InputView` gives `main_keyboard_frame` a white background carrying the `background` colour as a
`MODULATE` filter. So the panel behind the keys is one flat colour — or the user's background image,
composited under the panel colour, when one is set. The `keyboard_background_lxx_base_border.xml`
restyle still matters for the emoji *search* keyboard, which does draw that drawable.

This is why the drawables hold greyscale values and not the design's colours: **changing a
theme's colours must not require editing the drawables, and editing a drawable changes
every theme.** If you want to retune the strip's ramp, edit its greyscale stops; if you want to
retune the hue of anything, edit the palette in `KeyboardTheme.kt`.

## Glass: real translucency for this theme only

The design composites the keyboard over the app behind it. `PorterDuff.Mode.MULTIPLY`
cannot express that, no matter what alpha the tint carries: it composites alpha as
`Sa + Da - Sa*Da`, so an opaque drawable stays opaque. Real translucency therefore needs a
different compositing mode, and it is opt-in per theme:

- `GlassAlphas` (in `latin/common/Colors.kt`) is a per-key alpha bag passed to
  `DefaultColors`. **Only `THEME_IOS27` passes one**; every other theme — and every
  user-defined theme — leaves it `null` and takes the untouched `MULTIPLY` path, so they
  render bit-for-bit as before.
- Keys / functional keys / spacebar / action keys / popup keys are tinted with
  `PorterDuff.Mode.SRC_IN` instead. `SRC_IN` composites as `Sa * Da`, so the tint's alpha
  becomes real alpha, and the drawable's own alpha channel masks it — which is what keeps the
  rounded corners and the transparent "empty key" state.
- The **panel** is translucent too, but through the palette rather than through `GlassAlphas`:
  `panelOverImage` states the panel colour that is painted *over* the user's background image, so
  the panel becomes `image × panel colour` instead of the image itself. When there is no image,
  the panel is that same translucent colour on its own. `panelScrim` can add one more dim layer
  in between, independently.
- Alphas are **scaled, not overwritten**: a colour that already carries alpha keeps its
  relative transparency, so a theme that states a deliberately faint colour is not made
  opaque by the glass layer.

Alphas used — these are the palette's own, and `KEY_ALPHA` scales them:

| Surface | Alpha (`KEY_ALPHA = 255`) |
| --- | --- |
| character keys / spacebar | `0x66` (`#665B5F68`) |
| character keys, pressed | `0x99` (`#997D828D`) |
| functional keys | `0x99` (`#992B2D35`) |
| action key | `0xFF` (`#FF626570`, opaque by design) |
| panel | `0xA0` (`#CC202127` scaled by `PANEL_ALPHA` 200) |

### What you get, and what you give up

With a **keyboard background image** set (HeliBoard's own feature, *Appearance → Keyboard
background image*) the effect is the one the design wants: the image sits behind a translucent
slab, the keys are genuinely translucent panes over that, the suggestion strip draws nothing of
its own (`Colors` makes it fully transparent whenever a background image is set) so it is simply
part of the panel. The text stays fully opaque, because the translucency lives in the key
*background* drawable, not in the view.

Trade-offs, stated plainly:

1. **Keys are flat by design.** `SRC_IN` takes only the tint's colour, so each key is one
   translucent colour per state. That now matches the design instead of fighting it: the key
   drawables are flat white (see *How the key fill is painted*), the bevel is gone, and the only
   thing drawn on top of a key is the faint top hairline. Bringing an RGB ramp back to keys would
   mean editing a drawable that every other theme shares, so it is deliberately not done.
2. **Panel without a background image.** With no image the panel is `#CC202127` scaled by
   `PANEL_ALPHA` (200, i.e. `#A0202127`), over the IME window's background — a platform colour this
   app does not declare and cannot predict. The design assumes the backdrop is the user's
   (pre-blurred) image: set one, or raise `PANEL_ALPHA` towards 255 for more opaque material, or add
   a `SCRIM_INTENSITY`, if the panel reads wrong against a particular backdrop.
3. **The panel dims the image.** The panel colour is painted over the image, so the image is
   visible but dimmed (that is the "frosted slab" the design shows). To see the image at full
   strength, raise the panel colour's alpha towards `#00202127` — the single entry `PANEL`.
4. **Only the keyboard view.** The emoji and clipboard panels declare
   `android:background="@android:color/transparent"` in their layouts, so they were never
   coloured by the panel path and are unaffected either way; they take the panel colour because
   it shows through from behind them.
5. **API floor.** `SRC_IN` composites the same way from API 21 up. `BlendModeColorFilterCompat`
   is still used for the key icons and text, which is where the tint alpha actually has to be
   honoured on old APIs. The panel does not use a colour filter at all, so it has no API
   dependence.

To go back to fully opaque keys, set `KEY_ALPHA` to 255 and the alphas inside `KEY`,
`PRESSED`, `MODIFIER` and `ACTION` to `FF`; to go back to a fully opaque panel, set the alpha inside
`PANEL` to `FF` and `PANEL_ALPHA` to 255. Everything is in `Ios27Palette`.

## Background image

HeliBoard has its own *Appearance → Keyboard background image* setting, and this theme uses
it as its material: with an image set the panel **is** that image, and the keys are
translucent panes over it (see *Glass* above).

**The panel runs under the navigation bar.** The system does not inset the input method window for the
navigation bar at the bottom — it lays the window out over it (the nav bar inset is fitted on the left,
top and right only) — but SystemUI draws an *opaque, translucent* navigation bar window on top of the
window's bottom strip, which would cut the panel off. With a background image set the theme therefore
asks for a **transparent** navigation bar and turns contrast enforcement off
(`panelUnderNavigationBar` in `latin/common/Colors.kt`, used by `setNavigationBarColor` in
`latin/LatinIME.java`), so what shows under the gesture pill is the panel's own image. The colour the
bar would otherwise have still decides whether the pill is drawn light or dark. Without an image the
bar keeps the flat panel colour, every other theme is untouched, and below Android 10 — where contrast
enforcement cannot be turned off — the coloured bar is kept as the fallback.

Nothing has to be re-laid-out for this: the nav bar inset is zero at the bottom on the devices checked,
so the keys already sit above the bar and their touch targets do not move; only the panel, which
already reached the bottom of the screen, stops being covered.

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

# HiLight Studio design system

## Direction

- Pixel-native, candid, and experimental.
- Explain privileged access, privacy consequences, safety limits, and live state where they matter.
- Keep Material controls familiar; hardware effects provide the colour and personality.

## Tokens

- Use Material You dynamic colour first.
- Fallback light background `#FEF7FF`, surface `#F2ECF4`, primary `#5B3FBF`.
- Fallback dark background `#121116`, surface `#1E1D22`, primary `#B69DFF`.
- Roboto/system Material 3 typography, 14sp body and 16sp medium-weight title.
- Spacing: 6dp, 10dp, 16dp, 18dp. Rounding: 10dp, 16dp, 22dp, 28dp, 36dp.

## Components

- Reuse `PixelCard`, `SectionTitle`, `Caption`, Material buttons, and existing dialogs.
- Permission disclosures use the standard extra-large `AlertDialog`, direct copy, one continuation
  action, and a clear `Not now` exit.
- Never rely on colour alone for access or renderer status.

## Motion and accessibility

- Motion communicates state in 150 to 250ms; no decorative choreography.
- Preserve Material touch targets, system theme, dynamic colour, and English/Japanese parity.

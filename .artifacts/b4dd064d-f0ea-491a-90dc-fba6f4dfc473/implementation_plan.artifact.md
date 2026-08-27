# Fix missing app icon and logo resources

The project is failing to build because `AndroidManifest.xml` and the Kotlin source code refer to `ic_hilight_studio` and `ic_hilight_logo` resources, but the actual files in `res/` are named using the default `ic_launcher` names.

## Proposed Changes

### Resource Renaming

I will rename the following resources to match the project's expectations:

1.  **Adaptive Icon Foreground**: Rename `app/src/main/res/drawable/ic_launcher_foreground.xml` to `ic_hilight_logo.xml`. This will also satisfy the code references to `R.drawable.ic_hilight_logo`.
2.  **Adaptive Icon Background**: Rename `app/src/main/res/values/ic_launcher_background.xml` color name to `ic_hilight_background` (optional but cleaner).
3.  **App Icons (Mipmap)**:
    *   Rename `ic_launcher` to `ic_hilight_studio` in all `mipmap-*` directories.
    *   Rename `ic_launcher_round` to `ic_hilight_studio_round` in all `mipmap-*` directories.

### Manifest and Resource Updates

#### [MODIFY] [AndroidManifest.xml](file:///C:/Users/luke-/AndroidStudioProjects/hilight-studio/app/src/main/AndroidManifest.xml)
Update `android:roundIcon` to use `@mipmap/ic_hilight_studio_round`.

#### [MODIFY] [ic_hilight_studio.xml](file:///C:/Users/luke-/AndroidStudioProjects/hilight-studio/app/src/main/res/mipmap-anydpi-v26/ic_hilight_studio.xml) (Renamed from ic_launcher.xml)
Update references to `ic_launcher_foreground` and `ic_launcher_background` to use the new names.

#### [MODIFY] [ic_hilight_studio_round.xml](file:///C:/Users/luke-/AndroidStudioProjects/hilight-studio/app/src/main/res/mipmap-anydpi-v26/ic_hilight_studio_round.xml) (Renamed from ic_launcher_round.xml)
Update references to `ic_launcher_foreground` and `ic_launcher_background` to use the new names.

## Verification Plan

### Automated Tests
- Run `./gradlew :app:processDebugResources` to verify that resource linking now succeeds.
- Run a full build `./gradlew assembleDebug`.

### Manual Verification
- Deploy the app to a device/emulator to verify that the icon is correctly displayed.

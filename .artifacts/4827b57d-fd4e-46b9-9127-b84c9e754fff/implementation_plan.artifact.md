# Implementation Plan - Firestick-Safe Home Trailer Preview Fix

Ensure that the Home trailer preview implementation is safe for Fire TV devices by protecting against `YouTubePlayerView` initialization failures and ensuring true lazy initialization.

## User Review Required

> [!IMPORTANT]
> The fix introduces a session-wide `youtubeFailed` state in the `HomeScreen`. If initialization fails once (e.g., due to a missing or ancient WebView on a Firestick), the app will fallback to static artwork for all subsequent inline previews in that session to prevent crash loops.

## Proposed Changes

### Home Screen Component

#### [MODIFY] [HomeScreen.kt](file:///C:/Users/bronb/StudioProjects/GoatTV/app/src/main/java/tv/own/owntv/features/home/HomeScreen.kt)

- Add a session-level `youtubeFailed` state using `remember { mutableStateOf(false) }`.
- Update `HeroPreviewSurface` to:
    - Accept `youtubeFailed` and `onYoutubeFailed` parameters.
    - Use `runCatching` during `YouTubePlayerView` construction within a `remember` block.
    - Handle construction and initialization failures gracefully by calling `onYoutubeFailed`.
    - Ensure `YouTubePlayerView` is released correctly using `DisposableEffect` and `AndroidView.onRelease`.
    - Prevent `AndroidView` composition if `youtubeFailed` is true.
- Update `HeroRowSection` and `ExpandableRowSection` to pass the `youtubeFailed` state and handle failures.
- Verify that the 1.5s/3s dwell logic and `activeTrailerItem` resolution correctly defer `HeroPreviewSurface` composition.

## Verification Plan

### Automated Tests
- Run `gradlew :app:assembleStandardGoatDebug`
- Run `gradlew :app:assembleStandardFivestarDebug`
- Run `gradlew :app:lintStandardGoatDebug`

### Manual Verification
- Deploy to an NVIDIA Shield:
    - Verify inline trailers still play with audio.
    - Verify moving between rows releases previous players.
- Deploy to a Firestick (if available) or simulate failure:
    - Verify that if initialization fails, the app remains usable and shows the static backdrop/poster.
    - Verify that no crash loops occur.
- Verify that rapid scrolling does not instantiate previews (due to the 1.5s dwell).

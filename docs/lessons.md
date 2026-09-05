# Lessons from fixes

Paths are relative to `app/src/main/java/com/sungyoon/helper/`. Use `git show <commit>` for evidence; read only relevant entries.

- **Pointer coordinates and visibility.** Migrating coordinates before layout caused initial misalignment. `overlay/pointer/PointerOverlayController.kt` coordinates migration/collection with layout and cancels its delayed fallback on teardown; `PointerOverlayRootView.onSizeChanged` resyncs cached points (`33ff830`). `DraggablePointerView.onDraw` must draw the order label at zero tap radius; visual radius is separate from the touch hitbox (`1211307`, `0a5dc92`). Check first display, resize, and radius zero.

- **Panel sizing.** Panels replacing their own layout parameters broke the parent's `FrameLayout.LayoutParams` contract (`27b3c37`). `overlay/pointer/PointerOverlayRootView.kt` owns placement; preset/reservation panels size their children. Preserve bounded regular body heights and compact bottom padding (`b2a9824`, `5c369ae`). Check both panels in portrait/landscape and short windows, including footer reachability.

- **Korean input.** IME Done submitted preset names during composition. Preserve the composing-span guard and inline, single-line IME configuration in `overlay/pointer/PointerOverlayModalHostView.kt` (`2a67fa8`). Check Korean composition followed by Done.

- **Reservation resume.** Resuming replayed points from the beginning. Persist the next point offset in `data/ReservationRuntimeStore.kt`; normalize it against the current point count in `SungyoonHelperService.kt`. Clear it on new/stop/reset runs (`d0220c7`). Check pause/resume midway through a sequence and after changing the point count.

- **Update permission flow.** Download completion records pending installation permission; the update UI action opens Settings. `MainActivity.onResume` resumes installation and refreshes progress (`513f234`). Preserve guarded network-state access (`3c157ce`) and the process-level `didInitialUpdateCheck` in `MainActivity.kt` (`027d738`). Check offline launch, permission return, and cancellation without stale progress.

- **Release metadata.** `update/AppUpdateChecker.kt` requires an APK asset and a parseable version code. Include explicit `versionCode: <APK versionCode>` and `versionName: <app version>` lines in the release body (`979e1e2`). `AppUpdateManager.checkForUpdates` compares against `BuildConfig.DEV_VERSION_CODE`; lowering only the app version name cannot trigger an update. Check release metadata against the packaged version code when an update is missing.

- **Windows build startup.** A stale `JAVA_HOME` stopped the wrapper; non-ASCII workspace/temp paths caused JNI and loopback socket failures. Use an installed JDK and valid `GRADLE_USER_HOME`, launch from a temporary ASCII `subst` drive, and put `TEMP`/`TMP` on an ASCII path. Kotlin daemon path errors can use the compiler's fallback compilation. Keep overrides process-local and remove the drive afterward; avoid changing project settings to repair one machine.

# Issue #19: targeted root-cleanup test

Version `1.0.15-root-test.1`, version code `16`, GitHub package `com.hilight.studio`.
This is a test for the KernelSU/KernelSU Next upgrade failures reported in issue #19, not a general
release or a claim that affected hardware is fixed. The Play build is unchanged.

The cleanup scan now reads process arguments directly through Android's shell instead of spawning
one `tr` process per entry. Exact renderer identity, cooperative shutdown, the nine-second deadline,
and checks for another remaining helper are retained. Timeout errors identify the cleanup phase
without exposing arbitrary command output. Saved preferences and rule formats are unchanged.

## Evidence

- The new scan regression test failed before the fix and passed afterward.
- All 373 existing and root-fix unit tests passed; debug and optimized release builds passed.
- Release lint passed with zero errors. The 59 warnings did not include RootCommand or RootProcess.
- On Android API 37, the old scan timed out against 6,000 synthetic process entries; the new scan
  completed in 0.61 seconds. Through the app's actual command runner, the fixed scan took 826 ms.
- Android fixture tests retained exact-helper termination, duplicate-helper rejection, protection
  for unrelated reused PIDs, and rejection of an empty command line backed by app_process.
- The test-release tag `test/issue-19-root-cleanup-1.0.15-1` is deliberately outside the existing
  updater's version-tag format. A regression test confirms normal users stay on 1.0.14.

There is no affected rooted Pixel attached. These results establish a cleanup performance fix,
not confirmation of every KernelSU failure reported in the issue.

## Reporter test

Install the signed test APK over the existing **GitHub** app without uninstalling or clearing data.
Open the app and try Retry root. If it connects, check normal lighting and reconnecting after a
reboot. Report the installed previous version, Pixel model, root-manager version and result.
If it fails, copy the exact error and Setup > Copy LED diagnostics, removing private details.
The GitHub APK cannot update the separate Google Play package or reproduce that package's retained
state; Play-only users should mention this instead of uninstalling their app.

Do not replace the public 1.0.14 artifacts or roll out broadly until reporter feedback is reviewed.
Code 16 is used by this test; a subsequent GitHub APK must use a higher code to update it in place.

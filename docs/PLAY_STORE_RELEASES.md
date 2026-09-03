# Play Store release lane

The Play Store build is a product flavour of the same app, not a separate codebase.

## Durable workflow

1. Finish and push normal product work to GitHub `main`.
2. In the persistent `codex/play-store` worktree, fetch GitHub and merge `origin/main`.
3. Increase `versionCode`, confirm the release notes, and review new permissions and SDKs.
4. Run the Play tests, lint, release build, and merged-manifest policy checks.
5. Build the signed App Bundle with `:app:bundlePlayRelease` and record its SHA-256.
6. Test the bundle through Play Console's internal testing track before production rollout.
7. Commit and push the Play preparation to `codex/play-store` with the verification evidence.

## Intentional Play differences

- No in-app GitHub release checker.
- No `INTERNET` permission while the app has no other network feature.
- No `QUERY_ALL_PACKAGES`; the picker uses launcher visibility plus apps learned locally from
  notifications.
- Android backup is disabled so remembered chat names and device-specific rules do not enter cloud
  backup or device transfer.

Everything else remains shared unless a specific Play policy decision is documented here.

## Release boundary

A successful local build is not Play approval. Production readiness also requires accurate Play
Console declarations, an accessible hosted privacy policy, store listing assets, internal-track
installation on supported hardware, and approval from Google Play review.

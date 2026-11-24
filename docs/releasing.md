# Releasing

Releases ship `dev` to Google Play. `.github/workflows/release.yml` and `.github/release/` are
the release boundary: changes there need Dax's review (`.github/CODEOWNERS` and a ruleset).

## Branches

Feature branch → squash-merged PR into `dev`, the default branch. CI (`ci.yml`) must pass to
merge. `production` only moves by fast-forward (`git push origin dev:production`) and releases
nothing; both branches block deletion and force pushes.

## Cutting a release

`release.yml` runs from `dev` at noon and 6 p.m. Central with a patch bump. It skips when `dev`
hasn't moved since the last release or `ci.yml` hasn't passed on its commit yet (a run still
going waits for the next release time). To release now, or with a bigger bump:

```sh
gh workflow run release.yml --ref dev -f bump=patch   # or minor, major
```

A dispatch fails instead of skipping. Each run:

1. **plan** picks the version: the latest `vX.Y.Z` tag bumped by `bump`.
2. **build** builds the unsigned `:app:bundleRelease` with no secrets and reports the digest of
   its `SHA256SUMS`.
3. **publish** (environment `release`, allowed only on `dev`) checks out only `.github/release/`,
   verifies the bundle against that digest, signs it with the Play upload key
   (`sign-bundle.sh`), uploads it to Google Play internal testing and, when the
   `PLAY_OPEN_TESTING` variable of the `release` environment is `true`, puts the same build on open testing (`beta`)
   (`ship-play.sh`). Only then does it tag `v<version>` and create the GitHub release, so a
   failed run spends no version.

Both Play uploads refuse to commit while Google is reviewing earlier changes (Play's default
would cancel that review and start it again): the step fails and the track stays as it was. Once
Publishing overview shows no changes in review, re-run the failed jobs.

Report the tag, the workflow run and the Play version code.

## What a push to dev does

Runs `scripts/check.sh` and publishes the debug APK (`ai.opencode.mobilecode.debug`, installs
beside the Play build) as the rolling `dev-latest` GitHub pre-release for sideloading. Nothing
goes to Play.

## Versions

A release build's `versionName` is its version, from the latest tag and the bump. Other builds
are `0.1.0-<short sha>`. `versionCode` is `release.yml`'s run number plus 1000, so it only goes
up (Play rejects a code it has seen). Never bump either by hand. Re-running failed jobs keeps
the run number, so if Play already took that code, start a new run instead.

## Credentials

Nothing is stored in GitHub secrets. The `publish` job assumes
`arn:aws:iam::604439173067:role/github/opencode-android-release` over GitHub OIDC; the role
trusts only `release.yml` on `refs/heads/dev` in environment `release` (`anomalyco/secrets`).
It reads `opencode-android/release/*` from AWS Secrets Manager, step by step
(`with-secrets.sh`):

- `ANDROID_UPLOAD_KEYSTORE_BASE64` and `ANDROID_UPLOAD_KEYSTORE_PASSWORD`: the Play upload key
  (PKCS12), alias from the `release` environment's `ANDROID_UPLOAD_KEY_ALIAS` variable.
- `PLAY_SERVICE_ACCOUNT_JSON`: the Play publishing service account.

## Google Play

- Account Anomaly Innovations, Inc. (`5599481792709483137`), app **OpenCode**
  `ai.opencode.mobilecode` (same ID as iOS), Play Console app id `4973267939382667246`.
- Play App Signing holds the app signing key. CI signs uploads with the **upload key** (see
  Credentials). If it's lost or leaks, request an upload key reset in Play Console
  (App integrity → App signing) with a new key's certificate; nothing else changes.
- The Play upload uses `PLAY_SERVICE_ACCOUNT_JSON`: a key for
  `play-publisher@opencode-android-play.iam.gserviceaccount.com` (Google Cloud project
  `opencode-android-play` in the anoma.ly org), invited in Play Console with only *View app
  information* and *Release apps to testing tracks* on OpenCode, so it can't publish to
  production. Rotate it with `gcloud iam service-accounts keys create` and `put-secret-value` on
  `opencode-android/release/PLAY_SERVICE_ACCOUNT_JSON`, then delete the old key.
- Internal testing: email list **Anomaly team** (everyone on Anomaly's Slack, up to 100).
  Testers opt in once at https://play.google.com/apps/internaltest/4698009188042349382, then
  install OpenCode from Play. Add people by editing that list in Play Console (Testing →
  Internal testing → Testers).
- Internal testing has no Google review. Closed or open testing (like iOS's External group)
  needs the store listing, content rating, data safety form and privacy policy first.
- Open testing: in Play Console (Testing → Open testing) choose the countries and set up the
  testers page, finish the items above, then `gh variable set PLAY_OPEN_TESTING -e release --body true`.
  Unset it (`gh variable delete PLAY_OPEN_TESTING -e release`) to stop. Google reviews open testing
  releases, so a build can take a while to reach testers; a newer release replaces one still in
  review. Anyone opts in at https://play.google.com/apps/testing/ai.opencode.mobilecode.
- Until Google has reviewed the app once, Play only accepts draft releases outside internal
  testing ("Only releases with status draft may be created on draft app"). `play-promote.sh`
  then leaves the build as the open testing track's draft and the job warns: send it for review
  in Play Console (Publishing overview → Send changes for review). v0.2.2 (33) was the first,
  sent for review on 2026-11-08.


## Personal GitHub APK releases

This fork publishes non-debuggable, R8-optimized Release APKs as full GitHub releases. Its permanent package name is `ai.opencode.rocmobilecode`. Upstream's application ID and Google Play/AWS release workflow are unchanged. CI still runs the repository's debug checks, but this fork no longer uploads debug APKs or publishes `dev-latest`.

After the push CI on `dev` passes, dispatch:

```sh
gh workflow run github-apk.yml --repo roc198419/opencode-android --ref dev -f bump=patch
```

The plan chooses the next `vX.Y.Z` tag before the build. `OC_VERSION_NAME` is that semantic version, `OC_VERSION_CODE` is this workflow's run number plus 1000, and `OC_APPLICATION_ID` selects the personal package. Keep this workflow's run-number history; if replacing it, raise the offset above every previously published code.

The build job holds no signing secrets and runs `:app:assembleRelease` with the existing heap limits. It uploads the unsigned APK and R8 mapping. The publish job has no repository checkout. It verifies the artifact archive against the build job's SHA-256 output, checks the package/version and rejects debuggable APKs before reading the signing key. It then signs the APK, verifies its signature against the pinned certificate and checks 16 KB ZIP alignment. The full release includes the APK, `mapping.txt`, `SHA256SUMS` and `release.json` (version/code, package, minimum SDK, APK URL/size/hash, signing certificate and source commit). `release.json` supplies metadata for a future updater; the app does not yet check or install updates.

Signing configuration in this repository:

- Actions secrets: `APK_RELEASE_KEYSTORE_BASE64`, `APK_RELEASE_STORE_PASSWORD`.
- Actions variable: `APK_RELEASE_CERT_SHA256`.
- PKCS12 alias: `opencode-release`.
- Public certificate SHA-256: `176a82beabb79af4abcb73765a1fc1c962a6b8bb964b505455874907323b7172`.

The key and password must never be committed, uploaded as build artifacts or regenerated for routine releases. A protected local backup is under `%USERPROFILE%/.codex/signing/opencode-android-release/`: `release.p12`, `password.dpapi` and the public certificate. The password backup is encrypted with Windows DPAPI for the current Windows account; export it into a password manager from that account before migrating machines. Losing both usable copies of the signing identity prevents ordinary in-place upgrades.

The stable app can coexist with the historical `.debug` package. Its phone-local configuration is separate, so pair the computers once in the stable app; removing the old app is optional. Subsequent stable releases keep the same package and signing identity so they can replace the installed stable version without uninstalling it.

# Main APK publishing (implementation)

## Contract

Every push to `main` builds the pushed tip and publishes a downloadable debug APK
as a commit-specific GitHub prerelease. A multi-commit push builds its tip, as
standard push workflows do. No path filters or cancellation of older builds.
Main and same-repository PRs publish separate prereleases. This uses the existing Android
build and native preparation scripts; Play publishing and Wear APKs are excluded.
Pull requests targeting main build their exact head commit. Same-repository PRs
also create public PR prereleases tagged `pr-<number>-<head SHA>` for end-to-end
publication validation; fork PRs cannot run the release job. Main tags remain
`main-<SHA>`. PR releases remain available for inspection and never become latest stable.

Use Ubuntu 24.04, JDK 21, SDK 36/build-tools 36.0.0, Qwen NDK
27.0.12077973, and the Gradle-declared NDK 29.0.14206865.
Configure setup-android with `packages: platform-tools` to avoid its obsolete
default `tools` package, which caused the first hosted run to fail during setup.
Build `:app:assembleDebug` with two workers and a 2 GiB in-process Kotlin/Gradle heap. Set versionCode to
1000000 + GitHub run number to permit successive updates.

Use a newly generated, deliberately public CI-only debug key (standard Android
debug alias/password), stored as base64 in `.github/ci/debug.keystore.base64`.
It must never sign production packages. It provides continuity, not proof of
publisher identity. Users obtain APKs from this repository's releases.

Build with read-only repository permission, verify the APK signature, then pass
the APK and SHA-256 checksum to a separate release job with contents:write.
Release tags are `main-<full commit SHA>`; reruns replace assets on that release.
Releases remain prereleases and do not replace the latest stable release.

## Anti-patterns

- Do not publish unsigned APKs; verify with apksigner.
- Do not generate a new signing key per run; preserve the CI debug key.
- Do not cancel earlier commits; omit shared concurrency groups.
- Do not build moving main after checkout; use the triggering SHA.
- Do not publish failed builds; release depends on build success.

## Validation

Static checks: actionlint passes; publication gated to main or same-repository PRs; read-only build job;
commit-specific release target; no cancellation or path filters.
Integration checks: assembleDebug produces an APK; signing verification passes;
native Qwen library and sherpa libraries are packaged. On GitHub, check a main
push publishes both assets, a rerun reuses its release, and a feature push does
not publish without a PR. Same-repository PRs must create a public PR prerelease; fork
PRs must skip release creation. Every release job downloads its APK and checksum,
compares the downloaded checksum file to the built one, and verifies the APK
digest. This exercises asset upload and download before merging.

## Error handling

| Failure | Behavior |
| --- | --- |
| Native download/checksum/build or Gradle failure | Fail build; no release |
| Missing APK or invalid signature | Fail before artifact upload |
| Artifact upload/download failure | Fail job; no publication |
| GitHub release API failure | Fail visibly; rerun after recovery |
| Existing release on rerun | Replace assets with gh release upload --clobber |

## References

- [Workflow](../../../.github/workflows/android-apk.yml)
- [Build instructions](../../../README.md#qwen3-asr-17b-q4_k-local-build)
- [Native preparation](../../../tools/build-qwen-native.sh)
- [Release CLI](https://cli.github.com/manual/gh_release_create)

Clarity review: all 13 stream-coding checks pass; trigger, signing, build,
publication, exclusions and failures have explicit decisions (9/10).

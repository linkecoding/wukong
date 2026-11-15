# Signing and releasing Wukong

Current public version: **0.0.1**, Android version code **1**. Increase the version code for every subsequent public release. Keep the same signing identity so APK updates remain installable.

## Local signing

The maintainer's production key is outside the repository, in `~/Library/Application Support/Wukong/signing/`. Its PKCS12 keystore and private properties have owner-only permissions. The ignored root `signing.properties` points Gradle to that key. Back up the signing folder using an encrypted backup; losing it prevents updates to the existing installation identity.

On a fresh maintainer machine, `python3 scripts/generate-signing-key.py` creates an identity once and refuses to overwrite existing material. **Do not generate a new identity for each release.** Restore the existing key when moving machines.

```sh
./scripts/gradle-local.sh -PtargetAbi=arm64-v8a :app:assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

Release builds use R8 and resource shrinking. Without `signing.properties`, local release builds remain unsigned; the release workflow requires all signing secrets and fails if they are missing.

Debug and release APKs have different certificates. A debug installation cannot be updated in place with the release APK. The development phone retains its debug app and configuration; do not uninstall it just to test a production signature. Uninstalling removes local data and Keystore identities.

## GitHub Actions setup

Authenticate GitHub CLI with access to this repository and its Actions Secrets, then run:

```sh
python3 scripts/upload-signing-secrets.py
```

The script sends values to `gh secret set` through stdin. It does not print key contents or passwords. Repository secrets:

- `ANDROID_KEYSTORE_BASE64`: base64-encoded PKCS12 keystore.
- `ANDROID_KEYSTORE_PASSWORD`: store password.
- `ANDROID_KEY_ALIAS`: signing alias.
- `ANDROID_KEY_PASSWORD`: key password.

Base64 is a transport encoding; secrecy comes from encrypted GitHub Secrets. Never commit a base64 key, a plaintext password, or the private signing properties. Only trusted release tags use the production identity; pull request checks do not receive it.

## Publish

1. Complete device testing. Update `versionName`, increment `versionCode` in `app/build.gradle.kts`, and write `docs/releases/<version>.md`.
2. Push the reviewed commit to `main` and let **Android checks** pass.
3. Push the matching version tag, for example `git tag v0.0.1` then `git push origin v0.0.1`.
4. **Signed release** builds universal and arm64 APKs, verifies signatures and ELF/ZIP 16 KB alignment, then publishes them with `SHA256SUMS.txt`. Its tag must match the app version. Manual retries must select the existing version tag.

The workflow publishes APKs and checksums. It does not publish private signing material or model API keys. Existing releases are not overwritten silently; a retry that finds an already-published release requires inspection first.

References: [Android app signing](https://developer.android.com/studio/publish/app-signing), [GitHub encrypted secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets).

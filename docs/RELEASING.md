# Releasing

## The signing key

Releases are signed with one key, forever. Android only installs an update signed with the same key as the installed
app, so **losing the key means every user has to uninstall, losing their data, to get updates**. Keep two copies
somewhere safe and offline.

Locally the key lives in `.signing/smartphonex-release.jks`, and its passwords in `keystore.properties`. Both are ignored
by git and must never be committed. To make a new one, for a fork:

```
keytool -genkeypair -keystore .signing/smartphonex-release.jks -storetype PKCS12 -alias smartphonex \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=SmartPhoneX"
```

and write `keystore.properties`:

```
storeFile=.signing/smartphonex-release.jks
storePassword=...
keyAlias=smartphonex
keyPassword=...
```

For GitHub Actions, add these repository secrets: `SPX_KEYSTORE_BASE64` (`base64 -w0 .signing/smartphonex-release.jks`),
`SPX_KEYSTORE_PASSWORD`, `SPX_KEY_ALIAS` and `SPX_KEY_PASSWORD`.

## Debug and release builds

Debug builds are signed with each developer's own debug key, so a release can't update a debug install, or the other
way round. Moving between them needs a reinstall. Keep your data with **Setup, Backup**:

1. Download a backup, with photos and video if you want them.
2. Uninstall, then install the other build.
3. Pair again, and restore the backup in **Setup, Backup**.

The phone's access token, AI key and relay link are never in a backup. Pair, enter the key and scan the relay's QR code again.

## Making a release

1. Set `spx.versionCode` (one higher) and `spx.versionName` in `gradle.properties`. Once the repository is public, set
   `spx.updateUrl` to its latest-release address, such as `https://api.github.com/repos/<owner>/<repo>/releases/latest`, so
   installed apps say when a release is out. Builds for other stores can leave it empty.
2. If saved files changed shape, add a migration to `AppData.migrations` and raise `AppData.CURRENT`, with a test.
3. Make sure CI is green.
4. Tag and push: `git tag v0.7.0 && git push origin v0.7.0`. The release workflow checks the tag matches the version,
   runs the tests, builds and signs the APK, verifies the signature, and attaches it to a GitHub release with its SHA-256.

A version with a `-`, such as `0.7.0-beta1`, is marked as a pre-release.

## Building one by hand

```
./gradlew assembleRelease
```

The signed APK is in `app/build/outputs/apk/release/`.

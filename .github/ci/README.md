# Public CI debug key

`debug.keystore.base64` is a **deliberately public, CI-only** signing key generated
for the main debug APK workflow. It is not a production credential. Alias:
`androiddebugkey`; store and key passwords: `android`.

Keeping this key stable lets users install subsequent CI APKs as updates. Do not
rotate it casually, use it for release signing, or replace it with a private key.
Anyone can sign with it: obtain these development builds only from the trusted
repository's Releases page. Production signing keys must remain private.

The workflow decodes it into the runner's standard Android debug-keystore path.
Existing locally signed debug installs may require uninstalling first (losing
their app data). The production application uses a different package ID.

# Third-party notices

## Shizuku API (13.1.5)

The four small JARs in [`app/libs/`](app/libs/) contain classes extracted from
the official Shizuku API AARs published on Maven Central:

| File | Maven artifact |
|---|---|
| `api.jar` | `dev.rikka.shizuku:api:13.1.5` |
| `aidl.jar` | `dev.rikka.shizuku:aidl:13.1.5` |
| `shared.jar` | `dev.rikka.shizuku:shared:13.1.5` |
| `provider.jar` | `dev.rikka.shizuku:provider:13.1.5` |

- Project: <https://github.com/RikkaApps/Shizuku> · <https://github.com/RikkaApps/Shizuku-API>
- License: **Apache License 2.0** — <https://www.apache.org/licenses/LICENSE-2.0>
- Copyright: RikkaApps and contributors.

Only the compiled classes are redistributed so that `build.sh` can produce an
APK without Gradle or network access. No source files were copied.

In accordance with the upstream project's requirements, this app does **not**
use "Shizuku" as its app name, does not use `moe.shizuku.privileged.api` as its
application id, does not reuse Shizuku's launcher icons, and does not define
any `moe.shizuku.manager.permission.*` permission of its own. It only requests
the official `API_V23` permission in order to receive the Shizuku binder, and
declares its own `ShizukuProvider` authority (`com.zoomtap.app.shizuku`) as
described by the Shizuku API documentation.

The full Apache-2.0 license text is available at the URL above and is
incorporated here by reference.

## Android SDK / AOSP

`build.sh` links against `android.jar` and uses `aapt2`, `d8`, `zipalign` and
`apksigner` from the Android SDK build-tools. These are build-time tools only;
none of them are redistributed in this repository or in the APK. The Android
Open Source Project is licensed primarily under Apache-2.0:
<https://source.android.com/setup/start/licenses>.

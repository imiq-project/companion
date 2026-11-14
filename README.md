# Companion

> **Model documentation:** [HOTCO MODEL](app-backend/model/README.md).

Android mobility companion and the Python backend needed to reproduce the current
local USB test setup. Current app: **0.4.157 / versionCode 168**;
Android package: `de.ovgu.imiq.companion` (Android 8.0 / API 26 or later).

This publication is an **internal testing distribution**. The phone connects to
the backend on your computer through USB. Publishing this repository does not
deploy a university server or configure a public Companion API.

## Install the APK

Download the APK and `SHA256SUMS.txt` from the repository's
[Releases](https://github.com/imiq-project/companion/releases).
Enable USB debugging on the Android phone, connect it, accept the authorization
prompt, and install Android SDK Platform Tools on the computer.

```sh
adb devices
adb -s YOUR_DEVICE_SERIAL install -r Companion-0.4.157-usb-debug.apk
adb -s YOUR_DEVICE_SERIAL reverse tcp:8077 tcp:8077
adb -s YOUR_DEVICE_SERIAL shell am start -n de.ovgu.imiq.companion/.MainActivity
```

Keep the phone connected to the computer while testing. Repeat `adb reverse`
after reconnecting or rebooting. If you have multiple devices, always select the
intended serial. The computer must also run the backend described below.

The supplied APK is a debug-signed testing build, not a store release. Locally
compiled debug builds may use another signing key and therefore cannot update
an existing installation signed by someone else. Do not uninstall an existing
app if you need its local data; coordinate the signing key or use a separate
test device. No signing key is included in this repository.

## Run the backend locally

Requirements: Python 3.13, Java 21 or later for OpenTripPlanner, network access
to the project's public routing/context services, and enough disk space for the
downloaded transit data and graph (at least the bootstrap's 2 GB free-space check).
No provider credentials, Atlas URI, participant records or prebuilt transit graph
are bundled.

```sh
git clone https://github.com/imiq-project/companion.git
cd companion
python -m venv .venv
```

Activate the environment (`.venv\Scripts\Activate.ps1` in PowerShell, or
`source .venv/bin/activate` on Linux/macOS), then:

```sh
python -m pip install -r app-backend/requirements.txt
```

Copy `deploy/local.env.example` to `app-backend/.env`. The example binds only to
`127.0.0.1:8077`; keep that setting for USB testing. Panel login is enabled against
`https://imiq-staging.et.uni-magdeburg.de` with study identifier `app_imiq_test`.
Enter your authorized panel account in Android. Do not put your account password
or another participant's credentials in a repository file.

For the complete public-transport setup, bootstrap the public datasets and start
the API, OpenTripPlanner and realtime proxy:

```sh
python companion.py data transit bootstrap
python companion.py ops start
python companion.py ops status
```

Bootstrap downloads the OTP executable and public OSM/GTFS inputs into ignored
`runtime/` directories and builds the initial transit graph. It can take time and
requires network access. Subsequent data updates use:

```sh
python companion.py data transit sync
```

For API-only development, `python companion.py serve` starts the API in the
foreground; it does not start OTP or the realtime proxy. Public-transport routing
will need those services. Service/provider diagnostics are available through
`python companion.py check --services --providers`. These are operational probes,
not a bundled automated test suite. They contact configured services.

The routing, GraphHopper and Orion endpoint defaults are in the example config.
Their availability depends on the university services. Android can fall back to
its system geocoder when backend geocoding is unavailable. Optional LLM-generated
explanations require a separately authorized provider configuration; no API keys
are supplied.

## Optional local Atlas integration

Panel login and local app use can be configured independently of Atlas uploads.
The example leaves `COMPANION_PANEL_ATLAS_LOCAL_TEST=0`. To reproduce the current
owner-authorized staging/Atlas integration:

1. Install the optional driver:
   `python -m pip install -r deploy/requirements-mongodb.txt`.
2. Create `runtime/state/private/atlas.env` using `deploy/atlas.env.example` as a
   template. Supply your authorized Atlas database user, URI and database name
   locally, and configure Atlas network access for the computer running Python.
3. Update the following values in your private `app-backend/.env`, keeping the
   staging panel and `app_imiq_test` study, then restart the backend:

   ```dotenv
   COMPANION_PANEL_ATLAS_LOCAL_TEST=1
   COMPANION_PROFILE_UPLOAD_LOCAL_VALIDATION=1
   COMPANION_BLOCK04_LOCAL_TEST=1
   COMPANION_BLOCK05_LOCAL_TEST=1
   COMPANION_BLOCK06A_LOCAL_TEST=1
   COMPANION_BLOCK06B_LOCAL_TEST=1
   ```

   Replace any existing value for a variable; do not add conflicting duplicate
   definitions. The separate synthetic block-03 test endpoint stays disabled.

Passwords embedded in a URI must be URL-encoded. Neither the URI nor any Atlas
password belongs in Android or Git. The existing local integration is deliberately
restricted to loopback; do not expose it publicly by changing these checks.
The runtime adapter `infrastructure/panel_atlas_test.py` is required by the panel
and transfer routes; despite its historical name, it is not an automated test file.

This integration does not establish production study enrollment or consent.
Learning remains disabled. Confirmed profiles and supported search archives use
their existing transfer/outbox receipts; GPS/journey submission remains an
explicit action. Local deletion controls do not delete server-side records.

## Build Android from source

Open `frontend/` in a current Android Studio installation. Install Android SDK
Platform 36 and use a compatible JDK (Android Studio's bundled JDK is suitable).
Keep the repository layout intact: Gradle also bundles the public, versioned
resources under the sibling `contracts/` directory.

Windows, from `frontend/`:

```powershell
.\gradlew.bat assembleDebug
```

Linux/macOS, from `frontend/`:

```sh
./gradlew assembleDebug
```

The APK is generated at `frontend/app/build/outputs/apk/debug/app-debug.apk`.
The default backend URL is `http://localhost:8077`; use the USB reverse above.
Real devices need their own location permission, enabled location services and
an explicit user action to start recording a journey.

## Repository contents

- `frontend/`: Android runtime sources, bundled assets/resources and Gradle wrapper.
- `app-backend/`: Python API and runtime modules.
- `contracts/`: public schemas, model identities, emission factors and map/vehicle data.
- `operations/`: the CLI's service/transit lifecycle and operational diagnostics.
- `deploy/`: configuration examples, dependency declarations and service templates.

Automated tests, fixtures, model examples, phone-inspection tools, design drafts,
participant data, logs, caches, real environment files and signing keys are excluded
from this publication. Generated runtime files and APKs are not committed to Git;
the downloadable APK is published separately as a release asset.

The existing `legacy` branch is preserved. The former `master` snapshot is also
retained in `legacy-pre-0.4.157`. The updated `master` is a normal descendant of
that snapshot; its Git history is not rewritten.

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for component and data credits.
Project and university branding retain their respective usage terms; this
publication does not grant a new blanket license for the application or artwork.

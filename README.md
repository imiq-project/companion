<p align="left">
  <img src="frontend/app/src/main/assets/branding/imiq-ovgu-logo.png" alt="IMIQ — Otto von Guericke University Magdeburg" width="421">
</p>

# Companion

> **Model documentation:** [HOTCO MODEL](app-backend/model/README.md) — equations, inputs, numerical integration and reproducible synthetic figures.

**Understand your travel options. Choose a journey. Reflect on the experience.**

Companion is the native Android mobility companion of the **IMIQ — Intelligent
Mobility Space in the Quarter** project. It connects a personal travel interface
with a Python backend, a versioned five-goal model, and public mobility services
for Magdeburg.

This guide explains **what the app does, how its components work together, where
data is stored, and how colleagues can build and run it**.

| Application | Frontend | Backend | Local transport |
| --- | --- | --- | --- |
| **0.4.137 · code 148** | Kotlin · Jetpack Compose | Python · Flask | OpenTripPlanner 2.7.0 · MVB GTFS |
| `de.ovgu.imiq.companion` | Android 8.0+ · target API 36 | `/api/companion` | API **8077** · OTP **8091** · RT **8092** |

**Read first:** SQLite is the current **local** data-management mechanism, both
on Android and on the machine running the backend. Integration of data management
with the **project dashboard and project servers is in progress**. The current
app does not automatically upload GPS when a journey ends.

## Contents

- [Service overview](#service-overview)
- [System architecture](#system-architecture)
- [Android frontend](#android-frontend)
- [Python backend](#python-backend)
- [Journey lifecycle](#journey-lifecycle)
- [Public transport flow](#public-transport-flow)
- [Data management and project integration](#data-management-and-project-integration)
- [Repository map](#repository-map)
- [Getting started](#getting-started)
- [Configuration and operations](#configuration-and-operations)
- [Troubleshooting](#troubleshooting)
- [Public data and third-party notices](#public-data-and-third-party-notices)

---

## Service overview

Companion supports a complete local journey workflow: establish personal
priorities, compare travel alternatives, choose a route, record or review a
journey, and report the experience.

| Capability | What colleagues should know |
| --- | --- |
| Personal priorities | Five goals: **environmental impact, physical activity, cost, speed and comfort**. |
| Travel comparison | Walking, cycling, public transport and car alternatives depend on the configured providers and declared availability. |
| Context and maps | Route geometry, stops, departures, weather and estimated climate impact accompany the comparison. |
| Journey capture | A user-started Android foreground service can capture permitted location fixes; journeys without GPS are also supported. |
| Review and history | Search results, route choices, journeys and experience reports are held in local Android storage. |
| Validation and archive | **Data and validation → Validate with backend** explicitly sends a search and journey for backend validation and optional local archiving. |
| Visual companion | A native companion interface presents goals, travel feedback and reviewed explanations. |

### Current implementation and work in progress

| Area | Status in this version |
| --- | --- |
| Android app, Flask API and local transport tools | Implemented; connected functionality depends on provider and service availability. |
| SQLite on Android and on the backend host | Current local persistence. These are two separate databases. |
| Data management in the project dashboard and project servers | **Integration in progress**; not an available end-to-end flow in this version. |
| Automatic GPS synchronization at journey completion | Not implemented; the validation/upload action is manual. |
| Operational learning | **Disabled.** Experimental and shadow components do not enable production learning. |

The numerical model retains **five goals and thirteen states**. Parameters,
equations, factors and evidence contracts are versioned. Historical eleven-goal
material is not used as current profile input.

## System architecture

### Reading the diagrams

The diagrams use the official IMIQ palette: **Majorelle purple, Pale Blue and
OVGU Burgundy**, together with their light variants. Each component keeps the
same color throughout this guide. The visual legend below shows the colors;
the table explains their meaning.

```mermaid
%%{init: {"theme":"base","themeVariables":{"fontFamily":"Arial, sans-serif","primaryColor":"#D0C9E5","primaryTextColor":"#000000","primaryBorderColor":"#623CEA","secondaryColor":"#A5C4D4","tertiaryColor":"#FFFFFF","lineColor":"#623CEA","textColor":"#000000","edgeLabelBackground":"#FFFFFF","clusterBkg":"#FFFFFF","clusterBorder":"#623CEA"},"flowchart":{"htmlLabels":false,"curve":"linear","nodeSpacing":35,"rankSpacing":40,"wrappingWidth":220,"padding":18}}}%%
flowchart LR
    L1["Android"] ~~~ L2["Backend"] ~~~ L3["Provider"] ~~~ L4[("SQLite")] ~~~ L5["Contract"] ~~~ L6["User action"] ~~~ L7["In progress"]
    class L1 client;
    class L2 backend;
    class L3 provider;
    class L4 store;
    class L5 contract;
    class L6 action;
    class L7 planned;
    classDef client fill:#623CEA,stroke:#623CEA,color:#FFFFFF,stroke-width:2px;
    classDef backend fill:#D0C9E5,stroke:#623CEA,color:#000000,stroke-width:2px;
    classDef provider fill:#A5C4D4,stroke:#623CEA,color:#000000,stroke-width:2px;
    classDef store fill:#F4E9EF,stroke:#7A003F,color:#7A003F,stroke-width:2px;
    classDef contract fill:#FFFFFF,stroke:#623CEA,color:#623CEA,stroke-width:2px,stroke-dasharray:4 3;
    classDef action fill:#7A003F,stroke:#7A003F,color:#FFFFFF,stroke-width:2px;
    classDef decision fill:#FFFFFF,stroke:#7A003F,color:#7A003F,stroke-width:2px;
    classDef planned fill:#E4EBF2,stroke:#7A003F,color:#7A003F,stroke-width:2px,stroke-dasharray:6 4;
    linkStyle default stroke:#623CEA,stroke-width:1.5px;
```

| Component | Visual appearance | Meaning |
| --- | --- | --- |
| Android | Purple | App interface, state and device-side logic. |
| Backend | Light purple | Flask handlers and application services. |
| Provider | Pale blue | External services and separate transport processes. |
| Local storage | Light pink cylinder with a burgundy outline | Private SQLite database on the device or backend host. |
| Shared contract | White box with a dashed purple outline | Shared schema or versioned resource. |
| User action | Burgundy box | An explicit action taken by the user. |
| Decision | White diamond with a burgundy outline | A condition checked by the application. |
| Integration in progress | Light blue box with a dashed burgundy outline | Integration that is not yet operational. |

Arrows show the named interaction or dependency. A dashed **schema** connection
means a shared resource dependency; a dashed **in-progress** connection describes
work that is not yet operational. Labels and shapes carry meaning as well as color.

### Components and process boundaries

```mermaid
%%{init: {"theme":"base","themeVariables":{"fontFamily":"Arial, sans-serif","primaryColor":"#D0C9E5","primaryTextColor":"#000000","primaryBorderColor":"#623CEA","secondaryColor":"#A5C4D4","tertiaryColor":"#FFFFFF","lineColor":"#623CEA","textColor":"#000000","edgeLabelBackground":"#FFFFFF","clusterBkg":"#FFFFFF","clusterBorder":"#623CEA"},"flowchart":{"htmlLabels":false,"curve":"linear","nodeSpacing":35,"rankSpacing":40,"wrappingWidth":220,"padding":18}}}%%
flowchart TB
    subgraph DEVICE["ANDROID DEVICE"]
        APP["Companion Android<br/>Kotlin + Compose"]
        LOCAL[("On-device SQLite")]
        APP --> LOCAL
    end
    subgraph HOST["BACKEND HOST"]
        API["Flask API<br/>Port 8077"]
        ARCHIVE[("Backend-local SQLite")]
        TRANSIT["Local transport<br/>OTP: 8091<br/>RT proxy: 8092"]
        API -->|"Journey archive"| ARCHIVE
        API -->|"Transit plans and RT status"| TRANSIT
    end
    APP <-->|"Searches and explicit uploads"| API
    API --> UPSTREAM["External routing and<br/>context providers"]
    APP --> TILES["Map tiles<br/>and glyphs"]
    class APP client;
    class API backend;
    class LOCAL,ARCHIVE store;
    class TRANSIT,UPSTREAM,TILES provider;
    style DEVICE fill:#E4EBF2,stroke:#623CEA,color:#000000;
    style HOST fill:#FFFFFF,stroke:#623CEA,color:#000000;
    classDef client fill:#623CEA,stroke:#623CEA,color:#FFFFFF,stroke-width:2px;
    classDef backend fill:#D0C9E5,stroke:#623CEA,color:#000000,stroke-width:2px;
    classDef provider fill:#A5C4D4,stroke:#623CEA,color:#000000,stroke-width:2px;
    classDef store fill:#F4E9EF,stroke:#7A003F,color:#7A003F,stroke-width:2px;
    classDef contract fill:#FFFFFF,stroke:#623CEA,color:#623CEA,stroke-width:2px,stroke-dasharray:4 3;
    classDef action fill:#7A003F,stroke:#7A003F,color:#FFFFFF,stroke-width:2px;
    classDef decision fill:#FFFFFF,stroke:#7A003F,color:#7A003F,stroke-width:2px;
    classDef planned fill:#E4EBF2,stroke:#7A003F,color:#7A003F,stroke-width:2px,stroke-dasharray:6 4;
    linkStyle default stroke:#623CEA,stroke-width:1.5px;
```

Android, Flask, OTP and the RT proxy are separate runtime components. The
transport box in the overview groups two separate processes; the transport flow
below explains their schedule/realtime boundary. The
`contracts/` directory is shared source material, not another service. The two
SQLite databases belong to different hosts; neither establishes integration with
the project's central data infrastructure.

## Android frontend

The frontend is a native **Kotlin/Jetpack Compose** app. `MainActivity` connects
Android lifecycle events, permissions, imports/exports and tracking actions to
the UI. `CompanionModel` holds screen state and coordinates the app's operations.

```mermaid
%%{init: {"theme":"base","themeVariables":{"fontFamily":"Arial, sans-serif","primaryColor":"#D0C9E5","primaryTextColor":"#000000","primaryBorderColor":"#623CEA","secondaryColor":"#A5C4D4","tertiaryColor":"#FFFFFF","lineColor":"#623CEA","textColor":"#000000","edgeLabelBackground":"#FFFFFF","clusterBkg":"#FFFFFF","clusterBorder":"#623CEA"},"flowchart":{"htmlLabels":false,"curve":"linear","nodeSpacing":35,"rankSpacing":40,"wrappingWidth":220,"padding":18}}}%%
flowchart TB
    ACTIVITY["MainActivity<br/>Lifecycle and permissions"] --> UI["CompanionApp and Compose screens"]
    UI <-->|"Actions and screen state"| MODEL["CompanionModel<br/>AndroidViewModel"]
    MODEL --> NETWORK["Api<br/>HTTP JSON client"]
    NETWORK <-->|"Requests and responses"| API["Companion<br/>backend API"]
    MODEL --> TRIPS["TripRepository<br/>Journey transactions"]
    MODEL --> STORE["Store<br/>Profiles, settings and search history"]
    TRACK["TrackingService + TrackingEngine<br/>User-started capture"] --> TRIPS
    ACTIVITY -->|"Explicit start"| TRACK
    TRIPS --> STORE
    TRIPS -->|"TripUpdates flows"| MODEL
    STORE --> SQLITE[("Device-private SQLite")]
    CONTRACTS["Journey schemas and public assets"] -.-> TRIPS
    class ACTIVITY,UI,MODEL,NETWORK,TRIPS,STORE,TRACK client;
    class API backend;
    class SQLITE store;
    class CONTRACTS contract;
    classDef client fill:#623CEA,stroke:#623CEA,color:#FFFFFF,stroke-width:2px;
    classDef backend fill:#D0C9E5,stroke:#623CEA,color:#000000,stroke-width:2px;
    classDef provider fill:#A5C4D4,stroke:#623CEA,color:#000000,stroke-width:2px;
    classDef store fill:#F4E9EF,stroke:#7A003F,color:#7A003F,stroke-width:2px;
    classDef contract fill:#FFFFFF,stroke:#623CEA,color:#623CEA,stroke-width:2px,stroke-dasharray:4 3;
    classDef action fill:#7A003F,stroke:#7A003F,color:#FFFFFF,stroke-width:2px;
    classDef decision fill:#FFFFFF,stroke:#7A003F,color:#7A003F,stroke-width:2px;
    classDef planned fill:#E4EBF2,stroke:#7A003F,color:#7A003F,stroke-width:2px,stroke-dasharray:6 4;
    linkStyle default stroke:#623CEA,stroke-width:1.5px;
```

### Where to look in the frontend

All Kotlin modules below live in
[`frontend/app/src/main/java/de/ovgu/imiq/companion/`](frontend/app/src/main/java/de/ovgu/imiq/companion/).

| Responsibility | Main entry points |
| --- | --- |
| Activity and UI entry | `MainActivity.kt`, `CompanionApp.kt`, `CompanionModel.kt`. |
| Profile and priorities | `GoalPersonalizationUi.kt`, `GoalPriorities.kt`, `WeeklyNeedAllocation.kt`, `SituatedImportance.kt`. |
| Search and alternatives | `TravelPlanningUi.kt`, `RouteData.kt`, `RouteSummaryData.kt`, `RouteSummaryUi.kt`. |
| Maps and mobility context | `NativeMap.kt`, `VectorMapUi.kt`, `MapPlacesUi.kt`, `DepartureBoardData.kt`, `ClimateData.kt`. |
| Local persistence and HTTP | `Data.kt`: `Store`, SQLite helpers and `Api`. |
| Tracking and journey integrity | `TrackingService.kt`, `TrackingEngine.kt`, `TripRepository.kt`, `JourneyContract.kt`, `TrackingLocationQuality.kt`. |
| Experience and review | `ExperienceUi.kt`, `CompactExperienceUi.kt`, `HistoryDetails.kt`, `JourneyContextCapture.kt`. |
| Visual companion and assets | `CompanionUi.kt`, companion/scene UI modules, `assets/art/`, `assets/branding/` and `res/`. |

The app uses the local SQLite tables **`kv`** and **`searches`**. Search documents
include choices and nested journeys. Recording a journey changes local state;
it does not by itself create an archived journey on the backend.

**Shared assets:** Gradle also packages the sibling `contracts/` directory.
Android's asset packaging expands `.json.gz` catalogue sources into `.json`
assets. Keep `frontend/` and `contracts/` in their repository-relative locations.

## Python backend

[`application.py`](app-backend/application.py) creates the Flask app and registers
the API modules. Infrastructure code handles request limits, authentication when
configured, response transport and service settings. Domain modules validate and
process their own inputs.

```mermaid
%%{init: {"theme":"base","themeVariables":{"fontFamily":"Arial, sans-serif","primaryColor":"#D0C9E5","primaryTextColor":"#000000","primaryBorderColor":"#623CEA","secondaryColor":"#A5C4D4","tertiaryColor":"#FFFFFF","lineColor":"#623CEA","textColor":"#000000","edgeLabelBackground":"#FFFFFF","clusterBkg":"#FFFFFF","clusterBorder":"#623CEA"},"flowchart":{"htmlLabels":false,"curve":"linear","nodeSpacing":35,"rankSpacing":40,"wrappingWidth":220,"padding":18}}}%%
flowchart TB
    REQUEST["Android HTTP request"] --> API["Flask API and infrastructure<br/>Validation, limits and admission"]
    API --> SEARCH["Search coordination<br/>services/"]
    API --> JOURNEY["Journey validation<br/>journeys/"]
    SEARCH --> PROFILE["Declared profile<br/>passport/"]
    PROFILE --> MODEL["Signed HOTCO model<br/>Five goals, thirteen states"]
    SEARCH --> ROUTING["Routing, context and climate<br/>routing/ · context/ · climate/"]
    ROUTING --> PROVIDERS["OTP and configured providers"]
    MODEL --> RESULT["Versioned results and evidence"]
    ROUTING --> RESULT
    RESULT --> EXPLAIN["Checked explanations<br/>explanations/"]
    JOURNEY --> DB[("Backend-local SQLite<br/>Commit before archive receipt")]
    CONTRACTS["Shared schemas, factors and model parameters"] -.-> API
    class REQUEST client;
    class API,SEARCH,JOURNEY,PROFILE,MODEL,ROUTING,RESULT,EXPLAIN backend;
    class PROVIDERS provider;
    class DB store;
    class CONTRACTS contract;
    classDef client fill:#623CEA,stroke:#623CEA,color:#FFFFFF,stroke-width:2px;
    classDef backend fill:#D0C9E5,stroke:#623CEA,color:#000000,stroke-width:2px;
    classDef provider fill:#A5C4D4,stroke:#623CEA,color:#000000,stroke-width:2px;
    classDef store fill:#F4E9EF,stroke:#7A003F,color:#7A003F,stroke-width:2px;
    classDef contract fill:#FFFFFF,stroke:#623CEA,color:#623CEA,stroke-width:2px,stroke-dasharray:4 3;
    classDef action fill:#7A003F,stroke:#7A003F,color:#FFFFFF,stroke-width:2px;
    classDef decision fill:#FFFFFF,stroke:#7A003F,color:#7A003F,stroke-width:2px;
    classDef planned fill:#E4EBF2,stroke:#7A003F,color:#7A003F,stroke-width:2px,stroke-dasharray:6 4;
    linkStyle default stroke:#623CEA,stroke-width:1.5px;
```

### Backend module map

| Folder | Responsibility |
| --- | --- |
| [`infrastructure/`](app-backend/infrastructure/) | Environment/settings validation, HTTP safeguards, concurrency, compression and identifier compatibility. |
| [`services/`](app-backend/services/) | Coordinates profile/model calls and route searches across domain modules. |
| [`passport/`](app-backend/passport/) | Declared needs, priorities, profile preparation and versioned Passport logic. |
| [`model/`](app-backend/model/) | Signed HOTCO dynamics, input mapping, readout, diagnostics and robustness. |
| [`routing/`](app-backend/routing/) | Route providers, OTP integration, geometry, stops, departures, parking, geocoding and map APIs. |
| [`context/`](app-backend/context/) | Weather and environmental observations with provider provenance. |
| [`climate/`](app-backend/climate/) | Factor-based climate estimates and vehicle profile handling. |
| [`journeys/`](app-backend/journeys/) | Journey/evidence contracts, validation, identity checks and local SQLite archiving. |
| [`explanations/`](app-backend/explanations/) | Saved evidence projection, reviewed wording, reflection and optional narration. |
| [`comfort_pilot/`](app-backend/comfort_pilot/) | Versioned longitudinal/experimental APIs and shadow components; operational learning remains disabled. |
| [`artifacts/`](app-backend/artifacts/) | Small public model metadata; not participant records or generated reports. |

Context is archived as **observations** under the current public policy. It does
not add external forcing to HOTCO dynamics. Climate estimates and explanatory
wording do not change the model's equations or gains. Explanations use saved
evidence; optional narration is not a substitute for the numerical result.

### Key HTTP boundaries

| Endpoint | Purpose |
| --- | --- |
| `GET /health/live` | API process liveness. |
| `GET /health` and `GET /health/ready` | Model/service metadata and readiness information; evaluate the reported status as well as HTTP success. |
| `POST /api/companion/goal-personalization/prepare` | Prepare the declared goal/priorities profile. |
| `POST /api/companion/routed-contextual-deliberation` | Compute a route comparison with versioned model results and context evidence. |
| `GET /api/companion/journeys/contract` | Obtain the journey contract. |
| `POST /api/companion/journeys/validate` | Validate explicitly submitted evidence and archive it if storage is enabled. |
| `/api/companion/longitudinal/...` | Versioned evidence and experimental endpoints; endpoint availability does not enable production learning. |

This is an orientation map, not the full API specification. The registered
handlers and [`contracts/`](contracts/) define the actual payloads and checks.

## Journey lifecycle

1. **Declare and search:** the user supplies priorities, availability, origin,
   destination and departure information; the backend returns alternatives.
2. **Choose and capture:** the chosen itinerary is saved locally. A user can
   start GPS tracking with permissions, or use a journey flow without GPS.
3. **Review:** the user finishes/reviews the journey and confirms the experience;
   Android keeps the search, journey and related evidence locally.
4. **Validate/upload explicitly:** only the manual validation action submits the
   search and journey to the backend archive endpoint.

```mermaid
%%{init: {"theme":"base","themeVariables":{"fontFamily":"Arial, sans-serif","primaryColor":"#D0C9E5","primaryTextColor":"#000000","primaryBorderColor":"#623CEA","secondaryColor":"#A5C4D4","tertiaryColor":"#FFFFFF","lineColor":"#623CEA","textColor":"#000000","edgeLabelBackground":"#FFFFFF","clusterBkg":"#FFFFFF","clusterBorder":"#623CEA"},"flowchart":{"htmlLabels":false,"curve":"linear","nodeSpacing":35,"rankSpacing":40,"wrappingWidth":220,"padding":18}}}%%
flowchart TB
    CHOICE["Choose an itinerary"] --> MODE{"Capture GPS?"}
    MODE -->|"Yes: permissions + user start"| TRACK["Foreground journey tracking"]
    MODE -->|"No"| MANUAL["Journey without GPS capture"]
    TRACK --> LOCAL[("Android local journey")]
    MANUAL --> LOCAL
    LOCAL --> REVIEW["Finish, review and confirm experience"]
    REVIEW --> UPLOAD{"Explicit manual upload?"}
    UPLOAD -->|"No"| KEEP["Keep on the device"]
    UPLOAD -->|"Yes"| SEND["Validate with backend<br/>Explicit user action"]
    SEND --> VALIDATE["Backend contract and identity checks"]
    VALIDATE --> VALID{"Evidence valid?"}
    VALID -->|"No"| REJECT["Return validation error<br/>Local record remains available"]
    VALID -->|"Yes: archive enabled"| COMMIT[("Commit to backend-local SQLite")]
    COMMIT --> RECEIPT["Issue archive receipt after commit"]
    RECEIPT --> ACK["Android saves the receipt"]
    class CHOICE,TRACK,MANUAL,REVIEW,KEEP,ACK client;
    class LOCAL,COMMIT store;
    class MODE,UPLOAD,VALID decision;
    class VALIDATE,RECEIPT,REJECT backend;
    class SEND action;
    classDef client fill:#623CEA,stroke:#623CEA,color:#FFFFFF,stroke-width:2px;
    classDef backend fill:#D0C9E5,stroke:#623CEA,color:#000000,stroke-width:2px;
    classDef provider fill:#A5C4D4,stroke:#623CEA,color:#000000,stroke-width:2px;
    classDef store fill:#F4E9EF,stroke:#7A003F,color:#7A003F,stroke-width:2px;
    classDef contract fill:#FFFFFF,stroke:#623CEA,color:#623CEA,stroke-width:2px,stroke-dasharray:4 3;
    classDef action fill:#7A003F,stroke:#7A003F,color:#FFFFFF,stroke-width:2px;
    classDef decision fill:#FFFFFF,stroke:#7A003F,color:#7A003F,stroke-width:2px;
    classDef planned fill:#E4EBF2,stroke:#7A003F,color:#7A003F,stroke-width:2px,stroke-dasharray:6 4;
    linkStyle default stroke:#623CEA,stroke-width:1.5px;
```

The archive path above assumes `COMPANION_JOURNEY_STORAGE_ENABLED=1`. When
storage is disabled, valid evidence can receive a validation-only response with
`stored_on_server=false`; it is not an archive receipt. With archiving enabled,
receipts follow a committed write, retries are idempotent and revisions are
immutable.

**Capture, validation, persistence and synchronization are separate operations.**
GPS points alone do not establish movement, and a planned route is not proof of
an observed journey. Finishing a journey does not automatically synchronize GPS
to the backend or to the project dashboard.

## Public transport flow

The local transport setup covers **MVB** services in Magdeburg. It combines an
identity-preserving GTFS subset with an OSM street graph and a separate realtime
identity proxy.

```mermaid
%%{init: {"theme":"base","themeVariables":{"fontFamily":"Arial, sans-serif","primaryColor":"#D0C9E5","primaryTextColor":"#000000","primaryBorderColor":"#623CEA","secondaryColor":"#A5C4D4","tertiaryColor":"#FFFFFF","lineColor":"#623CEA","textColor":"#000000","edgeLabelBackground":"#FFFFFF","clusterBkg":"#FFFFFF","clusterBorder":"#623CEA"},"flowchart":{"htmlLabels":false,"curve":"linear","nodeSpacing":35,"rankSpacing":40,"wrappingWidth":220,"padding":18}}}%%
flowchart TB
    GTFS["Public GTFS<br/>schedules"] --> BUILD["Extract MVB and build coherent graph"]
    OSM["Magdeburg OSM<br/>extract"] --> BUILD
    BUILD --> CHECK["Verify hashes and timetable coverage"]
    CHECK --> OTP["OTP<br/>scheduled routing"]
    RTFEED["Public GTFS-RT<br/>feed"] --> PROXY["Identity, compatibility and freshness checks"]
    PROXY --> VALID{"Realtime verified?"}
    VALID -->|"Yes"| REALTIME["Use compatible realtime updates"]
    VALID -->|"No"| SCHEDULED["Explicit scheduled fallback"]
    OTP --> REALTIME
    OTP --> SCHEDULED
    REALTIME --> RESULT["Backend transit alternatives"]
    SCHEDULED --> RESULT
    class GTFS,OSM,OTP,RTFEED provider;
    class BUILD,CHECK,PROXY,REALTIME,SCHEDULED,RESULT backend;
    class VALID decision;
    classDef client fill:#623CEA,stroke:#623CEA,color:#FFFFFF,stroke-width:2px;
    classDef backend fill:#D0C9E5,stroke:#623CEA,color:#000000,stroke-width:2px;
    classDef provider fill:#A5C4D4,stroke:#623CEA,color:#000000,stroke-width:2px;
    classDef store fill:#F4E9EF,stroke:#7A003F,color:#7A003F,stroke-width:2px;
    classDef contract fill:#FFFFFF,stroke:#623CEA,color:#623CEA,stroke-width:2px,stroke-dasharray:4 3;
    classDef action fill:#7A003F,stroke:#7A003F,color:#FFFFFF,stroke-width:2px;
    classDef decision fill:#FFFFFF,stroke:#7A003F,color:#7A003F,stroke-width:2px;
    classDef planned fill:#E4EBF2,stroke:#7A003F,color:#7A003F,stroke-width:2px,stroke-dasharray:6 4;
    linkStyle default stroke:#623CEA,stroke-width:1.5px;
```

Scheduled routing readiness is checked independently of realtime. Invalid or
unverifiable RT is not made available by relaxing trip/stop identities. An RT
failure can use the explicit scheduled fallback; an invalid or missing static
timetable is a different failure that must be resolved independently.

## Data management and project integration

### What is stored today

| Store | Location and owner | Contents and meaning |
| --- | --- | --- |
| Android SQLite | App-private device storage, `companion-native-v1.db`. | Local profiles/settings, searches, nested journeys, experience evidence and receipts. |
| Backend SQLite | Local to the API host; default `runtime/state/user-data/journeys.sqlite3`, or `COMPANION_JOURNEY_DB`. | Explicitly received and validated search/journey archive, identities and immutable revisions. |
| Public transport state | `runtime/state/transit-*`, or `COMPANION_STATE_DIR`. | Public infrastructure inputs, release pointers, graph manifests and synchronization status; separate from participant data. |

### Integration status

```mermaid
%%{init: {"theme":"base","themeVariables":{"fontFamily":"Arial, sans-serif","primaryColor":"#D0C9E5","primaryTextColor":"#000000","primaryBorderColor":"#623CEA","secondaryColor":"#A5C4D4","tertiaryColor":"#FFFFFF","lineColor":"#623CEA","textColor":"#000000","edgeLabelBackground":"#FFFFFF","clusterBkg":"#FFFFFF","clusterBorder":"#623CEA"},"flowchart":{"htmlLabels":false,"curve":"linear","nodeSpacing":35,"rankSpacing":40,"wrappingWidth":220,"padding":18}}}%%
flowchart TB
    DEVICE[("Android SQLite<br/>Local to the device")]
    API["Backend validation and archive"]
    SERVER[("Backend SQLite<br/>Local to the API host")]
    PROJECT["Project dashboard and project servers<br/>Data-management integration in progress"]
    DEVICE -->|"Explicit user upload"| API
    API -->|"Commit and receipt"| SERVER
    SERVER -.->|"Integration in progress<br/>Not an implemented data path"| PROJECT
    class DEVICE,SERVER store;
    class API backend;
    class PROJECT planned;
    classDef client fill:#623CEA,stroke:#623CEA,color:#FFFFFF,stroke-width:2px;
    classDef backend fill:#D0C9E5,stroke:#623CEA,color:#000000,stroke-width:2px;
    classDef provider fill:#A5C4D4,stroke:#623CEA,color:#000000,stroke-width:2px;
    classDef store fill:#F4E9EF,stroke:#7A003F,color:#7A003F,stroke-width:2px;
    classDef contract fill:#FFFFFF,stroke:#623CEA,color:#623CEA,stroke-width:2px,stroke-dasharray:4 3;
    classDef action fill:#7A003F,stroke:#7A003F,color:#FFFFFF,stroke-width:2px;
    classDef decision fill:#FFFFFF,stroke:#7A003F,color:#7A003F,stroke-width:2px;
    classDef planned fill:#E4EBF2,stroke:#7A003F,color:#7A003F,stroke-width:2px,stroke-dasharray:6 4;
    linkStyle default stroke:#623CEA,stroke-width:1.5px;
```

**The dashed integration above is not operational in this release.** A backend
receipt confirms a commit to that host's local archive. It does not confirm that
the journey is available in the project dashboard or synchronized with central
project servers. The future integration's storage technology and API are not
specified here as implemented features.

Keep participant answers, coordinates, journeys, SQLite files, phone exports,
credentials, signing keys, logs and backups out of Git and public packages.
Generated public transport resources are also excluded from the source repository.

### Shared contracts

| Resource | Role |
| --- | --- |
| `companion-identity-v1.json` | Product identifiers and exact hashes of current contracts. |
| `hotco-signed-model-v1.json` | Five-goal, thirteen-state numerical model parameters. |
| `journey-v1.schema.json` | Journey and confirmed-experience contract. |
| `longitudinal-*.json` | Versioned evidence and experimental schemas/namespace metadata. |
| `climate-factors-v1.json` and `vehicle-*.json.gz` | Public climate factors and vehicle catalogue/reference data. |
| `magdeburg-*.json` | Public mobility locations and tram reference alignments. |

Version identifiers and content hashes link frontend and backend evidence. Keep
numerical parameters and contract identities synchronized; do not edit saved
evidence or history to make a validation succeed.

## Repository map

Use the module tables above for responsibilities and this tree for navigation.
The tree describes the published application source, including representative
files; generated runtime data is shown separately.

<details>
<summary><strong>Expand the application source tree</strong></summary>

```text
.
├── companion.py                         # Operational CLI
├── frontend/                            # Android Gradle project
│   ├── app/
│   │   ├── build.gradle.kts
│   │   └── src/
│   │       ├── main/
│   │       │   ├── AndroidManifest.xml
│   │       │   ├── java/de/ovgu/imiq/companion/
│   │       │   │   ├── MainActivity.kt
│   │       │   │   ├── CompanionApp.kt / CompanionModel.kt
│   │       │   │   ├── Data.kt / TripRepository.kt
│   │       │   │   ├── TrackingService.kt / TrackingEngine.kt
│   │       │   │   └── Other UI, map, profile, journey and contract modules
│   │       │   ├── assets/
│   │       │   │   ├── art/jrpg/         # Companion and scene sprite resources
│   │       │   │   ├── branding/         # Product graphics
│   │       │   │   ├── maps/             # Map styles and their notices
│   │       │   │   └── licenses/
│   │       │   └── res/
│   │       │       ├── drawable/ / drawable-nodpi/
│   │       │       ├── mipmap-anydpi-v26/ / mipmap-anydpi-v33/
│   │       │       └── values/ / xml/
│   │       └── debug/res/xml/            # Development network policy
│   ├── gradle/wrapper/                   # Gradle bootstrap JAR and properties
│   ├── scripts/build.mjs                 # Optional Node build launcher
│   ├── build.gradle.kts / settings.gradle.kts
│   └── gradle.properties / gradlew / gradlew.bat
├── app-backend/
│   ├── application.py                    # Flask application factory
│   ├── api_routes.py                     # Shared API registration
│   ├── main.py                           # Local HTTP entry point
│   ├── resources.py / requirements.txt
│   ├── model/                            # Numerical model and readout
│   ├── passport/                         # Declared priorities and profile logic
│   ├── services/                         # Search and model coordination
│   ├── routing/                          # Routing, stops, departures and map APIs
│   ├── context/                          # Weather and environmental context
│   ├── climate/                          # Climate estimates and vehicle profiles
│   ├── journeys/                         # Evidence validation and SQLite archive
│   ├── explanations/                     # Checked evidence and narration
│   ├── infrastructure/                   # Settings, limits, HTTP and compatibility
│   ├── comfort_pilot/                    # Versioned experimental components
│   ├── artifacts/                        # Public five-goal model metadata
│   └── choice_wording_v1.json            # Versioned presentation wording
├── contracts/
│   ├── companion-identity-v1.json        # Product identifiers and contract hashes
│   ├── hotco-signed-model-v1.json        # Numerical model parameters
│   ├── journey-v1.schema.json
│   ├── longitudinal-*.json               # Evidence and experimental schemas
│   ├── climate-factors-v1.json
│   ├── vehicle-*.json.gz                 # Public catalogues, not user profiles
│   └── magdeburg-*.json                  # Public mobility reference data
├── operations/
│   ├── runtime.py                        # Service ownership and release lifecycle
│   ├── transit.py                        # Bootstrap, GTFS updates and activation
│   ├── checks.py / smoke.py              # Operational and synthetic diagnostics
│   └── transit_runtime/
│       ├── gtfs_subset.py                # Identity-preserving MVB extraction
│       └── rt_proxy.py                   # Realtime filtering and compatibility
├── deploy/
│   ├── local.env.example / backend.env.example
│   ├── constraints-python313.txt
│   ├── gunicorn.conf.py
│   ├── companion-*.service
│   └── companion-transit-sync.timer / companion-transit-sync.sudoers
├── .gitignore / .gitattributes
├── README.md
└── THIRD_PARTY_NOTICES.md
```

</details>

The [`master`](https://github.com/imiq-project/app-frontend/tree/master) branch
contains the current application. The previous remote app is preserved under
`legacy/` on the separate [`legacy`](https://github.com/imiq-project/app-frontend/tree/legacy)
branch. The published selection excludes tests/fixtures, internal operational
history, design drafts, user data, secrets, build output and local backups.

<details>
<summary><strong>Expand generated local runtime state — excluded from Git</strong></summary>

```text
runtime/
├── otp-shaded-2.7.0.jar
└── state/
    ├── transit-active.json
    ├── transit-bootstrap/                # Public inputs for first graph creation
    ├── transit-releases/<release>/
    │   ├── graph/                        # GTFS, OSM, configs, manifest and graph
    │   ├── build-state.json / ready.json
    │   └── build.log
    ├── transit-sync.json
    ├── services/                         # Local process logs
    └── user-data/journeys.sqlite3         # Private backend-local archive
```

`COMPANION_STATE_DIR`, `COMPANION_OTP_JAR` and `COMPANION_JOURNEY_DB` can move
these resources outside the checkout. The Android database remains in the
device's app-private storage.

</details>

---

## Getting started

### Prerequisites

For Android compilation:

- Android Studio, or an Android SDK installation with platform **36**.
- A compatible Java installation for Android Gradle Plugin 8.13.0 (Java 17 or
  newer; Java 21 can also serve the backend transport tools).
- Gradle is provided through the checked-in wrapper; do not install a different
  global Gradle version to replace it.
- Node.js is optional and needed only for the convenience scripts in
  `frontend/scripts/`. Direct Gradle commands do not require Node.

For the backend and local public transport:

- Python **3.13** is the reference environment for the supplied constraints.
- Java **21 or newer** for OpenTripPlanner 2.7.0.
- Internet access for dependency and initial transport downloads, plus connected
  routing, weather, geocoding and map services.
- Several GB of free disk and RAM for graph building. Defaults reserve at least
  2 GiB of free disk and allow a 4 GiB graph-build Java heap; increase resources
  as needed. The default serving heap is 2 GiB.
- PowerShell 7 (`pwsh`) for the Windows process-management commands, or Linux
  with `/proc` available. Gunicorn and systemd templates target Linux.

### 1. Set up the backend environment

```sh
git clone --branch master https://github.com/imiq-project/app-frontend.git
cd app-frontend
python -m venv .venv
```

Activate the environment in PowerShell:

```powershell
.\.venv\Scripts\Activate.ps1
```

Or on Linux:

```sh
. .venv/bin/activate
```

Install the backend dependencies:

```sh
python -m pip install -r app-backend/requirements.txt -c deploy/constraints-python313.txt
```

Copy `deploy/local.env.example` to `app-backend/.env` for local use. This example
uses loopback binding and no API token, intended for development on your own
computer. The real `.env` is private and must remain untracked. Process environment
variables take precedence over it. Provider URLs are configuration examples;
availability and access to IMIQ services must be confirmed with their operator.

```powershell
Copy-Item deploy/local.env.example app-backend/.env
python companion.py config --json
```

On Linux use `cp deploy/local.env.example app-backend/.env`.

### 2. Initialize and start local transport

A clean checkout deliberately has no OTP JAR, graph, GTFS feed or user database.
Run the initial setup once:

```sh
python companion.py data transit bootstrap
```

This command:

1. Refuses to overwrite an existing active transit installation.
2. Downloads OTP 2.7.0 from its upstream release and verifies the pinned SHA-256.
3. Downloads the Magdeburg OSM PBF extract from BBBike over HTTPS, or accepts a
   local extract with `--osm /path/to/Magdeburg.osm.pbf`.
4. Downloads the current public German GTFS feed and extracts MVB services while
   retaining original trip, route and stop identities.
5. Builds an OTP graph, writes and verifies input hashes, checks effective
   timetable coverage, and creates the first active-release pointer.

Setup writes only to configured runtime locations. It does **not** start services
or claim that routing is available before the subsequent checks pass. A failed
setup leaves the active pointer absent; fix the reported issue and retry.
The OSM extract must cover Magdeburg. Its download is size checked and its
resulting hash is recorded with the graph inputs; unlike the OTP executable,
the moving OSM and GTFS sources are not pinned to an old snapshot.

Start and inspect the services:

```sh
python companion.py ops plan
python companion.py ops start
python companion.py check --services
python companion.py check --synthetic-route
```

Allow OTP time to load the graph before checking. The default ports are **8077**
for the API, **8091** for OTP and **8092** for the realtime proxy. Scheduled transit
readiness is checked independently of realtime availability. If realtime identity
cannot be verified, transport must use the explicit scheduled fallback; identifiers
are never relaxed to force realtime availability.

For later updates:

```sh
python companion.py data transit sync
python companion.py data transit status
python companion.py data transit verify
```

`sync` checks for a new static feed, builds a coherent replacement when needed,
and verifies scheduled routing before completing replacement activation. It can
restart the owned local transport processes. `watch` repeats synchronization;
the Linux service and timer templates support a ten-minute schedule. Installing
those templates is a separate operator action, not part of bootstrap.

Useful settings include `COMPANION_JAVA`, `COMPANION_OTP_JAR`, `COMPANION_STATE_DIR`,
`COMPANION_OTP_BUILD_HEAP`, `COMPANION_OTP_HEAP`, and
`COMPANION_TRANSIT_BUILD_TIMEOUT`. The default build timeout is 1800 seconds.

The local transport extract covers **MVB**, not a complete regional or national
rail network. Other route options depend on the configured routing providers.

### 3. Build and connect the Android app

Open **`frontend/`** in Android Studio, let Gradle synchronize, and build the
`app` module. Keep the repository layout intact: the app also packages assets
from the sibling **`contracts/`** directory.

From the repository root on Windows:

```powershell
node frontend/scripts/build.mjs assembleDebug
```

Or without Node, from `frontend/`:

```powershell
.\gradlew.bat assembleDebug
```

On Linux, from `frontend/`:

```sh
chmod +x gradlew
./gradlew assembleDebug
```

The debug APK is generated at
`frontend/app/build/outputs/apk/debug/app-debug.apk`. Compilation does not install
the APK on a phone. SDK paths belong in environment variables (`ANDROID_HOME`)
or your private `frontend/local.properties`, never in a committed machine-specific
file. Release distribution requires your own privately managed signing setup.

The default Android backend URL is `http://localhost:8077`. For an explicitly
connected development phone, after installing the APK yourself, USB forwarding
can connect that address to your computer:

```sh
adb reverse tcp:8077 tcp:8077
```

For the standard Android emulator use `http://10.0.2.2:8077` in the app's
connection settings. A remote installation needs a reachable HTTPS endpoint.
Debug builds permit cleartext development connections; the main network policy
restricts cleartext to the configured local hosts.

## Configuration and operations

`deploy/backend.env.example` documents the Linux deployment environment.
`deploy/gunicorn.conf.py` configures the production WSGI server, and the
`companion-*.service` files are templates whose paths, user, environment file and
permissions must be adapted to your host. Linux deployment and HTTPS termination are operator responsibilities.

Important configuration:

| Variable | Meaning |
| --- | --- |
| `PORT`, `COMPANION_BIND_HOST` | Local API port and interface. |
| `COMPANION_OTP_PORT`, `COMPANION_REALTIME_PORT` | Distinct transport process ports. |
| `COMPANION_STATE_DIR` | Generated infrastructure state and release pointers. |
| `COMPANION_JOURNEY_DB` | Private journey database path; preferably outside the source checkout. |
| `COMPANION_JOURNEY_STORAGE_ENABLED` | Enables the backend journey archive. |
| `COMPANION_ROUTING_BASE_URL` | External route-computation provider. |
| `COMPANION_GRAPHHOPPER_BASE_URL` | External geometry provider. |
| `COMPANION_ORION_BASE_URL` | External context provider where applicable. |
| `COMPANION_OTP_BASE_URL`, `COMPANION_OTP_STATUS_URL` | OTP API and realtime identity status. |
| `COMPANION_API_TOKEN` | Optional Bearer protection of `/api/`; required by the supplied Gunicorn configuration. |

Gunicorn requires a private API token of at least 32 characters. The current
Android connection settings configure a base URL and do **not** provide a Bearer
token field. A token-protected remote API therefore needs client authentication
integration before this Android version can use it directly. Do not bake server
secrets into a public APK. The shared API token is not participant authentication.
The repository does not provide a turnkey public multi-user service.

Optional model-generated narration requires separately configured provider
credentials. Never commit them. Numeric model results, availability and clinical
or behavioral interpretations must not be inferred from a narration service.

## Troubleshooting

```sh
python companion.py ops status
python companion.py check --services
python companion.py check --providers --validation
python companion.py ops restart --service backend
python companion.py ops stop
```

Diagnostics can use configured external providers. `--validation` checks HTTP
rejection behavior; it is not a development test suite. The synthetic route
check uses public coordinates and synthetic declarations and does not archive a
participant. Default output goes to the terminal; `--output` saves a chosen report.

- **No active transit release:** run `data transit bootstrap` first.
- **Graph build failed:** inspect its `build.log` under
  `runtime/state/transit-releases/`; check Java 21+, memory, disk, downloads and
  timetable dates. Retry bootstrap if no active pointer exists.
- **API works, realtime is degraded:** inspect compatibility and feed freshness;
  retain scheduled routing instead of weakening GTFS identity checks.
- **Phone cannot reach localhost:** check the app URL, USB forwarding and API
  listener. `localhost` on a phone normally refers to the phone itself.
- **401 from a remote API:** the server requires authentication; this Android
  version needs the client integration described above.
- **Another process owns a port:** lifecycle commands refuse to stop an unknown
  listener. Resolve the conflict before retrying.

Health HTTP success alone does not establish full provider or route availability.
Checks return 0 for success, 2 for degraded service, 3 for API unavailability and
4 for OTP HTTP unavailability; operational command failures return 1.

## Public data and third-party notices

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and the license files shipped
with Android assets. OpenStreetMap attribution must remain visible. Climate
factors are estimates with explicit boundaries, and vehicle registration names
do not imply model-specific consumption measurements.

This repository does not grant a new blanket license to Companion source code,
project branding or third-party assets. Third-party licenses retain their own
terms; obtain authorization from the respective rights holders where needed.


**Project affiliation:** IMIQ, Otto von Guericke University Magdeburg. IMIQ is
funded by the European Union and the State of Saxony-Anhalt; the existing
[official funding marks](frontend/app/src/main/assets/branding/imiq-funding-official.png)
are retained in the Android assets.

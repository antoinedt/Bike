# Bike — Virtual Indoor Trainer (Android)

A single-player, offline "Zwift-style" Android app. It connects to a smart bike
trainer over Bluetooth, simulates riding through randomly generated **corridors**
with real elevation, lets you change **virtual gears**, and can upload the
finished ride to **Strava**.

There is no online/multiplayer component — it's just you, the road, and your
trainer.

## Features

- **Smart trainer connection (BLE)** — speaks the standard
  [FTMS](https://www.bluetooth.com/specifications/specs/fitness-machine-service-1-0/)
  (Fitness Machine Service) for live power/cadence/speed **and** resistance
  control. Falls back to the Cycling Power Service for power-only trainers.
- **Elevation-based resistance** — the trainer's simulated road grade tracks the
  generated route's elevation profile in real time.
- **Virtual gears** — 12 gears by default. Shifting up/down adds or removes
  resistance on top of the terrain; because the trainer reports your actual
  power, gear choice flows naturally into your virtual speed (easy gear → less
  power → slower; hard gear → more effort → faster). Works on flats too.
- **Procedural corridors** — each ride generates a deterministic route (by seed)
  with a believable elevation profile and gentle bends, rendered as a pseudo-3D
  road plus a live elevation profile.
- **Physics** — a forward-dynamics model converts your measured power + the road
  grade into virtual speed and distance (gravity, rolling resistance, aero drag).
- **Strava upload** — finished rides are exported to TCX (with power) and
  uploaded via the Strava v3 API using OAuth.
- **Heart-rate monitor** — pair any standard BLE heart-rate strap (Heart Rate
  Service 0x180D); HR flows into the HUD, recording and TCX.
- **Physical gear controller** — connect a Zwift Click / Play controller to
  shift the virtual gears (best-effort, community-documented protocol).
- **In-app keys** — enter your Strava API keys and MapTiler key directly in the
  app (stored on-device); no rebuild needed. Build-time values are the fallback.
- **Local ride files** — every finished ride is saved as a TCX in the app's
  storage and can be exported/shared to upload manually later.

## Project layout

```
app/src/main/java/com/bike/trainer/
├── ble/        BLE: FTMS/Cycling-Power parsing + GATT connection manager
├── physics/    CyclingPhysics (speed/distance) + VirtualGears
├── route/      Route model + procedural RouteGenerator (corridors)
├── session/    RideEngine (the tick loop), RideState, RideRecorder
├── export/     TcxWriter
├── strava/     OAuth config, models, repository (token storage + upload)
├── data/       DataStore-backed settings (rider weight, difficulty)
├── di/         ServiceLocator (manual singletons)
└── ui/         Compose screens: home, connect, ride, summary
```

## Building

Requires the Android SDK (compileSdk 35) and JDK 17+.

```bash
./gradlew assembleDebug
```

The Gradle wrapper is checked in. There is no Android SDK in the CI sandbox used
to scaffold this project, so the app is built/run on a machine with the SDK
installed (or Android Studio).

### Strava credentials

Uploads require a Strava API application
(<https://www.strava.com/settings/api>). Set the **Authorization Callback
Domain** to `strava-auth`. Then provide the credentials at build time, e.g. in
`~/.gradle/gradle.properties` or `local.properties`:

```
STRAVA_CLIENT_ID=12345
STRAVA_CLIENT_SECRET=your_secret_here
```

Without them the app still runs; only the Strava upload is disabled. The OAuth
redirect URI is `bike://strava-auth` (wired up in the manifest).

### 3D map scenery (MapTiler)

The ride view renders a real 3D map (satellite imagery + 3D terrain + extruded
OpenStreetMap buildings) via [MapLibre](https://maplibre.org/), following the
route. The tiles come from [MapTiler](https://www.maptiler.com/) — add a free
key at build time:

```
MAPTILES_API_KEY=your_maptiler_key
```

Without a key the ride falls back to MapLibre's free demo tiles: still a real
world map (GPX routes show the right place) but flat — no terrain, satellite, or
buildings. The map needs a network connection while riding; the rest of the app
(trainer, gears, resistance, recording) works offline.

### Interactive Street View (Google Maps, optional)

The ride view has a **Chase ⇄ Street View** toggle. When a Google Maps key is
present it shows an interactive Google **Street View panorama** that follows the
route; otherwise the toggle gives a rendered near-ground "ground" view of the 3D
map.

The Google Maps SDK reads its key from the manifest, so unlike the other keys
this one is **build-time** (it can't be entered in-app). Create a key in the
[Google Maps Platform](https://console.cloud.google.com/google/maps-apis) with
the **Maps SDK for Android** enabled and **billing on**, then provide it at build
time:

```
MAPS_API_KEY=your_google_maps_key
```

(or set the `MAPS_API_KEY` GitHub Actions secret). Street View only appears where
Google has coverage; gaps show a small notice.

## How a ride works

1. Pick a difficulty and your weight on the home screen, connect your trainer.
2. **Start Ride** generates a corridor and opens the ride HUD.
3. The `RideEngine` ticks 4×/second: reads your power from the trainer, advances
   the physics for the current grade, updates the HUD, and sends
   `terrain grade + gear offset` back to the trainer as the resistance.
4. Shift gears with the ± buttons to find a comfortable cadence.
5. **Finish** (or reach the end) → summary screen → optionally upload to Strava.

## Notes & limitations (first version)

- Single player, fully offline aside from the Strava upload.
- Corridor rendering is intentionally lightweight (Compose Canvas), not a 3D
  engine.
- No background/foreground service yet — keep the app in the foreground while
  riding.
- HRM is read if the trainer reports it via FTMS; a standalone HR strap is not
  yet paired separately.

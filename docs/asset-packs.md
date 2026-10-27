# 3D model packs (3D World)

A pack swaps the built-in 3D World props for your own glTF models, and can bring
its own tile art for the game worlds. Packs are imported in the app
(Home → 3D World → *Import pack (.zip)…*, from phone storage, Google Drive …)
and stay in the app's private storage: they are never part of this repository
or its builds.

## What goes in the zip

- **Models** (`.glb` or `.gltf`) named after a slot, e.g. `chest.glb`,
  `round_tree.glb`. A slot without a model keeps the built-in one. Models keep
  their own materials and textures, stand on the ground (their lowest point at
  the prop's position) and face +Z.
- **`pack.json`** (optional) to use other file names and set sizes:

  ```json
  {
    "name": "My pack",
    "props": {
      "chest": { "model": "treasure_box.glb", "height": 0.8, "rot": 90 },
      "round_tree": { "model": "oak.glb", "height": 9 },
      "gem": { "model": "crystal.gltf", "scale": 0.5, "y": 0.2 }
    }
  }
  ```

  `height` scales the model to that many metres (else `scale`, default 1),
  `y` lifts it (metres), `rot` turns it (degrees).
- **`atlas.png`** (optional): tile art for the game worlds — 128 × 128 px,
  8 × 8 tiles of 16 px, same layout as the built-in
  [`robot_atlas.png`](../app/src/godot/themes/robot_atlas.png) /
  [`quest_atlas.png`](../app/src/godot/themes/quest_atlas.png)
  (`robot_atlas.png` / `quest_atlas.png` in the pack target one theme only).

A single top-level folder inside the zip is fine.

## Slots

| World | Slots |
|---|---|
| Kingdom Quest | `round_tree`, `conifer`, `bush`, `rock`, `boulder`, `mushroom`, `pot`, `chest`, `gem`, `heart`, `sign`, `lily` |
| Robot City | `bot`, `capsule` |
| Real place | `maple`, `linden`, `ash`, `locust`, `elm`, `oak`, `poplar`, `birch`, `small_tree`, `conifer`, `tree`, `car`, `lamp`, `bench`, `bus_stop`, `signal`, `bollard`, `bike_rack`, `bin`, `hydrant` |

Free models that work well: Kenney (kenney.nl), Quaternius (quaternius.com),
KayKit (kaylousberg.com) — all CC0.

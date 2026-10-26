# Changelog

Notable changes to RTX Remix for Minecraft, newest first. Changes that a
player, a map author, or a contributor would notice go here; internal
refactoring does not.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
The version in a release is the `mod_version` in `gradle.properties`, and it is
also part of every mod file name.

## Unreleased

### Added

- Sky Appearance settings: saturation, sunset warmth, sun glare, rainbows
  after rain, star brightness, high cirrus and northern aurora.
- Two cloud styles. Reimagined draws large rounded blocky clouds in one or two
  thick layers. Unbound draws soft volumetric clouds out to the horizon in
  three layers, small and large cumulus and an altostratus sheet, that cast
  shadows on the land as they drift.
- Cloud coverage, speed and Unbound shape and lighting settings.
- LOD Terrain: with Distant Horizons 3.3.4 installed, its distant terrain is
  path traced beyond Minecraft's render distance, and the view fade moves out
  to its LOD radius. Experimental; needs Auto View Fade.
- Puddles on open ground in the rain: thin sheets of clear water that the
  raindrop rings ripple. They pull back from sheltered and lower ground, so a
  roof's edge leaves no straight line.
- Lens settings: a light depth of field that focuses on what the crosshair
  rests on, and lens flare from lights, such as the sun and a held torch:
  round ghosts and a rainbow ring along the line through the screen centre,
  and a starburst on the sun.
- The sun glints on rippling water.
- Aerial Haze: distant land fades into the colour of the sky, most in low
  valleys and least on high ground.
- Unbound clouds are drawn for every pixel, sharp and with the parallax of
  real clouds: you can fly into and through them, and from above you look
  down on them, with the land showing through the gaps.

### Changed

- High Cirrus is off by default. Seen from below, its streaks fanned out from
  the zenith and the sun like smears; the slider still turns it on.
- Direct sunlight is slightly warmer.
- Block lights, item lights and beacon beams are 1.5 times as bright.
- Water is clearer: a riverbed shows several blocks down, and a bright cloud's
  reflection no longer hides it in shallow water.
- Puddles reflect more of the sky, and raindrop rings land more often and
  ripple harder.
- Unbound clouds are brighter on the sun's side and greyer on the far side,
  and the bases of the cumulus are flat rather than frayed. Their sides bulge
  and round into the base instead of standing like walls.
- Rain closes the view: distant land fades into the grey of the overcast sky
  instead of standing dark against it.

### Fixed

- Distant Horizons no longer turns Minecraft's clouds off, which removed
  every cloud while it was installed.
- Distant leaves no longer turn violet, and distant faces no longer pick up
  lines or a neighbour's glow, with high-resolution LabPBR packs. Their
  normal and specular maps stay out of the block and item atlases, and the
  atlases are stitched without a border around each sprite, which keeps every
  sprite on its own power-of-two grid so the runtime's mips never blend it with
  a neighbour. A 128-texel pack's atlases are a quarter of the size the wider
  border needed, which saves about 6 GB of video memory.
- Leaves, grass and packed ice with LabPBR subsurface scattering no longer
  glow far brighter than their surroundings at night and in the rain. The
  radiance cache no longer shades them where they are seen directly.
- `rtx screenshots refresh --resource-pack local:` accepts a pack already in
  the instance's resource packs folder.
- Unbound clouds no longer show a fine diagonal grid where the sky overhead is
  magnified.
- Seen from above the Unbound clouds, the land below keeps its colours, and
  the clouds over it are the same clouds as the ones in the sky, not a flat
  layer that appears as you climb past them.
- Distant ridges no longer show a dark outline that crawls along them against
  the sky: aerial haze now covers the pixels along a silhouette.
- In the rain, the aerial haze, the Unbound clouds over the land and the depth
  of field are no longer undone wherever rain falls.
- The aerial haze no longer flickers toward the edges of the screen while you
  look down or into trees.
- The bases of Unbound clouds near the horizon no longer show grainy patches.
- The flare from a torch in your hand starts at the flame on screen.
- When the time of day jumps, as with `/time set`, sleeping or a camera path,
  riverbeds and shaded ground no longer keep glowing with the old daylight
  for half a minute: the runtime's radiance cache is cleared.
- Benchmarks are no longer held at 30 FPS after a minute by Minecraft's
  inactivity frame limit.

## 0.2.0 - 2026-10-25

The port to Minecraft 26.3. PORTING.md records the design changes.

### Changed

- Minecraft Java Edition 26.3 on Fabric, with Java 25.
- The game heap defaults to a fixed 16 GB.
- Changing a Block Lights multiplier updates the loaded lights in place
  instead of rebuilding all terrain, which could fill video memory.

### Added

- Raindrop rings on open water while it rains.
- Japanese translation.

### Fixed

- Distant terrain no longer shows purple tints and lines from neighbouring
  atlas sprites; every atlas sprite gets at least an eight-texel border.
- See-through sleeves on the first-person arm show the arm instead of black.
- Slab sides next to leaves or glass no longer vanish depending on the view.

## 0.1.0 - 2026-10-23

The first public release, a beta.

### Added

- Path-traced rendering of Minecraft Java Edition 1.21.1 through RTX Remix:
  terrain, entities and block entities, particles, the sky, and clouds, with
  baseline LabPBR textures built into the mod JAR through Fabric's `fabric` pack.
- Lighting that follows the game: time of day, weather, being underwater, and
  per-dimension skies, with the End's constant dark dome and a black sky with
  no ambient where a dimension has none. Map authors can give zones and biomes
  their own looks through gameplay-driven conf profiles installed beside the
  mod, with an authoring guide.
- Block lights from resource-pack rules, a Light Reach setting and an Auto
  light cull distance, flames and lights on hand-held items, and the `G` key
  to put out and relight what the local player holds.
- The Copper Beacon, a placeable and craftable directional lamp: dye and
  saturation, intensity, cone angle, redstone control, and static, rotating,
  oscillating, or patrolling motion that every player sees the same way.
- An RTX Remix settings screen in Options, a button into Remix's own Graphics
  Settings, the `/rtx` command tree, `F8` to toggle path tracing, and
  session-only quality presets through `/rtx preset <name>` or
  `-Drtxremix.preset=<name>`.
- Camera paths: record a flythrough by walking it and replay it on wall-clock
  time or one fixed step per frame. Still shots keep a pose together with its
  time of day, weather, path-tracing state, and cloud style.
- Procedural clouds, selectable in place of vanilla's.
- Steadier frames at high render distance: paced chunk delivery and unloads
  in single-player, a hold behind the loading screen after joining a world,
  and allocation-free hot paths that keep garbage collection out of the frame.
- A self-contained mod JAR that any Fabric launcher can install on its own,
  and a Windows installer for Prism Launcher and the Microsoft launcher that
  sets up Java, Fabric, the mod, and tuned JVM arguments, and repairs,
  upgrades, uninstalls, or exports an install.
- Developer tools under `tools/` through the `rtx` CLI: launching, benchmarks
  on the pinned demo world, built by [BlockWorks](https://www.blockworks.uk/),
  and the LabPBR art pack, created by an art team at NVIDIA, or on locally
  staged fixtures, JFR and Tracy profiling, and the contributor checks.
- Developer tools launch Minecraft through a Prism Launcher data folder for
  each checkout, signed in to a Microsoft account that owns Minecraft: Java
  Edition. `tools\rtx.bat prism signin` signs a checkout in, and
  `launch.jvmArgs` carries developer JVM options.

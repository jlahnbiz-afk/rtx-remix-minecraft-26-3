# Extracted particle geometry

Vanilla `SINGLE_QUADS` particles retain their worker-prepared, double-buffered
slot renderer. Custom quad render states can implement `ExtractedParticleGeometry`
and write their existing extracted vertices to `ParticleGeometrySink`. This
path preserves multiple quads, orientation, UVs, packed ARGB and opacity instead
of replacing a custom particle with a billboard. It submits native Remix meshes;
it does not add a raster pass.

## Particle Interactions

The optional adapter supports Particle Interactions 0.10.5 for Minecraft 26.3,
including the custom `26.3-fabric.perf1` build. Other API versions are rejected
by Fabric metadata because the adapter accesses internal primitive storage.
Particle Interactions is neither bundled nor required. Its JAR is unchanged.

The adapter reads the completed `CustomParticleGeometryRenderState` at the
shared particle-submit boundary. Its extraction still runs exactly once, with
the original animation, collision, particle limits and lifetime logic. Active
custom particles outside the main camera frustum are extracted while Remix is
rendering, since reflected rays can see them. Raster mode retains the original
frustum check and submission.

Layers retain their atlas, blend mode, the particle shader's 10% alpha cutoff,
and face culling. Batches are split by
layer and intrinsic emission, then paged at 8192 quads. Reusable buffers grow
geometrically; uploads contain only active vertices. Materials borrow existing
atlas textures. Meshes and buffers are released on world teardown and resource
reload, before those textures are destroyed. Inactive page storage is released
after 120 submitted frames. All native calls remain on the render thread.

This is a shared quad adapter, not a universal replacement for arbitrary mod
shaders or non-quad particle renderers. Additional mods need a small bridge to
the extracted-geometry interface; they do not need another native renderer.

## Brightness and emission

Vanilla flame particles raise packed block-light brightness with age. That
lightmap value alone does not define a physical light. The existing Remix
vanilla flame material uses its authored emissive texture, and that path is
unchanged.

Particle Interactions supplies a separate intrinsic `minLightEmission` value.
The adapter records that value during extraction, rather than inferring emission
from environmental packed light. Zero retains environmental path-traced
lighting. A positive value supplies an albedo-colored emissive material with
intensity `minLightEmission / 15`. This is a modest material-emission fallback,
not the much stronger intensity used by the vanilla flame's authored texture.
An explicit resource-pack emissive mask for the source sprite takes precedence
and keeps the atlas's authored intensity and mask. No extra atlases or explicit
point lights are created per particle or per emission level.

Material emission can be sampled by the runtime's indirect-lighting system;
being bright on screen does not guarantee a detectable illumination change on
nearby surfaces. Small, short-lived surfaces are subject to the runtime's
sampling and temporal reconstruction. There is no independent brightness-only
particle control in the pinned material API. Adding a dynamic light for each
spark is deliberately avoided: it would increase light sampling and churn and
could change the intended illumination substantially.

## Validation

Run the JVM suite with `gradlew test`. Geometry-buffer tests cover exact XYZ,
UV, ARGB, normals, bounds, index order, growth, frame reset and page overflow.
Metadata tests cover optional loading and unsupported-version rejection.
Material tests cover resource-pack emission precedence.

For runtime validation, compare the previous and current mod in the same
world, settings, resolution and mod set. Warm the JVM before recording frame
times; disable frame generation only for measurement and use the same setting
in both builds. Include dense vanilla particles, custom multi-quad particles,
bright and dark rooms, translucent layers, resource reload and RTX toggling.
Check native quad counts and ensure custom raster submission occurs only when
RTX is off. Keep diagnostic helpers out of the distributable.

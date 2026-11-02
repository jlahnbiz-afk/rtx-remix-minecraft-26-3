# Extracted particle geometry

Remix's live-slot renderer handles vanilla `SINGLE_QUADS` particles on its
existing worker and double buffer. Custom extracted quad groups must also
reach Remix before vanilla particle submission is suppressed; otherwise their
CPU geometry exists but no native scene geometry is submitted.

The shared adapter consumes completed camera-relative quads once, preserving
XYZ, UVs, packed ARGB/opacity, orientation, layer atlas, face culling, blending
and the particle shader's alpha cutoff. It batches native meshes by layer and
intrinsic emission, pages at 8192 quads, reuses buffers and existing atlas
uploads, and releases inactive page storage and world/reload resources.
It adds no raster pass, particle simulation or second live-particle extraction.

## Source contracts

`ExtractedParticleGeometry` is the explicit integration contract. Sources write
through `ParticleGeometrySink`, including full sprite bounds and intrinsic
emission when available. All native work remains on the render thread.

A compatibility fallback recognizes public extracted-state methods with the
same shape as Minecraft's quad state: `isEmpty()`, `layers()` returning an
`Iterable`, and `buildLayer(SingleQuadParticle.Layer, VertexConsumer)`.
Method handles are resolved once per state class, with unsupported results
cached too. It never opens a private field or method, names another mod's
internal class, or inspects a third-party storage layout. Vanilla's exact quad
state skips this lookup entirely and retains its existing worker/slot path.

The fallback reuses one quad of vertex scratch space and a reload-scoped UV
index to find full sprite bounds, including cropped quads. Unsupported states
are not submitted again or heuristically replaced with billboards. This is a
CPU-quad adapter, not universal support for procedural GPU geometry or arbitrary
custom shaders. Source-specific extraction/culling policies remain the source's
responsibility; the adapter does not resurrect geometry absent from a state.

## Lighting

Environmental packed light is not an intrinsic emission attribute. The public
vertex fallback does not turn sunlit or fullbright particles into physical
lights. Existing authored atlas emission masks still apply. An explicit source
can supply its emission floor; authored emission takes precedence, including
Minecraft's built-in emitting block sprites. No per-particle light objects or
duplicate emission atlases are created. Vanilla flames keep their current
authored materials and worker rendering.

Material emission can contribute to the runtime's indirect lighting, but a bright
pixel does not guarantee a visible nearby illumination change. Sampling of tiny,
short-lived surfaces is separate from fullbright raster lightmap behavior.

## Validation

Run `gradlew test`. Synthetic fixtures use Minecraft interfaces only and cover
vanilla exclusion, explicit-source precedence, public method shape and caching,
source errors, multi-quad XYZ/UV/ARGB transfer, cropped sprite bounds, incomplete
streams, native index/normal conventions, paging/reset and emission precedence.

For graphical testing, use identical world/camera/settings/runtime and compare
dense vanilla particles before/after, custom non-billboard/multi-quad particles,
translucent/cutout layers, dark/bright scenes, reload, world changes and RTX
toggle behavior. Verify native geometry counters instead of accepting mod
loading as proof of rendering. Record frame generation, GPU clocks/power,
particle counts and warm-up before comparing frame-time distributions. Keep
machine-specific fixtures and non-public mod code/artifacts outside the PR.

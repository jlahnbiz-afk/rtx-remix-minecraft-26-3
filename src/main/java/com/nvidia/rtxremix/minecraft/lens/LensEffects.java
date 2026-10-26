/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lens;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import com.nvidia.rtxremix.minecraft.sky.CloudField;
import com.nvidia.rtxremix.minecraft.sky.SkyLook;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryStack;
import java.nio.IntBuffer;

/**
 * Draws a camera lens over the composited Remix image: a light depth of field focused on
 * what the crosshair rests on, and flares from lights: ghosts and a rainbow ring along the
 * line from each light through the screen centre, and a starburst on the sun. The lights are
 * the sun and the brightest the frame submits ({@link FlareSources}); each flares as brightly
 * as the image is where it lands, so one behind a block or lost in the daylight does not.
 *
 * <p>It also fades distant land into the sky at the horizon (aerial haze) and draws the
 * Unbound clouds per pixel ({@link CloudField}). The runtime shows them on a dome, a panorama
 * marched a few times a second and averaged, which stays for reflections and the sky's light;
 * on screen that panorama is soft, trails a moving camera, and is hidden by the land wherever
 * a cloud lies in front of it. So the lens marches the same field with the same lighting for
 * every pixel: over the land, the clouds between the camera and the land; over the sky, all
 * of them, put in place of the dome's, which it takes back out of the image using the
 * panorama the dome shows and the sky the image shows behind it.
 *
 * <p>The passes run after the composite and before the weather. The first keeps only the
 * near-saturated parts of the Remix image, at half resolution, and mips them, which is what a
 * light's brightness is read from; small passes then gather the sky's colour along the
 * horizon, march the clouds at half resolution, measure how bright the image shows opaque
 * clouds against their marched light, and gather the sky behind the dome's clouds by
 * elevation; the last redraws the image opaque over the main target, blurred by depth, hazed
 * and clouded, with the flares added. The interop depth is window depth from the Remix
 * camera's planes.
 *
 * <p>Render thread only. Like {@code RemixWeatherRenderer}, state the OpenGL backend caches
 * goes through {@link GlStateManager}, and program, vertex array, sampler and viewport
 * bindings are put back.
 */
public final class LensEffects {

    // The near plane the Remix camera renders with; the far plane comes with the camera.
    private static final float NEAR_PLANE = 0.05f;
    /** Blocks the focus raycast reaches; with nothing in reach the lens focuses here. */
    private static final double MAX_FOCUS_DISTANCE = 256.0;
    private static final float MIN_FOCUS_DISTANCE = 0.5f;
    /** Seconds for the focus to settle on a new distance, as an eye or an autofocus does. */
    private static final float FOCUS_SECONDS = 0.35f;
    /** Seconds for the clouds' brightness to follow the sky's exposure. */
    private static final float CALIBRATION_SECONDS = 0.5f;

    private static final int UNIT_SCENE = 0;
    private static final int UNIT_DEPTH = 1;
    private static final int UNIT_BRIGHT = 2;
    private static final int UNIT_HORIZON = 3;
    private static final int UNIT_NOISE = 4;
    private static final int UNIT_CLOUDS = 5;
    private static final int UNIT_CALIBRATION = 6;
    private static final int UNIT_CLOUD_DISTANCE = 7;
    private static final int UNIT_CIRRUS = 8;
    private static final int UNIT_PANORAMA = 9;
    private static final int UNIT_SKY_BEHIND = 10;
    private static final int UNIT_HISTORY = 11;
    // The temporal pass reads no panorama, so its history's distances take that unit; above
    // eleven the backend caches no bindings.
    private static final int UNIT_HISTORY_DISTANCE = UNIT_PANORAMA;
    private static final int UNIT_COUNT = 12;
    /**
     * Brightness bands the clouds' calibration keeps apart, since the tone mapper bends dark
     * and bright cloud differently.
     */
    private static final int CALIBRATION_BINS = 8;
    /** Bands of the sine of elevation, nadir to zenith, the sky behind the clouds is kept for. */
    private static final int SKY_BANDS = 32;
    /** A camera move this far in a frame is a teleport, after which the clouds' history starts over. */
    private static final double HISTORY_RESET_DISTANCE = 64.0;
    /** Columns across the screen the sky's colour at the horizon is kept for. */
    private static final int HORIZON_BINS = 256;
    /** The sun and the lights {@link FlareSources} keeps. */
    private static final int MAX_SOURCES = FlareSources.MAX + 1;
    /**
     * Blocks beyond which a lamp no longer flares: farther, it covers a pixel or so, and what is
     * measured around it is whatever bright thing lies beside it.
     */
    private static final float MAX_LIGHT_DISTANCE = 64.0f;

    private static final String VERTEX_SHADER = """
            #version 330 core
            void main() {
                // One triangle covering the screen.
                vec2 corner = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    // Remix's images run top to bottom, so screen positions flip to sample them.
    private static final String SCENE_SAMPLING = """
            uniform sampler2D SceneColor;
            uniform vec2 ViewportSize;

            vec3 scene(vec2 uv) {
                return texture(SceneColor, vec2(uv.x, 1.0 - uv.y)).rgb;
            }
            """;

    private static final String DEPTH_SAMPLING = """
            uniform sampler2D SceneDepth;
            uniform float Near;
            uniform float Far;

            // Distance along the view axis; the sky reads as the far plane.
            float distanceAt(vec2 uv) {
                float depth = texture(SceneDepth, vec2(uv.x, 1.0 - uv.y)).r;
                return Near * Far / (Far - depth * (Far - Near));
            }

            bool isSky(float distance) {
                return distance > Far * 0.99;
            }
            """;

    private static final String VIEW_RAY = """
            uniform vec3 CameraForward;
            uniform vec3 CameraRight;
            uniform vec3 CameraUp;
            uniform vec2 TanHalfFov;

            // The ray through a screen position, one block long along the view axis.
            vec3 viewRay(vec2 uv) {
                vec2 ndc = uv * 2.0 - 1.0;
                return CameraForward + CameraRight * (ndc.x * TanHalfFov.x) + CameraUp * (ndc.y * TanHalfFov.y);
            }
            """;

    // The image is display encoded; light adds up only once decoded.
    private static final String COLOUR_SPACE = """
            vec3 srgbToLinear(vec3 c) {
                c = max(c, vec3(0.0));
                return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c));
            }

            vec3 linearToSrgb(vec3 c) {
                c = max(c, vec3(0.0));
                return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
            }
            """;

    // Per screen column, the sky just above the lowest point it shows: the colour distant land
    // fades toward. A column showing no sky is left empty for the fill pass.
    private static final String HORIZON_FRAGMENT_SHADER = """
            #version 330 core
            out vec4 fragColor;
            """ + SCENE_SAMPLING + DEPTH_SAMPLING + """

            const int ROWS = 128;

            void main() {
                float u = gl_FragCoord.x / ViewportSize.x;
                for (int i = 0; i < ROWS; i++) {
                    float v = (float(i) + 0.5) / float(ROWS);
                    // A little above the land's edge, where antialiasing no longer mixes it in.
                    vec2 above = vec2(u, min(v + 2.0 / float(ROWS), 1.0));
                    vec2 higher = vec2(u, min(v + 4.0 / float(ROWS), 1.0));
                    // The sky must run on up to where its colour is read: a lone texel with
                    // nothing drawn, a gap in leaves that the unantialiased depth shows on some
                    // frames and not others, would read the land and flicker the haze.
                    if (isSky(distanceAt(vec2(u, v))) && isSky(distanceAt(above)) && isSky(distanceAt(higher))) {
                        fragColor = vec4((scene(above) + scene(higher)) * 0.5, 1.0);
                        return;
                    }
                }
                fragColor = vec4(0.0);
            }
            """;

    // Gives a column without sky the nearest column's, and keeps last frame's when no column
    // shows any sky, as when looking straight down or in a cave.
    private static final String HORIZON_FILL_FRAGMENT_SHADER = """
            #version 330 core
            out vec4 fragColor;
            uniform sampler2D HorizonRaw;
            uniform sampler2D HorizonPrevious;

            void main() {
                int x = int(gl_FragCoord.x);
                int columns = textureSize(HorizonRaw, 0).x;
                vec4 here = texelFetch(HorizonRaw, ivec2(x, 0), 0);
                if (here.a > 0.0) {
                    fragColor = here;
                    return;
                }
                for (int d = 1; d < columns; d++) {
                    vec4 left = x - d >= 0 ? texelFetch(HorizonRaw, ivec2(x - d, 0), 0) : vec4(0.0);
                    vec4 right = x + d < columns ? texelFetch(HorizonRaw, ivec2(x + d, 0), 0) : vec4(0.0);
                    if (left.a > 0.0 || right.a > 0.0) {
                        fragColor = vec4((left.rgb * left.a + right.rgb * right.a) / (left.a + right.a), 1.0);
                        return;
                    }
                }
                fragColor = texelFetch(HorizonPrevious, ivec2(x, 0), 0);
            }
            """;

    // Averages each column with its neighbours across a sixth of the screen, so a column whose
    // lowest sky is a cloud does not stripe the haze against the columns beside it.
    private static final String HORIZON_SMOOTH_FRAGMENT_SHADER = """
            #version 330 core
            out vec4 fragColor;
            uniform sampler2D HorizonRaw;

            const int RADIUS = 24;

            void main() {
                int x = int(gl_FragCoord.x);
                int columns = textureSize(HorizonRaw, 0).x;
                vec4 sum = vec4(0.0);
                float weights = 0.0;
                for (int d = -RADIUS; d <= RADIUS; d++) {
                    int column = clamp(x + d, 0, columns - 1);
                    float weight = float(RADIUS + 1 - abs(d));
                    vec4 colour = texelFetch(HorizonRaw, ivec2(column, 0), 0);
                    sum += vec4(colour.rgb * colour.a, colour.a) * weight;
                    weights += weight;
                }
                fragColor = sum.a > 0.5 * weights ? vec4(sum.rgb / sum.a, 1.0) : vec4(0.0);
            }
            """;

    // Where the panorama the dome shows holds a direction, as
    // VolumetricCloudGenerator.rowCoordinate lays it out, and what it shows there. The includer
    // puts CloudField.defines() first.
    private static final String PANORAMA_SAMPLING = """
            uniform sampler2D Panorama;
            // The screen's size in pixels.
            uniform vec2 ScreenSize;

            vec2 panoramaUv(vec3 direction) {
                float elevation = degrees(asin(clamp(direction.y, -1.0, 1.0)));
                float split = float(CLOUD_UPPER_ROWS) / float(CLOUD_HEIGHT);
                float row = elevation >= MIN_ELEVATION_DEGREES
                    ? (90.0 - elevation) / (90.0 - MIN_ELEVATION_DEGREES) * split
                    : split + sqrt((MIN_ELEVATION_DEGREES - elevation) / (MIN_ELEVATION_DEGREES + 90.0)) * (1.0 - split);
                return vec2(atan(direction.x, -direction.z) * (0.5 / 3.14159265358979323846), row);
            }

            // Premultiplied light and opacity toward a screen position, as the image shows them.
            // The panorama is read over the pixel's footprint, as the runtime mipmaps the dome's
            // texture. Where a march grazes a cloud's flat base, its texels speckle between the
            // shaded base and the lit cloud; the runtime's denoiser smooths that speckle out of
            // the image but keeps the clouds' outlines. So the colour is averaged over the
            // neighbours whose opacity matches, and the opacity only deep inside the cloud: read
            // texel by texel, the speckle showed through the lens's clouds as grainy patches on
            // their bases, and with the opacity averaged out to the soft edges, the outlines
            // spread into pale rims against the sky.
            vec4 shownClouds(vec2 uv) {
                vec2 centre = panoramaUv(normalize(viewRay(uv)));
                vec2 dx = panoramaUv(normalize(viewRay(uv + vec2(1.0 / ScreenSize.x, 0.0)))) - centre;
                vec2 dy = panoramaUv(normalize(viewRay(uv + vec2(0.0, 1.0 / ScreenSize.y)))) - centre;
                // Across the seam where the azimuth wraps.
                dx.x -= round(dx.x);
                dy.x -= round(dy.x);
                vec4 middle = textureGrad(Panorama, centre, dx, dy);
                vec2 texel = 1.0 / vec2(textureSize(Panorama, 0));
                vec4 sum = middle;
                float weights = 1.0;
                for (int y = -1; y <= 1; y++) {
                    for (int x = -1; x <= 1; x++) {
                        if (x != 0 || y != 0) {
                            vec4 near = textureGrad(Panorama, centre + vec2(x, y) * texel, dx, dy);
                            float w = max(1.0 - abs(near.a - middle.a) * 8.0, 0.0);
                            sum += near * w;
                            weights += w;
                        }
                    }
                }
                float opacity = mix(middle.a, sum.a / weights, smoothstep(0.7, 0.95, middle.a));
                vec4 cloud = vec4(sum.rgb / max(sum.a, 1.0e-4) * opacity, opacity);
                return vec4(max(cloud.rgb, vec3(0.0)), clamp(cloud.a, 0.0, 1.0));
            }
            """;

    // The light the image shows for clouds, from the calibration's brightness bands.
    private static final String CLOUD_EXPOSURE = "const int CALIBRATION_BINS = " + CALIBRATION_BINS + ";\n" + """
            uniform sampler2D CloudCalibration;

            const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

            // A calibration band, or the nearest one that has seen opaque cloud.
            vec4 calibrationBin(int bin) {
                for (int d = 0; d < CALIBRATION_BINS; d++) {
                    vec4 below = texelFetch(CloudCalibration, ivec2(max(bin - d, 0), 0), 0);
                    if (below.a > 0.0) {
                        return below;
                    }
                    vec4 above = texelFetch(CloudCalibration, ivec2(min(bin + d, CALIBRATION_BINS - 1), 0), 0);
                    if (above.a > 0.0) {
                        return above;
                    }
                }
                return vec4(1.0, 1.0, 1.0, 0.0);
            }

            // What the image multiplies a cloud's decoded light by, between the bands around its brightness.
            vec3 cloudExposure(vec3 colour) {
                float position = clamp(dot(colour, LUMA) * float(CALIBRATION_BINS) - 0.5, 0.0,
                    float(CALIBRATION_BINS - 1));
                int low = int(position);
                return mix(calibrationBin(low).rgb, calibrationBin(min(low + 1, CALIBRATION_BINS - 1)).rgb,
                    position - float(low));
            }

            // The decoded light the image shows for clouds of this premultiplied light and
            // opacity: the dome's colour, clamped per channel as the sky is, at its exposure,
            // over their share.
            vec3 cloudLight(vec4 cloud) {
                if (cloud.a <= 1.0e-3) {
                    return vec3(0.0);
                }
                vec3 colour = min(cloud.rgb / cloud.a, vec3(1.0));
                return cloudExposure(colour) * srgbToLinear(colour) * cloud.a;
            }

            // The same, but brought within the sky's range by scaling, for the difference between
            // the lens's clouds and the dome's: clamped per channel, a bright sunset rim lost its
            // blue first, and where the lens's sharper rim and the dome's softer one differ the
            // difference showed as a yellow and magenta fringe.
            vec3 cloudLightInRange(vec4 cloud) {
                if (cloud.a <= 1.0e-3) {
                    return vec3(0.0);
                }
                vec3 colour = cloud.rgb / cloud.a;
                colour /= max(1.0, max(colour.r, max(colour.g, colour.b)));
                return cloudExposure(colour) * srgbToLinear(colour) * cloud.a;
            }
            """;

    // The clouds' field reads these, by the names cloud_field.glsl expects. Each pixel's march
    // has fewer steps through the cumulus than the dome's, since the history averages several
    // frames' marches, and more through the altostratus, which the dome's panorama blurs but
    // a pixel resolves. The includer puts CloudField.defines() first.
    private static final String CLOUD_FIELD = """
            #define CLOUD_MIN_STEPS 20
            #define CLOUD_MAX_STEPS 40
            #define CLOUD_ALTO_STEPS 6
            const float PI = 3.14159265358979323846;
            uniform sampler2D Cirrus;
            uniform float CirrusAngle;
            uniform float CirrusStrength;
            uniform sampler3D Noise;
            uniform float CloudBase;
            uniform float CloudThickness;
            uniform float Coverage;
            uniform float Density;
            uniform float FeatureScale;
            uniform float Detail;
            uniform vec3 LightDirection;
            uniform vec3 LightIrradiance;
            uniform vec3 AmbientTop;
            uniform vec3 AmbientBottom;
            uniform vec2 CameraXZ;
            uniform float CameraHeight;
            uniform float Brightness;
            uniform float HazeDistance;
            """ + CloudField.source();

    // The clouds as the dome's march draws them, at half resolution: premultiplied light and
    // opacity between the camera and the land, or, over the sky, all of them with the cirrus
    // above; the distance along the view axis each texel marched to, Far for the sky; and
    // where along its ray the clouds begin, MAX_DISTANCE with none.
    private static final String CLOUD_MARCH_FRAGMENT_SHADER = """
            #version 330 core
            layout(location = 0) out vec4 fragColor;
            layout(location = 1) out vec2 fragDistance;
            uniform vec2 ViewportSize;
            uniform float FrameIndex;
            """ + CloudField.defines() + DEPTH_SAMPLING + VIEW_RAY + CLOUD_FIELD + """

            void main() {
                // Of the four pixels under this texel, alternate texels march to the nearest land
                // and to the farthest, so along a silhouette, or a ridge against the sky, the
                // upsample finds texels on both sides.
                bool nearest = ((int(gl_FragCoord.x) + int(gl_FragCoord.y)) & 1) == 0;
                float axial = -1.0;
                vec2 uv = vec2(0.0);
                for (int i = 0; i < 4; i++) {
                    vec2 sampleUv = (gl_FragCoord.xy + vec2(float(i & 1), float(i >> 1)) * 0.5 - 0.25) / ViewportSize;
                    float distance = distanceAt(sampleUv);
                    if (!isSky(distance) && (axial < 0.0 || (nearest ? distance < axial : distance > axial))) {
                        axial = distance;
                        uv = sampleUv;
                    }
                }
                // Interleaved gradient noise, stepped by the golden ratio each frame, so the
                // history averages the march's steps away.
                float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715)))
                    + FrameIndex * 0.61803398875);
                float entry;
                if (axial < 0.0) {
                    vec3 direction = normalize(viewRay(gl_FragCoord.xy / ViewportSize));
                    vec4 clouds = marchCloudsTo(direction, jitter, MAX_DISTANCE, entry);
                    fragColor = clouds + (1.0 - clouds.a) * cirrusLayer(direction);
                    fragDistance = vec2(Far, entry);
                    return;
                }
                vec3 ray = viewRay(uv);
                float rayLength = length(ray);
                fragColor = marchCloudsTo(ray / rayLength, jitter, axial * rayLength, entry);
                fragDistance = vec2(axial, entry);
            }
            """;

    // This frame's march folded into last frame's result, found where the camera saw the same
    // clouds: at their entry, or the land behind when there are none, moved by the camera's
    // motion. A history whose texel saw sky where this one sees land, or land at another
    // depth, is not taken, and one outside this texel's neighbours' range is pulled into it,
    // so moving clouds and parallax leave no trails.
    private static final String CLOUD_TEMPORAL_FRAGMENT_SHADER = """
            #version 330 core
            layout(location = 0) out vec4 fragColor;
            layout(location = 1) out vec2 fragDistance;
            """ + DEPTH_SAMPLING + VIEW_RAY + """
            uniform sampler2D CloudLight;
            uniform sampler2D CloudDistance;
            uniform sampler2D CloudHistory;
            uniform sampler2D HistoryDistance;
            uniform vec2 ViewportSize;
            uniform mat4 PreviousViewProjection;
            uniform vec3 CameraMotion;
            uniform float HistoryValid;

            // The march's share of each frame: about twenty frames averaged while the view holds
            // still, fewer as what this texel shows moves across the screen, so the clouds
            // settle smooth yet follow a turning camera.
            const float STILL_WEIGHT = 0.05;
            const float MOVING_WEIGHT = 0.25;
            // Texels of motion over which the weight goes from still to moving.
            const float MOTION_TEXELS = 4.0;
            const float NO_CLOUD = 29000.0;
            // Blocks out a texel with neither cloud nor land is taken to lie.
            const float EMPTY_SKY_DISTANCE = 10000.0;

            void main() {
                ivec2 texel = ivec2(gl_FragCoord.xy);
                ivec2 last = ivec2(ViewportSize) - 1;
                vec4 current = texelFetch(CloudLight, texel, 0);
                vec2 distances = texelFetch(CloudDistance, texel, 0).rg;
                fragDistance = distances;
                bool sky = isSky(distances.r);
                vec4 low = current;
                vec4 high = current;
                for (int y = -1; y <= 1; y++) {
                    for (int x = -1; x <= 1; x++) {
                        ivec2 neighbour = clamp(texel + ivec2(x, y), ivec2(0), last);
                        if (isSky(texelFetch(CloudDistance, neighbour, 0).r) == sky) {
                            vec4 value = texelFetch(CloudLight, neighbour, 0);
                            low = min(low, value);
                            high = max(high, value);
                        }
                    }
                }
                fragColor = current;
                if (HistoryValid <= 0.0) {
                    return;
                }
                vec3 ray = viewRay(gl_FragCoord.xy / ViewportSize);
                float along = distances.g < NO_CLOUD ? distances.g
                    : sky ? EMPTY_SKY_DISTANCE : distances.r * length(ray);
                vec4 clip = PreviousViewProjection * vec4(normalize(ray) * along + CameraMotion, 1.0);
                if (clip.w <= 0.0) {
                    return;
                }
                vec2 previousUv = clip.xy / clip.w * 0.5 + 0.5;
                if (any(lessThan(previousUv, vec2(0.0))) || any(greaterThan(previousUv, vec2(1.0)))) {
                    return;
                }
                float previousDistance = texture(HistoryDistance, previousUv).r;
                bool same = sky ? isSky(previousDistance)
                    : !isSky(previousDistance) && abs(previousDistance - distances.r) < 0.1 * distances.r + 0.5;
                if (same) {
                    float motion = length((previousUv - gl_FragCoord.xy / ViewportSize) * ViewportSize);
                    float weight = mix(STILL_WEIGHT, MOVING_WEIGHT, clamp(motion / MOTION_TEXELS, 0.0, 1.0));
                    fragColor = mix(clamp(texture(CloudHistory, previousUv), low, high), current, weight);
                }
            }
            """;

    // How bright the image shows the sky's clouds against the light they were marched with,
    // per brightness band, one texel each: the sky is drawn as display-encoded colour,
    // clamped, and exposed and tone mapped with the rest of the frame, so opaque clouds the
    // frame shows in the sky measure what the lens's clouds must be brought to, per channel,
    // since the tone mapper shifts hue at dusk. Only clouds that hide the sky entirely are
    // measured: through a cloud only nine tenths opaque the blue sky is a large share of a
    // dark cloud's colour, and that tinted the dark bands blue. Eased toward from last
    // frame's, and kept while the frame shows no opaque cloud of that brightness in the sky.
    private static final String CLOUD_CALIBRATION_FRAGMENT_SHADER = """
            #version 330 core
            out vec4 fragColor;
            """ + "const int BINS = " + CALIBRATION_BINS + ";\n" + CloudField.defines() + SCENE_SAMPLING
            + DEPTH_SAMPLING + VIEW_RAY + COLOUR_SPACE + PANORAMA_SAMPLING + """
            uniform sampler2D CalibrationPrevious;
            uniform float Follow;

            const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
            const int COLUMNS = 64;
            const int ROWS = 36;

            void main() {
                int bin = int(gl_FragCoord.x);
                vec3 shown = vec3(0.0);
                vec3 marched = vec3(0.0);
                float weights = 0.0;
                for (int y = 0; y < ROWS; y++) {
                    for (int x = 0; x < COLUMNS; x++) {
                        vec2 uv = (vec2(x, y) + 0.5) / vec2(COLUMNS, ROWS);
                        if (!isSky(distanceAt(uv))) {
                            continue;
                        }
                        vec4 cloud = shownClouds(uv);
                        vec3 colour = min(cloud.rgb, vec3(1.0));
                        float w = smoothstep(0.95, 1.0, cloud.a);
                        if (w > 0.0 && min(int(dot(colour, LUMA) * float(BINS)), BINS - 1) == bin) {
                            shown += srgbToLinear(scene(uv)) * w;
                            marched += srgbToLinear(colour) * w;
                            weights += w;
                        }
                    }
                }
                vec4 previous = texelFetch(CalibrationPrevious, ivec2(bin, 0), 0);
                if (weights < 3.0 || min(marched.r, min(marched.g, marched.b)) < 1.0e-4 * weights) {
                    fragColor = previous;
                    return;
                }
                vec3 current = shown / marched;
                fragColor = vec4(previous.a > 0.0 ? mix(previous.rgb, current, Follow) : current, 1.0);
            }
            """;

    // The sky the image shows behind the dome's clouds, decoded, per band of elevation: read
    // through them where they are thin, the image less their light over what they let pass.
    // The lens needs it where its own clouds are thinner than the dome's. Eased toward from
    // last frame's, and kept while no thin cloud shows in a band.
    private static final String SKY_BEHIND_FRAGMENT_SHADER = """
            #version 330 core
            out vec4 fragColor;
            """ + "const int BANDS = " + SKY_BANDS + ";\n" + CloudField.defines() + SCENE_SAMPLING + DEPTH_SAMPLING
            + VIEW_RAY + COLOUR_SPACE + PANORAMA_SAMPLING + CLOUD_EXPOSURE + """
            uniform sampler2D SkyBehindPrevious;
            uniform float Follow;

            const int COLUMNS = 64;
            const int ROWS = 36;

            void main() {
                int band = int(gl_FragCoord.x);
                vec3 sum = vec3(0.0);
                float weights = 0.0;
                for (int y = 0; y < ROWS; y++) {
                    for (int x = 0; x < COLUMNS; x++) {
                        vec2 uv = (vec2(x, y) + 0.5) / vec2(COLUMNS, ROWS);
                        if (!isSky(distanceAt(uv))) {
                            continue;
                        }
                        vec3 direction = normalize(viewRay(uv));
                        if (clamp(int((direction.y * 0.5 + 0.5) * float(BANDS)), 0, BANDS - 1) != band) {
                            continue;
                        }
                        vec4 shown = shownClouds(uv);
                        float w = 1.0 - smoothstep(0.2, 0.6, shown.a);
                        if (w > 0.0) {
                            sum += max(srgbToLinear(scene(uv)) - cloudLight(shown), 0.0) / (1.0 - shown.a) * w;
                            weights += w;
                        }
                    }
                }
                vec4 previous = texelFetch(SkyBehindPrevious, ivec2(band, 0), 0);
                if (weights < 1.0) {
                    fragColor = previous;
                    return;
                }
                vec3 current = sum / weights;
                fragColor = vec4(previous.a > 0.0 ? mix(previous.rgb, current, Follow) : current, 1.0);
            }
            """;

    private static final String BRIGHT_FRAGMENT_SHADER = """
            #version 330 core
            out vec4 fragColor;
            """ + SCENE_SAMPLING + """

            // Only what the tone mapper nearly saturated flares, as only a lens's brightest sources do.
            const float THRESHOLD = 0.8;

            void main() {
                // Each half-resolution texel centre falls between four scene texels, which the
                // linear filter averages.
                vec3 colour = scene(gl_FragCoord.xy / ViewportSize);
                fragColor = vec4(max(colour - THRESHOLD, 0.0) / (1.0 - THRESHOLD), 1.0);
            }
            """;

    private static final String COMPOSITE_FRAGMENT_SHADER = """
            #version 330 core
            out vec4 fragColor;
            """ + "const int MAX_SOURCES = " + MAX_SOURCES + ";\n" + "const int SKY_BANDS = " + SKY_BANDS + ";\n"
            + CloudField.defines() + SCENE_SAMPLING + DEPTH_SAMPLING + VIEW_RAY + COLOUR_SPACE + CLOUD_FIELD
            + PANORAMA_SAMPLING + CLOUD_EXPOSURE + """

            uniform sampler2D Bright;
            uniform sampler2D HorizonSky;
            uniform sampler2D CloudLight;
            uniform sampler2D CloudDistance;
            uniform sampler2D SkyBehind;
            uniform vec2 CloudSize;
            uniform float Clouds;
            uniform float Haze;
            // The rain's strength, 0 to 1.
            uniform float Rain;
            // How dark the clouds' shadows on the land are, 0 to 1.
            uniform float CloudShadow;
            uniform float CameraY;
            uniform float FocusDistance;
            uniform float DepthOfField;
            uniform float Flare;
            uniform float Aspect;
            // Each light that may flare: where it lands on screen, in z 1 for the sun and 2 for a
            // flame in the player's hand, and in w its distance along the view axis, at which the
            // image must show something there.
            uniform vec4 Sources[MAX_SOURCES];
            uniform int SourceCount;

            // Blur radius, as a share of the screen height, per dioptre the distance is out of
            // focus, and its cap: a light blur, a few pixels for the far distance at 1080p.
            const float BLUR_PER_DIOPTRE = 0.02;
            const float MAX_BLUR = 0.008;
            // Nearer than this is the player's own hand, which stays sharp.
            const float HAND_DISTANCE = 1.5;
            const int TAPS = 24;
            const float GOLDEN_ANGLE = 2.39996323;
            // Ghosts along the line from a light through the centre: where on it (0 at the light,
            // 1 at the centre), how large as a share of the screen height, and their tint.
            const int GHOSTS = 5;
            const float GHOST_AT[GHOSTS] = float[](0.45, 0.8, 1.25, 1.55, 1.95);
            const float GHOST_RADIUS[GHOSTS] = float[](0.025, 0.07, 0.04, 0.11, 0.055);
            const vec3 GHOST_TINT[GHOSTS] = vec3[](vec3(1.0, 0.7, 0.4), vec3(0.6, 1.0, 0.7),
                vec3(0.5, 0.7, 1.0), vec3(0.8, 0.6, 1.0), vec3(1.0, 0.8, 0.5));
            const float GHOST_GAIN = 0.05;
            const float RING_AT = 1.15;
            const float RING_RADIUS = 0.2;
            const float RING_WIDTH = 0.012;
            const float RING_GAIN = 0.06;
            const float GLOW_GAIN = 0.2;
            const float STAR_GAIN = 0.8;

            // Aerial perspective: air thins with height, so the haze a path crosses is its length
            // times the mean density along it, which the exponential falloff integrates exactly.
            const float HAZE_PER_BLOCK = 0.000866;
            const float HAZE_SEA_LEVEL = 63.0;
            const float HAZE_SCALE_HEIGHT = 300.0;
            // Rain fills the air below the clouds evenly and closes the view to about two
            // kilometres. The runtime's own rain fog mostly absorbs, darkening distant land
            // below the overcast sky; here it scatters the sky's grey back in, as rain does.
            const float RAIN_HAZE_PER_BLOCK = 0.002;

            float blurRadius(float distance) {
                if (distance < HAND_DISTANCE) {
                    return 0.0;
                }
                float defocus = abs(1.0 / FocusDistance - 1.0 / distance);
                return min(defocus * BLUR_PER_DIOPTRE, MAX_BLUR) * DepthOfField * ViewportSize.y;
            }

            vec3 depthOfField(vec2 uv) {
                vec3 centre = scene(uv);
                float distance = distanceAt(uv);
                float radius = blurRadius(distance);
                if (radius < 0.5) {
                    return centre;
                }
                vec3 sum = centre;
                float weight = 1.0;
                for (int i = 0; i < TAPS; i++) {
                    float r = sqrt((float(i) + 0.5) / float(TAPS)) * radius;
                    float angle = float(i) * GOLDEN_ANGLE;
                    vec2 sampleUv = uv + vec2(cos(angle), sin(angle)) * r / ViewportSize;
                    float sampleDistance = distanceAt(sampleUv);
                    // Something nearer and sharper than this pixel does not spread over it.
                    float w = sampleDistance < distance
                        ? smoothstep(r - 1.0, r + 1.0, blurRadius(sampleDistance))
                        : 1.0;
                    sum += scene(sampleUv) * w;
                    weight += w;
                }
                return sum / weight;
            }

            vec3 bright(vec2 uv, float lod) {
                return textureLod(Bright, uv, lod).rgb;
            }

            // How bright the image is where a light lands: the largest of a few taps around it, so
            // a light behind a block, or too dim against the daylight, does not flare.
            vec3 lightAt(vec2 uv) {
                vec2 step = 4.0 / ViewportSize;
                vec3 b = bright(uv, 1.0);
                b = max(b, bright(uv + vec2(step.x, 0.0), 1.0));
                b = max(b, bright(uv - vec2(step.x, 0.0), 1.0));
                b = max(b, bright(uv + vec2(0.0, step.y), 1.0));
                return max(b, bright(uv - vec2(0.0, step.y), 1.0));
            }

            // Whether the image shows the lamp itself around where a light lands: something at the
            // light's own distance. Nearer, a block hides it; farther, the lamp is not drawn there,
            // as with the hand hidden, and only what lies behind would be measured.
            bool showsLight(vec2 uv, float lightDistance) {
                vec2 step = 4.0 / ViewportSize;
                float tolerance = 0.6 + 0.1 * lightDistance;
                return abs(distanceAt(uv) - lightDistance) < tolerance
                    || abs(distanceAt(uv + vec2(step.x, 0.0)) - lightDistance) < tolerance
                    || abs(distanceAt(uv - vec2(step.x, 0.0)) - lightDistance) < tolerance
                    || abs(distanceAt(uv + vec2(0.0, step.y)) - lightDistance) < tolerance
                    || abs(distanceAt(uv - vec2(0.0, step.y)) - lightDistance) < tolerance;
            }

            // A ghost is the aperture's image: a faint disc with a brighter rim.
            float ghost(float r, float radius) {
                float inside = 1.0 - smoothstep(radius * 0.93, radius, r);
                return inside * (0.35 + 0.65 * smoothstep(radius * 0.6, radius * 0.97, r));
            }

            vec3 spectrum(float t) {
                float hue = (1.0 - t) * 0.75;
                return clamp(abs(fract(hue + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
            }

            // Reflections between a lens's elements: ghosts along the line from the light through
            // the centre, their colours parted at the rims, a thin rainbow ring, and a glow on the
            // light itself. Only shapes, never a copy of the image, so nothing but lights flares.
            vec3 flareFrom(vec2 uv, vec2 lightUv, vec3 light) {
                vec2 scale = vec2(Aspect, 1.0);
                vec2 p = uv * scale;
                vec2 source = lightUv * scale;
                vec2 axis = vec2(0.5) * scale - source;
                vec3 sum = vec3(0.0);
                for (int i = 0; i < GHOSTS; i++) {
                    float r = length(p - (source + axis * GHOST_AT[i]));
                    float radius = GHOST_RADIUS[i];
                    vec3 fringed = vec3(ghost(r, radius * 1.02), ghost(r, radius), ghost(r, radius * 0.98));
                    sum += fringed * GHOST_TINT[i] * GHOST_GAIN;
                }
                float ring = (length(p - (source + axis * RING_AT)) - RING_RADIUS + RING_WIDTH) / (2.0 * RING_WIDTH);
                if (ring > 0.0 && ring < 1.0) {
                    sum += spectrum(1.0 - ring) * sin(PI * ring) * RING_GAIN;
                }
                sum += vec3(exp(-length(p - source) * 25.0) * GLOW_GAIN);
                return sum * light;
            }

            // Diffraction spikes off the aperture blades, six strong and six faint between them.
            vec3 starburst(vec2 uv, vec2 sunUv, vec3 sun) {
                vec2 offset = (uv - sunUv) * vec2(Aspect, 1.0);
                float r = length(offset);
                float angle = atan(offset.y, offset.x);
                float rays = pow(abs(cos(3.0 * angle)), 90.0) + 0.5 * pow(abs(cos(3.0 * angle + PI / 6.0)), 160.0);
                float star = rays * exp(-r * 6.0) + 0.5 * exp(-r * 30.0);
                return sun * star * STAR_GAIN;
            }

            vec4 horizonSky(float u) {
                return texture(HorizonSky, vec2(u, 0.5));
            }

            // The marched clouds at half resolution, gathered from the texels around this pixel
            // that see what it sees: over the land, the land texels, mostly those at its depth,
            // so the clouds neither bleed across a silhouette nor show the march's dither; over
            // the sky, the sky texels. No texel to take leaves weights at 0.
            vec4 gatherClouds(vec2 uv, float axial, bool sky, out float weights) {
                vec2 position = uv * CloudSize - 0.5;
                ivec2 corner = ivec2(floor(position));
                vec2 f = position - vec2(corner);
                ivec2 last = ivec2(CloudSize) - 1;
                float tolerance = 0.03 * axial + 0.3;
                vec4 sum = vec4(0.0);
                weights = 0.0;
                for (int y = -1; y <= 2; y++) {
                    for (int x = -1; x <= 2; x++) {
                        ivec2 texel = clamp(corner + ivec2(x, y), ivec2(0), last);
                        float texelDistance = texelFetch(CloudDistance, texel, 0).r;
                        if (isSky(texelDistance) != sky) {
                            continue;
                        }
                        vec2 d = vec2(x, y) - f;
                        float w = exp(-0.5 * dot(d, d));
                        if (!sky) {
                            // A floor under the depth weight, so a pixel no texel shares a depth
                            // with, a fence post say, still takes its neighbours' clouds.
                            w *= exp(-abs(texelDistance - axial) / tolerance) + 1.0e-3;
                        }
                        sum += texelFetch(CloudLight, texel, 0) * w;
                        weights += w;
                    }
                }
                return weights > 0.0 ? sum / weights : vec4(0.0);
            }

            // The distance haze and clouds take at a pixel: its own, or, for a sky pixel beside
            // the land, the farthest land beside it. The image is antialiased and the depth is
            // not, so along a silhouette a pixel shows part land and part sky while its depth,
            // jittered from frame to frame, says one or the other; a sky pixel left unhazed
            // there shows the land's own dark colour, a dark outline that crawls along every
            // distant ridge. The sky behind a ridge is nearly the haze's colour, so hazing its
            // share of the pixel too changes nothing visible.
            float landDistance(vec2 uv) {
                float axial = distanceAt(uv);
                if (!isSky(axial)) {
                    return axial;
                }
                vec2 texel = 1.0 / vec2(textureSize(SceneDepth, 0));
                float land = axial;
                for (int y = -1; y <= 1; y++) {
                    for (int x = -1; x <= 1; x++) {
                        float neighbour = distanceAt(uv + vec2(x, y) * texel);
                        if (!isSky(neighbour) && (isSky(land) || neighbour > land)) {
                            land = neighbour;
                        }
                    }
                }
                return land;
            }

            // The clouds in front of the land, at the brightness the dome shows its own clouds.
            vec3 cloudsOverLand(vec2 uv, vec3 colour, float axial) {
                float weights;
                vec4 cloud = gatherClouds(uv, axial, false, weights);
                if (cloud.a <= 1.0e-3) {
                    return colour;
                }
                return linearToSrgb(srgbToLinear(colour) * (1.0 - cloud.a) + cloudLight(cloud));
            }

            // A band of the sky behind the clouds, or the nearest one that has seen it.
            vec4 skyBand(int band) {
                for (int d = 0; d < SKY_BANDS; d++) {
                    vec4 below = texelFetch(SkyBehind, ivec2(max(band - d, 0), 0), 0);
                    if (below.a > 0.0) {
                        return below;
                    }
                    vec4 above = texelFetch(SkyBehind, ivec2(min(band + d, SKY_BANDS - 1), 0), 0);
                    if (above.a > 0.0) {
                        return above;
                    }
                }
                return vec4(0.0);
            }

            vec3 skyBehindAt(float rise) {
                float position = clamp((rise * 0.5 + 0.5) * float(SKY_BANDS) - 0.5, 0.0, float(SKY_BANDS - 1));
                int low = int(position);
                return mix(skyBand(low).rgb, skyBand(min(low + 1, SKY_BANDS - 1)).rgb, position - float(low));
            }

            // The marched clouds over the sky in place of the dome's. Where the dome's clouds are
            // thin the sky behind them reads straight out of the image, and where they hide it,
            // from its band of elevation; where the two marches agree the image is unchanged.
            // Rain closes the sky into a featureless overcast the dome shows as well, and its
            // lightning changes the light faster than the dome follows, so rain hands the sky
            // back to the dome.
            vec3 cloudsOverSky(vec2 uv, vec3 colour) {
                float weights;
                vec4 marched = gatherClouds(uv, Far, true, weights);
                if (weights <= 0.0 || Rain >= 1.0) {
                    return colour;
                }
                vec3 direction = normalize(viewRay(uv));
                vec4 shown = shownClouds(uv);
                vec3 shownLight = cloudLight(shown);
                vec3 image = srgbToLinear(colour);
                // Read through the dome's clouds only where they are nearly clear: dividing by
                // what they let pass magnifies any error in their light, which coloured the rims
                // of sunset clouds magenta and yellow; and kept near the band's colour.
                vec3 band = skyBehindAt(direction.y);
                vec3 behind = max(image - shownLight, 0.0) / max(1.0 - shown.a, 1.0e-3);
                behind = clamp(behind, band * 0.5, band * 2.0 + 0.01);
                behind = mix(band, behind, smoothstep(0.4, 0.1, shown.a));
                vec3 change = cloudLightInRange(marched) - cloudLightInRange(shown);
                // Where the lens's clouds are thicker, they hide more of the sky, but never more
                // sky than the image holds beside the dome's clouds: a band brightened by a
                // lightning flash otherwise took the image below black.
                vec3 hidden = (marched.a - shown.a) * behind;
                if (marched.a > shown.a) {
                    hidden = min(hidden, max(image - shownLight, 0.0));
                }
                return mix(colour, linearToSrgb(max(image + change - hidden, 0.0)), 1.0 - Rain);
            }

            // The land in the clouds' shadows: the light the clouds above stop, times the share of
            // the land's light, per channel, that comes straight from the sun or moon rather than
            // the sky, which falls as the light sinks. At the setting's default a full shadow
            // keeps the sky's blue share and a little bounce, as dark as Remix's own shadow of a
            // block. Your hand lies in the shadow you stand in.
            const float SHADOW_DEPTH_AT_DEFAULT = 0.8;

            vec3 cloudShadows(vec2 uv, vec3 colour) {
                float axial = landDistance(uv);
                if (isSky(axial) || CloudShadow <= 0.0) {
                    return colour;
                }
                vec3 ray = viewRay(uv);
                float along = axial < HAND_DISTANCE ? 0.0 : axial * length(ray);
                vec3 world = vec3(CameraXZ.x, CameraHeight, CameraXZ.y) + normalize(ray) * along;
                vec3 direct = LightIrradiance * max(LightDirection.y, 0.0);
                vec3 share = direct / max(direct + AmbientTop, vec3(1.0e-4));
                vec3 darkening = min(vec3(0.95), 2.0 * CloudShadow * SHADOW_DEPTH_AT_DEFAULT * share
                    * (1.0 - shadowTransmittance(world)));
                return linearToSrgb(srgbToLinear(colour) * (1.0 - darkening));
            }

            vec3 clouds(vec2 uv, vec3 colour) {
                float axial = landDistance(uv);
                return isSky(axial) ? cloudsOverSky(uv, colour) : cloudsOverLand(uv, colour, axial);
            }

            vec3 aerialHaze(vec2 uv, vec3 colour) {
                float axial = landDistance(uv);
                vec4 sky = horizonSky(uv.x);
                if (isSky(axial) || axial < HAND_DISTANCE || sky.a <= 0.0) {
                    return colour;
                }
                vec3 ray = viewRay(uv);
                float pathLength = axial * length(ray);
                float rise = axial * ray.y / HAZE_SCALE_HEIGHT;
                float mean = abs(rise) > 1.0e-3 ? (1.0 - exp(-rise)) / rise : 1.0 - 0.5 * rise;
                float startDensity = exp(-(CameraY - HAZE_SEA_LEVEL) / HAZE_SCALE_HEIGHT);
                float opticalDepth = Haze * pathLength
                    * (HAZE_PER_BLOCK * min(startDensity * mean, 4.0) + RAIN_HAZE_PER_BLOCK * Rain);
                return mix(colour, sky.rgb, 1.0 - exp(-opticalDepth));
            }

            void main() {
                vec2 uv = gl_FragCoord.xy / ViewportSize;
                vec3 colour = DepthOfField > 0.0 ? depthOfField(uv) : scene(uv);
                if (Clouds > 0.0) {
                    colour = cloudShadows(uv, colour);
                }
                if (Haze > 0.0) {
                    colour = aerialHaze(uv, colour);
                }
                if (Clouds > 0.0) {
                    colour = clouds(uv, colour);
                }
                if (Flare > 0.0) {
                    vec3 flare = vec3(0.0);
                    for (int i = 0; i < SourceCount; i++) {
                        vec2 lightUv = Sources[i].xy;
                        if (Sources[i].z < 0.5 && !showsLight(lightUv, Sources[i].w)) {
                            continue;
                        }
                        vec3 light = lightAt(lightUv);
                        if (abs(Sources[i].z - 1.0) < 0.5) {
                            // The sun stands in a bright sky, so its own glow decides the strength.
                            light = bright(lightUv, 3.0);
                            flare += starburst(uv, lightUv, light);
                        }
                        flare += flareFrom(uv, lightUv, light);
                    }
                    colour += flare * Flare;
                }
                fragColor = vec4(colour, 1.0);
            }
            """;

    private final Matrix4f view = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    private final Vector4f clip = new Vector4f();
    /** Per source: screen u, v, 1 for the sun, and padding, as the shader's Sources array. */
    private final float[] sources = new float[MAX_SOURCES * 4];
    private float aspect = 16.0f / 9.0f;
    private float rain;
    private float farPlane = 1000.0f;
    private int screenWidth = 1;
    private int screenHeight = 1;
    private double cameraX;
    private double cameraY;
    private double cameraZ;
    /** Camera forward, right and up, then the tangents of half the field of view across and up. */
    private final float[] cameraBasis = new float[9];
    private float tanHalfFovX;
    private float tanHalfFovY;
    private float focusDioptres = 1.0f / (float) MAX_FOCUS_DISTANCE;
    private final int[] previousViewport = new int[4];
    private final int[] previousSamplers = new int[UNIT_COUNT];
    private long lastFocusNanos;
    private final float[] sunDirection = new float[3];

    private Program brightProgram;
    private Program horizonProgram;
    private Program horizonFillProgram;
    private Program horizonSmoothProgram;
    private int horizonSmooth;
    private Program compositeProgram;
    private int horizonRaw;
    /** Ping-ponged, so a frame without sky can keep the last frame's colours. */
    private final int[] horizonFilled = new int[2];
    private int horizonCurrent;
    private Program cloudMarchProgram;
    private Program cloudCalibrationProgram;
    private Program skyBehindProgram;
    /** Ping-ponged, one row of bands each, so the sky behind the clouds eases from last frame's. */
    private final int[] skyBehind = new int[2];
    private int skyBehindCurrent;
    private int cloudTexture;
    private int cloudDistanceTexture;
    private int cloudFramebuffer;
    private Program cloudTemporalProgram;
    /** Ping-ponged: the accumulated clouds and the distances each texel saw them at. */
    private final int[] cloudHistory = new int[2];
    private final int[] historyDistance = new int[2];
    private int historyCurrent;
    private int temporalFramebuffer;
    private boolean historyValid;
    private int cloudFrame;
    private final Matrix4f viewProjection = new Matrix4f();
    private final Matrix4f previousViewProjection = new Matrix4f();
    private double previousCameraX;
    private double previousCameraY;
    private double previousCameraZ;
    private int cloudWidth;
    private int cloudHeight;
    /** Ping-ponged, one row of bands each, so the clouds' brightness eases from last frame's. */
    private final int[] calibration = new int[2];
    private int calibrationCurrent;
    private float calibrationFollow = 1.0f;
    private int vertexArray;
    private int framebuffer;
    private int brightTexture;
    private int brightWidth;
    private int brightHeight;
    private int sceneSampler;
    private int depthSampler;
    private int brightSampler;
    private int panoramaSampler;

    /**
     * Takes this frame's camera, its projection and the distance the lens focuses at, which
     * follows what the view rests on.
     *
     * @param sunDirection unit vector toward the sun; one pointing down has no flare
     */
    public void updateCamera(Camera camera, Level level, float fovYDegrees, float aspect, float farPlane,
                             float[] sunDirection) {
        this.aspect = aspect;
        this.farPlane = farPlane;
        rain = level == null ? 0.0f : level.getRainLevel(1.0f);
        Vec3 position = camera.position();
        cameraX = position.x;
        cameraY = position.y;
        cameraZ = position.z;
        camera.getViewRotationMatrix(view);
        projection.setPerspective((float) Math.toRadians(fovYDegrees), aspect, NEAR_PLANE, farPlane);
        Vector3fc forward = camera.forwardVector();
        Vector3fc left = camera.leftVector();
        Vector3fc up = camera.upVector();
        cameraBasis[0] = forward.x(); cameraBasis[1] = forward.y(); cameraBasis[2] = forward.z();
        cameraBasis[3] = -left.x();   cameraBasis[4] = -left.y();   cameraBasis[5] = -left.z();
        cameraBasis[6] = up.x();      cameraBasis[7] = up.y();      cameraBasis[8] = up.z();
        tanHalfFovY = (float) Math.tan(Math.toRadians(fovYDegrees) * 0.5);
        tanHalfFovX = tanHalfFovY * aspect;
        System.arraycopy(sunDirection, 0, this.sunDirection, 0, 3);
        long now = System.nanoTime();
        float seconds = lastFocusNanos == 0L ? Float.POSITIVE_INFINITY : (now - lastFocusNanos) / 1.0e9f;
        updateFocus(camera, level, now, seconds);
        calibrationFollow = 1.0f - (float) Math.exp(-seconds / CALIBRATION_SECONDS);
    }

    /** Projects the sun and this frame's flare lights onto the screen; returns how many land on it. */
    private int projectSources() {
        int count = 0;
        if (sunDirection[1] > 0.0f) {
            count = addSource(count, sunDirection[0], sunDirection[1], sunDirection[2], 0.0f, true, false);
        }
        for (int i = 0; i < FlareSources.count(); i++) {
            count = addSource(count, (float) (FlareSources.x(i) - cameraX), (float) (FlareSources.y(i) - cameraY),
                    (float) (FlareSources.z(i) - cameraZ), 1.0f, false, FlareSources.held(i));
        }
        return count;
    }

    private int addSource(int count, float x, float y, float z, float w, boolean sun, boolean held) {
        clip.set(x, y, z, w);
        view.transform(clip);
        projection.transform(clip);
        if (clip.w <= 0.0f || (!sun && clip.w > MAX_LIGHT_DISTANCE)) {
            return count;
        }
        float u = clip.x / clip.w * 0.5f + 0.5f;
        float v = clip.y / clip.w * 0.5f + 0.5f;
        if (u < 0.0f || u > 1.0f || v < 0.0f || v > 1.0f) {
            return count;
        }
        int at = count * 4;
        sources[at] = u;
        sources[at + 1] = v;
        sources[at + 2] = sun ? 1.0f : held ? 2.0f : 0.0f;
        sources[at + 3] = clip.w;
        return count + 1;
    }

    private void updateFocus(Camera camera, Level level, long now, float seconds) {
        double target = MAX_FOCUS_DISTANCE;
        if (level != null) {
            Vec3 eye = camera.position();
            Vector3fc forward = camera.forwardVector();
            Vec3 end = eye.add(forward.x() * MAX_FOCUS_DISTANCE, forward.y() * MAX_FOCUS_DISTANCE,
                    forward.z() * MAX_FOCUS_DISTANCE);
            BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.VISUAL,
                    ClipContext.Fluid.NONE, CollisionContext.empty()));
            if (hit.getType() != HitResult.Type.MISS) {
                target = Math.max(MIN_FOCUS_DISTANCE, hit.getLocation().distanceTo(eye));
            }
        }
        lastFocusNanos = now;
        // In dioptres, so a pull between near and far moves as evenly as a lens does.
        float follow = 1.0f - (float) Math.exp(-seconds / FOCUS_SECONDS);
        focusDioptres += ((float) (1.0 / target) - focusDioptres) * follow;
    }

    /**
     * Redraws the main target from {@code sceneColorTexture} with the lens applied, nothing when
     * every effect is off, and starts the next frame's list of lights that may flare. The list
     * is cleared here rather than with the camera, because the hand is captured before the
     * camera arrives and offers its flame then.
     */
    public void render(int sceneColorTexture, int depthTexture) {
        try {
            draw(sceneColorTexture, depthTexture);
        } finally {
            FlareSources.clear();
        }
    }

    private void draw(int sceneColorTexture, int depthTexture) {
        float depthOfField = LensLook.getDepthOfField();
        float flare = LensLook.getFlare();
        float haze = SkyLook.getAerialHaze();
        boolean clouds = CloudField.ready();
        if ((depthOfField <= 0.0f && flare <= 0.0f && haze <= 0.0f && !clouds)
                || sceneColorTexture <= 0 || depthTexture <= 0) {
            return;
        }
        RenderTarget mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (!(mainTarget.getColorTexture() instanceof GlTexture targetColor)) {
            return;
        }
        screenWidth = mainTarget.width;
        screenHeight = mainTarget.height;
        ensureObjects();
        ensureBrightTexture(Math.max(1, mainTarget.width / 2), Math.max(1, mainTarget.height / 2));
        if (clouds) {
            ensureCloudTexture(brightWidth, brightHeight);
        }

        int previousProgram = GL45C.glGetInteger(GL45C.GL_CURRENT_PROGRAM);
        int previousVertexArray = GL45C.glGetInteger(GL45C.GL_VERTEX_ARRAY_BINDING);
        int previousDrawFramebuffer = GlStateManager.getFrameBuffer(GL45C.GL_DRAW_FRAMEBUFFER);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer viewport = stack.mallocInt(4);
            GL45C.glGetIntegerv(GL45C.GL_VIEWPORT, viewport);
            viewport.get(previousViewport);
        }
        for (int unit = 0; unit < UNIT_COUNT; unit++) {
            GlStateManager._activeTexture(GL45C.GL_TEXTURE0 + unit);
            previousSamplers[unit] = GL45C.glGetInteger(GL45C.GL_SAMPLER_BINDING);
        }

        try {
            GlStateManager._glBindFramebuffer(GL45C.GL_DRAW_FRAMEBUFFER, framebuffer);
            GlStateManager._disableDepthTest();
            GlStateManager._depthMask(false);
            GlStateManager._disableCull();
            GlStateManager._disableScissorTest();
            GlStateManager._colorMask(0xF);
            GlStateManager._disableBlend(0);
            GL45C.glBindVertexArray(vertexArray);
            bindTexture(UNIT_SCENE, sceneColorTexture, sceneSampler);

            if (flare > 0.0f) {
                GL45C.glNamedFramebufferTexture(framebuffer, GL45C.GL_COLOR_ATTACHMENT0, brightTexture, 0);
                GlStateManager._viewport(0, 0, brightWidth, brightHeight);
                brightProgram.use();
                brightProgram.set("ViewportSize", brightWidth, brightHeight);
                GL45C.glDrawArrays(GL45C.GL_TRIANGLES, 0, 3);
                GL45C.glGenerateTextureMipmap(brightTexture);
            }

            if (haze > 0.0f) {
                bindTexture(UNIT_DEPTH, depthTexture, depthSampler);
                GlStateManager._viewport(0, 0, HORIZON_BINS, 1);
                GL45C.glNamedFramebufferTexture(framebuffer, GL45C.GL_COLOR_ATTACHMENT0, horizonRaw, 0);
                horizonProgram.use();
                horizonProgram.set("ViewportSize", HORIZON_BINS, 1);
                horizonProgram.set("Near", NEAR_PLANE);
                horizonProgram.set("Far", farPlane);
                GL45C.glDrawArrays(GL45C.GL_TRIANGLES, 0, 3);

                int next = 1 - horizonCurrent;
                GL45C.glNamedFramebufferTexture(framebuffer, GL45C.GL_COLOR_ATTACHMENT0, horizonFilled[next], 0);
                bindTexture(UNIT_BRIGHT, horizonRaw, depthSampler);
                bindTexture(UNIT_HORIZON, horizonFilled[horizonCurrent], depthSampler);
                horizonFillProgram.use();
                GL45C.glDrawArrays(GL45C.GL_TRIANGLES, 0, 3);
                horizonCurrent = next;

                GL45C.glNamedFramebufferTexture(framebuffer, GL45C.GL_COLOR_ATTACHMENT0, horizonSmooth, 0);
                bindTexture(UNIT_BRIGHT, horizonFilled[horizonCurrent], depthSampler);
                horizonSmoothProgram.use();
                GL45C.glDrawArrays(GL45C.GL_TRIANGLES, 0, 3);
            }

            if (clouds) {
                GlStateManager._glBindFramebuffer(GL45C.GL_DRAW_FRAMEBUFFER, cloudFramebuffer);
                GlStateManager._viewport(0, 0, cloudWidth, cloudHeight);
                bindTexture(UNIT_DEPTH, depthTexture, depthSampler);
                bindTexture(UNIT_CIRRUS, CloudField.cirrusTexture(), sceneSampler);
                // The backend tracks no 3D bindings, so this one is taken back below.
                GlStateManager._activeTexture(GL45C.GL_TEXTURE0 + UNIT_NOISE);
                GL45C.glBindTexture(GL45C.GL_TEXTURE_3D, CloudField.noiseTexture());
                GL45C.glBindSampler(UNIT_NOISE, 0);
                cloudMarchProgram.use();
                cloudMarchProgram.set("ViewportSize", cloudWidth, cloudHeight);
                cloudMarchProgram.set("FrameIndex", cloudFrame++ % 4096);
                setView(cloudMarchProgram);
                setCloudField(cloudMarchProgram);
                GL45C.glDrawArrays(GL45C.GL_TRIANGLES, 0, 3);
                foldIntoHistory();
                GlStateManager._glBindFramebuffer(GL45C.GL_DRAW_FRAMEBUFFER, framebuffer);

                int next = 1 - calibrationCurrent;
                GlStateManager._viewport(0, 0, CALIBRATION_BINS, 1);
                GL45C.glNamedFramebufferTexture(framebuffer, GL45C.GL_COLOR_ATTACHMENT0, calibration[next], 0);
                bindTexture(UNIT_PANORAMA, CloudField.panoramaTexture(), panoramaSampler);
                bindTexture(UNIT_CALIBRATION, calibration[calibrationCurrent], depthSampler);
                cloudCalibrationProgram.use();
                setView(cloudCalibrationProgram);
                cloudCalibrationProgram.set("Follow", calibrationFollow);
                GL45C.glDrawArrays(GL45C.GL_TRIANGLES, 0, 3);
                calibrationCurrent = next;

                next = 1 - skyBehindCurrent;
                GlStateManager._viewport(0, 0, SKY_BANDS, 1);
                GL45C.glNamedFramebufferTexture(framebuffer, GL45C.GL_COLOR_ATTACHMENT0, skyBehind[next], 0);
                bindTexture(UNIT_CALIBRATION, calibration[calibrationCurrent], depthSampler);
                bindTexture(UNIT_SKY_BEHIND, skyBehind[skyBehindCurrent], depthSampler);
                skyBehindProgram.use();
                setView(skyBehindProgram);
                skyBehindProgram.set("Follow", calibrationFollow);
                GL45C.glDrawArrays(GL45C.GL_TRIANGLES, 0, 3);
                skyBehindCurrent = next;
            }

            // Attached every frame: a resize recreates the target, and GL may hand a deleted
            // texture's name to its replacement.
            GL45C.glNamedFramebufferTexture(framebuffer, GL45C.GL_COLOR_ATTACHMENT0, targetColor.glId(), 0);
            GlStateManager._viewport(0, 0, mainTarget.width, mainTarget.height);
            bindTexture(UNIT_DEPTH, depthTexture, depthSampler);
            bindTexture(UNIT_BRIGHT, brightTexture, brightSampler);
            bindTexture(UNIT_HORIZON, horizonSmooth, sceneSampler);
            bindTexture(UNIT_CLOUDS, cloudHistory[historyCurrent], depthSampler);
            bindTexture(UNIT_CLOUD_DISTANCE, cloudDistanceTexture, depthSampler);
            bindTexture(UNIT_CALIBRATION, calibration[calibrationCurrent], depthSampler);
            bindTexture(UNIT_PANORAMA, clouds ? CloudField.panoramaTexture() : 0, panoramaSampler);
            bindTexture(UNIT_SKY_BEHIND, skyBehind[skyBehindCurrent], depthSampler);
            compositeProgram.use();
            compositeProgram.set("ViewportSize", mainTarget.width, mainTarget.height);
            setView(compositeProgram);
            compositeProgram.set("Clouds", clouds ? 1.0f : 0.0f);
            if (clouds) {
                setCloudField(compositeProgram);
                compositeProgram.set("CloudShadow", CloudField.shadowStrength());
            }
            compositeProgram.set("CloudSize", cloudWidth, cloudHeight);
            compositeProgram.set("FocusDistance", 1.0f / focusDioptres);
            compositeProgram.set("DepthOfField", depthOfField);
            compositeProgram.set("Flare", flare);
            compositeProgram.set("Aspect", aspect);
            compositeProgram.set("Haze", haze);
            compositeProgram.set("Rain", rain);
            compositeProgram.set("CameraY", (float) cameraY);
            compositeProgram.setSources(sources, flare > 0.0f ? projectSources() : 0);
            GL45C.glDrawArrays(GL45C.GL_TRIANGLES, 0, 3);
            historyValid = clouds;
        } finally {
            GlStateManager._activeTexture(GL45C.GL_TEXTURE0 + UNIT_NOISE);
            GL45C.glBindTexture(GL45C.GL_TEXTURE_3D, 0);
            for (int unit = 0; unit < UNIT_COUNT; unit++) {
                GL45C.glBindSampler(unit, previousSamplers[unit]);
            }
            GL45C.glBindVertexArray(previousVertexArray);
            GL45C.glUseProgram(previousProgram);
            GlStateManager._glBindFramebuffer(GL45C.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            GlStateManager._viewport(
                    previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
        }
    }

    /**
     * Folds this frame's march into the clouds' history, in the march's targets' size; the
     * caller rebinds its framebuffer and viewport.
     */
    private void foldIntoHistory() {
        viewProjection.set(projection).mul(view);
        double motionX = cameraX - previousCameraX;
        double motionY = cameraY - previousCameraY;
        double motionZ = cameraZ - previousCameraZ;
        boolean valid = historyValid
                && motionX * motionX + motionY * motionY + motionZ * motionZ < HISTORY_RESET_DISTANCE * HISTORY_RESET_DISTANCE;
        int next = 1 - historyCurrent;
        GL45C.glNamedFramebufferTexture(temporalFramebuffer, GL45C.GL_COLOR_ATTACHMENT0, cloudHistory[next], 0);
        GL45C.glNamedFramebufferTexture(temporalFramebuffer, GL45C.GL_COLOR_ATTACHMENT1, historyDistance[next], 0);
        GlStateManager._glBindFramebuffer(GL45C.GL_DRAW_FRAMEBUFFER, temporalFramebuffer);
        bindTexture(UNIT_CLOUDS, cloudTexture, depthSampler);
        bindTexture(UNIT_CLOUD_DISTANCE, cloudDistanceTexture, depthSampler);
        bindTexture(UNIT_HISTORY, cloudHistory[historyCurrent], sceneSampler);
        bindTexture(UNIT_HISTORY_DISTANCE, historyDistance[historyCurrent], depthSampler);
        cloudTemporalProgram.use();
        cloudTemporalProgram.set("ViewportSize", cloudWidth, cloudHeight);
        setView(cloudTemporalProgram);
        cloudTemporalProgram.set("CameraMotion", (float) motionX, (float) motionY, (float) motionZ);
        cloudTemporalProgram.set("HistoryValid", valid ? 1.0f : 0.0f);
        cloudTemporalProgram.setMatrix("PreviousViewProjection", previousViewProjection);
        GL45C.glDrawArrays(GL45C.GL_TRIANGLES, 0, 3);
        historyCurrent = next;
        previousViewProjection.set(viewProjection);
        previousCameraX = cameraX;
        previousCameraY = cameraY;
        previousCameraZ = cameraZ;
    }

    /** The depth and view ray inputs every pass that reads depth shares. */
    private void setView(Program program) {
        program.set("Near", NEAR_PLANE);
        program.set("Far", farPlane);
        program.set("CameraForward", cameraBasis[0], cameraBasis[1], cameraBasis[2]);
        program.set("CameraRight", cameraBasis[3], cameraBasis[4], cameraBasis[5]);
        program.set("CameraUp", cameraBasis[6], cameraBasis[7], cameraBasis[8]);
        program.set("TanHalfFov", tanHalfFovX, tanHalfFovY);
        program.set("ScreenSize", screenWidth, screenHeight);
    }

    private static void setCloudField(Program program) {
        float[] camera = CloudField.camera();
        float[] layers = CloudField.layers();
        float[] light = CloudField.light();
        program.set("CameraXZ", camera[0], camera[2]);
        program.set("CameraHeight", camera[1]);
        program.set("CloudBase", layers[0]);
        program.set("CloudThickness", layers[1]);
        program.set("Coverage", layers[2]);
        program.set("Density", layers[3]);
        program.set("FeatureScale", layers[4]);
        program.set("Detail", layers[5]);
        program.set("LightDirection", light[0], light[1], light[2]);
        program.set("LightIrradiance", light[3], light[4], light[5]);
        program.set("AmbientTop", light[6], light[7], light[8]);
        program.set("AmbientBottom", light[9], light[10], light[11]);
        program.set("Brightness", CloudField.brightness());
        program.set("HazeDistance", CloudField.hazeDistance());
        program.set("CirrusAngle", CloudField.cirrusAngle());
        program.set("CirrusStrength", CloudField.cirrusStrength());
    }

    public void cleanup() {
        if (brightProgram != null) {
            brightProgram.delete();
            brightProgram = null;
        }
        if (horizonProgram != null) {
            horizonProgram.delete();
            horizonProgram = null;
        }
        if (horizonFillProgram != null) {
            horizonFillProgram.delete();
            horizonFillProgram = null;
            horizonSmoothProgram.delete();
            horizonSmoothProgram = null;
        }
        if (horizonRaw != 0) {
            GL45C.glDeleteTextures(horizonRaw);
            GL45C.glDeleteTextures(horizonFilled);
            GL45C.glDeleteTextures(horizonSmooth);
            horizonRaw = horizonFilled[0] = horizonFilled[1] = horizonSmooth = 0;
        }
        if (compositeProgram != null) {
            compositeProgram.delete();
            compositeProgram = null;
        }
        if (cloudMarchProgram != null) {
            cloudMarchProgram.delete();
            cloudMarchProgram = null;
            cloudCalibrationProgram.delete();
            cloudCalibrationProgram = null;
            skyBehindProgram.delete();
            skyBehindProgram = null;
            GL45C.glDeleteTextures(calibration);
            GL45C.glDeleteTextures(skyBehind);
            calibration[0] = calibration[1] = skyBehind[0] = skyBehind[1] = 0;
        }
        if (cloudTexture != 0) {
            GL45C.glDeleteTextures(cloudTexture);
            GL45C.glDeleteTextures(cloudDistanceTexture);
            GL45C.glDeleteTextures(cloudHistory);
            GL45C.glDeleteTextures(historyDistance);
            GL45C.glDeleteFramebuffers(cloudFramebuffer);
            GL45C.glDeleteFramebuffers(temporalFramebuffer);
            cloudTexture = cloudDistanceTexture = cloudFramebuffer = temporalFramebuffer = cloudWidth = cloudHeight = 0;
            cloudHistory[0] = cloudHistory[1] = historyDistance[0] = historyDistance[1] = 0;
            historyValid = false;
        }
        if (cloudTemporalProgram != null) {
            cloudTemporalProgram.delete();
            cloudTemporalProgram = null;
        }
        if (vertexArray != 0) GL45C.glDeleteVertexArrays(vertexArray);
        if (framebuffer != 0) GL45C.glDeleteFramebuffers(framebuffer);
        if (brightTexture != 0) GL45C.glDeleteTextures(brightTexture);
        if (sceneSampler != 0) GL45C.glDeleteSamplers(sceneSampler);
        if (depthSampler != 0) GL45C.glDeleteSamplers(depthSampler);
        if (brightSampler != 0) GL45C.glDeleteSamplers(brightSampler);
        if (panoramaSampler != 0) GL45C.glDeleteSamplers(panoramaSampler);
        vertexArray = framebuffer = brightTexture = brightWidth = brightHeight = 0;
        sceneSampler = depthSampler = brightSampler = panoramaSampler = 0;
    }

    private void ensureObjects() {
        if (brightProgram == null) {
            brightProgram = new Program(BRIGHT_FRAGMENT_SHADER);
        }
        if (horizonProgram == null) {
            horizonProgram = new Program(HORIZON_FRAGMENT_SHADER);
            horizonFillProgram = new Program(HORIZON_FILL_FRAGMENT_SHADER);
            horizonSmoothProgram = new Program(HORIZON_SMOOTH_FRAGMENT_SHADER);
        }
        if (horizonRaw == 0) {
            horizonRaw = horizonTexture();
            horizonFilled[0] = horizonTexture();
            horizonFilled[1] = horizonTexture();
            horizonSmooth = horizonTexture();
        }
        if (compositeProgram == null) {
            compositeProgram = new Program(COMPOSITE_FRAGMENT_SHADER);
        }
        if (cloudMarchProgram == null) {
            cloudMarchProgram = new Program(CLOUD_MARCH_FRAGMENT_SHADER);
            cloudCalibrationProgram = new Program(CLOUD_CALIBRATION_FRAGMENT_SHADER);
            skyBehindProgram = new Program(SKY_BEHIND_FRAGMENT_SHADER);
            cloudTemporalProgram = new Program(CLOUD_TEMPORAL_FRAGMENT_SHADER);
            calibration[0] = colourTexture(CALIBRATION_BINS);
            calibration[1] = colourTexture(CALIBRATION_BINS);
            skyBehind[0] = colourTexture(SKY_BANDS);
            skyBehind[1] = colourTexture(SKY_BANDS);
        }
        if (vertexArray == 0) {
            vertexArray = GL45C.glCreateVertexArrays();
        }
        if (framebuffer == 0) {
            framebuffer = GL45C.glCreateFramebuffers();
            GL45C.glNamedFramebufferDrawBuffer(framebuffer, GL45C.GL_COLOR_ATTACHMENT0);
        }
        if (sceneSampler == 0) {
            sceneSampler = sampler(GL45C.GL_LINEAR, GL45C.GL_LINEAR);
            depthSampler = sampler(GL45C.GL_NEAREST, GL45C.GL_NEAREST);
            brightSampler = sampler(GL45C.GL_LINEAR_MIPMAP_LINEAR, GL45C.GL_LINEAR);
            // The panorama wraps around in azimuth.
            panoramaSampler = sampler(GL45C.GL_LINEAR_MIPMAP_LINEAR, GL45C.GL_LINEAR);
            GL45C.glSamplerParameteri(panoramaSampler, GL45C.GL_TEXTURE_WRAP_S, GL45C.GL_REPEAT);
        }
    }

    private void ensureBrightTexture(int width, int height) {
        if (brightTexture != 0 && width == brightWidth && height == brightHeight) {
            return;
        }
        if (brightTexture != 0) {
            GL45C.glDeleteTextures(brightTexture);
        }
        int levels = 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
        brightTexture = GL45C.glCreateTextures(GL45C.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(brightTexture, levels, GL45C.GL_RGBA16F, width, height);
        brightWidth = width;
        brightHeight = height;
    }

    /** The march's targets, in a framebuffer of their own, since they are smaller than the screen. */
    private void ensureCloudTexture(int width, int height) {
        if (cloudTexture != 0 && width == cloudWidth && height == cloudHeight) {
            return;
        }
        if (cloudTexture != 0) {
            GL45C.glDeleteTextures(cloudTexture);
            GL45C.glDeleteTextures(cloudDistanceTexture);
            GL45C.glDeleteTextures(cloudHistory);
            GL45C.glDeleteTextures(historyDistance);
        }
        if (cloudFramebuffer == 0) {
            cloudFramebuffer = GL45C.glCreateFramebuffers();
            GL45C.glNamedFramebufferDrawBuffers(cloudFramebuffer,
                    new int[] {GL45C.GL_COLOR_ATTACHMENT0, GL45C.GL_COLOR_ATTACHMENT1});
            temporalFramebuffer = GL45C.glCreateFramebuffers();
            GL45C.glNamedFramebufferDrawBuffers(temporalFramebuffer,
                    new int[] {GL45C.GL_COLOR_ATTACHMENT0, GL45C.GL_COLOR_ATTACHMENT1});
        }
        cloudTexture = GL45C.glCreateTextures(GL45C.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(cloudTexture, 1, GL45C.GL_RGBA16F, width, height);
        cloudDistanceTexture = GL45C.glCreateTextures(GL45C.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(cloudDistanceTexture, 1, GL45C.GL_RG32F, width, height);
        for (int i = 0; i < 2; i++) {
            cloudHistory[i] = GL45C.glCreateTextures(GL45C.GL_TEXTURE_2D);
            GL45C.glTextureStorage2D(cloudHistory[i], 1, GL45C.GL_RGBA16F, width, height);
            historyDistance[i] = GL45C.glCreateTextures(GL45C.GL_TEXTURE_2D);
            GL45C.glTextureStorage2D(historyDistance[i], 1, GL45C.GL_RG32F, width, height);
        }
        historyValid = false;
        GL45C.glNamedFramebufferTexture(cloudFramebuffer, GL45C.GL_COLOR_ATTACHMENT0, cloudTexture, 0);
        GL45C.glNamedFramebufferTexture(cloudFramebuffer, GL45C.GL_COLOR_ATTACHMENT1, cloudDistanceTexture, 0);
        cloudWidth = width;
        cloudHeight = height;
    }

    /** One row of {@link #HORIZON_BINS} colours, cleared to no sky. */
    private static int horizonTexture() {
        return colourTexture(HORIZON_BINS);
    }

    /** One row of {@code width} colours, cleared to none. */
    private static int colourTexture(int width) {
        int texture = GL45C.glCreateTextures(GL45C.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(texture, 1, GL45C.GL_RGBA16F, width, 1);
        GL45C.glClearTexImage(texture, 0, GL45C.GL_RGBA, GL45C.GL_FLOAT, (java.nio.FloatBuffer) null);
        return texture;
    }

    private static void bindTexture(int unit, int texture, int sampler) {
        GlStateManager._activeTexture(GL45C.GL_TEXTURE0 + unit);
        GlStateManager._bindTexture(texture);
        GL45C.glBindSampler(unit, sampler);
    }

    private static int sampler(int minFilter, int magFilter) {
        int sampler = GL45C.glCreateSamplers();
        GL45C.glSamplerParameteri(sampler, GL45C.GL_TEXTURE_MIN_FILTER, minFilter);
        GL45C.glSamplerParameteri(sampler, GL45C.GL_TEXTURE_MAG_FILTER, magFilter);
        GL45C.glSamplerParameteri(sampler, GL45C.GL_TEXTURE_WRAP_S, GL45C.GL_CLAMP_TO_EDGE);
        GL45C.glSamplerParameteri(sampler, GL45C.GL_TEXTURE_WRAP_T, GL45C.GL_CLAMP_TO_EDGE);
        return sampler;
    }

    private static final class Program {
        private final int id;

        Program(String fragmentSource) {
            int vertexShader = compile(GL45C.GL_VERTEX_SHADER, VERTEX_SHADER);
            int fragmentShader = 0;
            int program = 0;
            try {
                fragmentShader = compile(GL45C.GL_FRAGMENT_SHADER, fragmentSource);
                program = GL45C.glCreateProgram();
                GL45C.glAttachShader(program, vertexShader);
                GL45C.glAttachShader(program, fragmentShader);
                GL45C.glLinkProgram(program);
                if (GL45C.glGetProgrami(program, GL45C.GL_LINK_STATUS) == GL45C.GL_FALSE) {
                    String log = GL45C.glGetProgramInfoLog(program);
                    GL45C.glDeleteProgram(program);
                    throw new IllegalStateException("Failed to link lens shader: " + log);
                }
            } finally {
                GL45C.glDeleteShader(vertexShader);
                if (fragmentShader != 0) {
                    GL45C.glDeleteShader(fragmentShader);
                }
            }
            id = program;
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "SceneColor"), UNIT_SCENE);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "SceneDepth"), UNIT_DEPTH);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "Bright"), UNIT_BRIGHT);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "HorizonSky"), UNIT_HORIZON);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "HorizonRaw"), UNIT_BRIGHT);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "HorizonPrevious"), UNIT_HORIZON);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "Noise"), UNIT_NOISE);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "Panorama"), UNIT_PANORAMA);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "Cirrus"), UNIT_CIRRUS);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "SkyBehind"), UNIT_SKY_BEHIND);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "SkyBehindPrevious"), UNIT_SKY_BEHIND);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "CloudHistory"), UNIT_HISTORY);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "HistoryDistance"), UNIT_HISTORY_DISTANCE);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "CloudLight"), UNIT_CLOUDS);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "CloudDistance"), UNIT_CLOUD_DISTANCE);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "CalibrationPrevious"), UNIT_CALIBRATION);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "CloudCalibration"), UNIT_CALIBRATION);
        }

        void use() {
            GL45C.glUseProgram(id);
        }

        void set(String name, float value) {
            GL45C.glUniform1f(GL45C.glGetUniformLocation(id, name), value);
        }

        void set(String name, float x, float y) {
            GL45C.glUniform2f(GL45C.glGetUniformLocation(id, name), x, y);
        }

        void set(String name, float x, float y, float z) {
            GL45C.glUniform3f(GL45C.glGetUniformLocation(id, name), x, y, z);
        }

        void setMatrix(String name, Matrix4f matrix) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                GL45C.glUniformMatrix4fv(GL45C.glGetUniformLocation(id, name), false, matrix.get(stack.mallocFloat(16)));
            }
        }

        void setSources(float[] sources, int count) {
            GL45C.glUniform1i(GL45C.glGetUniformLocation(id, "SourceCount"), count);
            if (count > 0) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    GL45C.glUniform4fv(GL45C.glGetUniformLocation(id, "Sources"),
                            stack.mallocFloat(count * 4).put(sources, 0, count * 4).flip());
                }
            }
        }

        void delete() {
            GL45C.glDeleteProgram(id);
        }

        private static int compile(int type, String source) {
            int shader = GL45C.glCreateShader(type);
            GL45C.glShaderSource(shader, source);
            GL45C.glCompileShader(shader);
            if (GL45C.glGetShaderi(shader, GL45C.GL_COMPILE_STATUS) == GL45C.GL_FALSE) {
                String log = GL45C.glGetShaderInfoLog(shader);
                GL45C.glDeleteShader(shader);
                throw new IllegalStateException("Failed to compile lens shader: " + log);
            }
            return shader;
        }
    }
}

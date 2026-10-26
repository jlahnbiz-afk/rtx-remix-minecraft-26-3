// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

// The Unbound clouds' density field, lighting, march and shadow, shared by the march that
// draws them into the sky (volumetric_clouds.comp) and the lens (LensEffects), which marches
// them in front of the land and shades the land beneath them, so both show the same clouds
// lit the same way. The includer defines PI and
// declares the 3D noise as Noise, the layer inputs CloudBase, CloudThickness, Coverage,
// Density, FeatureScale and Detail, the light LightDirection, LightIrradiance, AmbientTop and
// AmbientBottom, the camera in the clouds' frame as CameraXZ and CameraHeight, the look as
// Brightness and HazeDistance, and the cirrus layer as Cirrus, CirrusAngle, CirrusStrength and
// CIRRUS_MIN_ELEVATION_DEGREES. It may define CLOUD_MIN_STEPS, CLOUD_MAX_STEPS and
// CLOUD_ALTO_STEPS first.

#ifndef CLOUD_MIN_STEPS
#define CLOUD_MIN_STEPS 28
#endif
#ifndef CLOUD_MAX_STEPS
#define CLOUD_MAX_STEPS 56
#endif

// Extinction per block at full density.
const float EXTINCTION = 0.08;
// The stack, from CloudBase and CloudThickness: small cumulus half as tall as the large,
// the large a little above it, and the altostratus about 1.7 km higher.
const float SMALL_SHARE = 0.5;
const float LARGE_LIFT = 260.0;
const float ALTO_LIFT = 1700.0;
const float ALTO_THICKNESS = 40.0;
const float SMALL_DENSITY = 1.0;
const float LARGE_DENSITY = 1.0;
const float ALTO_DENSITY = 0.12;
const float LARGE_COVERAGE = 0.8;
const float ALTO_COVERAGE = 0.55;
// Blocks per noise tile at feature scale 1, for each layer's weather cells and clumps.
const float SMALL_BROAD_PERIOD = 5000.0;
const float SMALL_FINE_PERIOD = 900.0;
const float LARGE_BROAD_PERIOD = 10000.0;
const float LARGE_FINE_PERIOD = 2600.0;
const float ALTO_BROAD_PERIOD = 60000.0;
const float ALTO_FINE_PERIOD = 7500.0;
const float ALTO_WARP = 3000.0;
const float BROAD_SHARE = 0.45;
const float DETAIL_PERIOD = 200.0;
const float BASE_LOW = 0.75;
const float BASE_HIGH = 0.9;
// Blocks over which a cumulus base rounds off.
const float BASE_ROUNDING = 20.0;
// How far the detail leans downwind at the top, per block of layer thickness.
const vec2 SHEAR = vec2(0.96, 0.28) * 0.2;
const float DETAIL_EROSION = 0.9;
// Height fraction over which erosion grows from none at a cumulus base to full; below it the
// thin rim of a base would fray into holes that show as a speckled patch from below.
const float BASE_EROSION_HEIGHT = 0.3;
const float EDGE_GAIN = 3.0;

// --- Layered clouds ---------------------------------------------------------------------
// Three layers stacked as weather builds them: small cumulus just above the base, large
// cumulus higher up, and a thin altostratus sheet far above both. Each cumulus layer takes
// its cover from flat noise, grows taller where that cover is strongest, and has its edges
// eroded by 3D detail sheared downwind with height. A layer above shades the ones below it,
// both from the sun and from the sky.

const int LAYER_SMALL = 0;
const int LAYER_LARGE = 1;
const int LAYER_ALTO = 2;

float remap(float value, float low, float high, float newLow, float newHigh) {
    return newLow + (value - low) / (high - low) * (newHigh - newLow);
}

float layerBase(int layer) {
    return layer == LAYER_SMALL ? CloudBase : layer == LAYER_LARGE ? CloudBase + LARGE_LIFT : CloudBase + ALTO_LIFT;
}

float layerThickness(int layer) {
    return layer == LAYER_SMALL ? CloudThickness * SMALL_SHARE : layer == LAYER_LARGE ? CloudThickness : ALTO_THICKNESS;
}

float layerCoverage(int layer) {
    float share = layer == LAYER_SMALL ? 1.0 : layer == LAYER_LARGE ? LARGE_COVERAGE : ALTO_COVERAGE;
    return clamp(Coverage * share, 0.0, 1.0);
}

// The base shape noise, 0 to 1, read flat at one height of the noise tile.
float flatShape(vec2 xz, float period, float slice) {
    vec4 n = texture(Noise, vec3(xz.x / period, slice, xz.y / period));
    float lowFbm = n.g * 0.625 + n.b * 0.25 + n.a * 0.125;
    // The Perlin-Worley remap lands between about 0.7 and 0.95; stretching its middle over
    // 0 to 1 makes coverage roughly the share of sky the clouds fill.
    return clamp(remap(remap(n.r, lowFbm - 1.0, 1.0, 0.0, 1.0), BASE_LOW, BASE_HIGH, 0.0, 1.0), 0.0, 1.0);
}

// How strongly a layer is covered over a point, 0 to 1: broad weather cells and the clumps
// inside them, thresholded by the layer's coverage.
float layerCover(int layer, vec2 xz) {
    float scale = FeatureScale;
    float broad, fine;
    if (layer == LAYER_SMALL) {
        broad = flatShape(xz, SMALL_BROAD_PERIOD * scale, 0.19);
        fine = flatShape(xz, SMALL_FINE_PERIOD * scale, 0.53);
    } else if (layer == LAYER_LARGE) {
        broad = flatShape(xz.yx, LARGE_BROAD_PERIOD * scale, 0.71);
        fine = flatShape(xz.yx, LARGE_FINE_PERIOD * scale, 0.37);
    } else {
        broad = flatShape(xz, ALTO_BROAD_PERIOD * scale, 0.83);
        // Warped by the broad field, so the sheet tears into long streaks and rifts.
        fine = flatShape(xz + (broad - 0.5) * ALTO_WARP, ALTO_FINE_PERIOD * scale, 0.07);
    }
    float field = mix(fine, broad, BROAD_SHARE);
    float coverage = layerCoverage(layer);
    return clamp((field - (1.0 - coverage)) / max(coverage, 0.08), 0.0, 1.0);
}

// The cover a cumulus dome is built on, eased in from the edge: the dome rises as the square
// root of the cover, so cover climbing straight from zero stood the cloud's side up as a wall;
// eased, the side bulges out and rounds into the base.
float domeCover(float cover) {
    return cover * cover * (3.0 - 2.0 * cover);
}

// Density, 0 to 1 before the layer's density scale, at height fraction h through a cumulus layer.
float cumulusShape(int layer, vec3 world, float h, bool detailed) {
    if (h <= 0.0 || h >= 1.0) {
        return 0.0;
    }
    float cover = domeCover(layerCover(layer, world.xz));
    if (cover <= 0.0) {
        return 0.0;
    }
    float thickness = layerThickness(layer);
    // A rounded base a few blocks deep, and a dome above: only well-covered spots reach the
    // top, so strong cells tower while weak ones stay as low, flat puffs.
    float bottom = 1.0 - pow(1.0 - clamp(h * thickness / BASE_ROUNDING, 0.0, 1.0), 4.0);
    float top = 1.0 - h * h;
    float shape = clamp(cover + bottom * top - 1.0, 0.0, 1.0);
    if (detailed && shape > 0.0 && Detail > 0.0) {
        // Detail drifts downwind faster than the body, more so higher up, so tops shear over.
        vec3 sheared = world;
        sheared.xz += SHEAR * pow(h, 1.5) * thickness;
        vec4 n = texture(Noise, sheared / (DETAIL_PERIOD * FeatureScale));
        float detail = n.g * 0.625 + n.b * 0.25 + n.a * 0.125;
        // Wispy low on the sides, billowing higher up.
        float erosion = mix(1.0 - detail, detail, clamp(h * 4.0, 0.0, 1.0));
        // Only the thin edges erode; the dense heart of a cloud keeps its body, and the base,
        // where the air reaches condensation all at once, stays flat rather than perforated.
        erosion *= smoothstep(0.0, BASE_EROSION_HEIGHT, h);
        shape = clamp(shape - erosion * erosion * DETAIL_EROSION * Detail * (1.0 - shape), 0.0, 1.0);
    }
    return clamp(shape * EDGE_GAIN, 0.0, 1.0);
}

float layerDensity(int layer, vec3 world, bool detailed) {
    float h = (world.y - layerBase(layer)) / layerThickness(layer);
    if (layer == LAYER_ALTO) {
        if (h <= 0.0 || h >= 1.0) {
            return 0.0;
        }
        float cover = layerCover(LAYER_ALTO, world.xz);
        return cover * cover * ALTO_DENSITY * Density;
    }
    float density = layer == LAYER_SMALL ? SMALL_DENSITY : LARGE_DENSITY;
    return cumulusShape(layer, world, h, detailed) * density * Density;
}

const int LIGHT_STEPS = 3;
// Blocks between light samples in a 200-block layer, scaled with the layer's thickness.
const float LIGHT_STEP = 20.0;
const float ALTO_LIGHT_STEP = 200.0;
const float FORWARD_G = 0.8;
const float MIDDLE_G = 0.45;
const float WIDE_G = 0.15;
const float BACK_G = -0.3;
const float BACK_WEIGHT = 0.5;
const float POWDER_RATE = 4.0;
// Kept below the direct beam's share, so a cloud is bright on the sun's side and grey on the far one.
const float MULTIPLE_SCATTERING_GAIN = 2.0;
const float MULTIPLE_SCATTERING_REACH = 0.35;
const float AMBIENT_GAIN = 0.65;
const float BASE_SHADE = 5.0;
// Share of the sky a fully covered large cumulus layer hides from the small cumulus under it.
const float UPPER_SKY_OCCLUSION = 0.6;
// Blocks of cloud a sunbeam crosses in a fully covered layer above.
const float LARGE_SHADOW_DEPTH = 60.0;
const float ALTO_SHADOW_DEPTH = 30.0;

float henyeyGreenstein(float cosTheta, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (4.0 * PI * pow(1.0 + g2 - 2.0 * g * cosTheta, 1.5));
}

// The share of a higher layer's cover that lies between a point and the sun or the sky:
// the layer is read where the line from the point reaches its middle.
float coverAbove(int layer, vec3 world, vec3 toward) {
    if (toward.y <= 0.01) {
        return 0.0;
    }
    float middle = layerBase(layer) + layerThickness(layer) * 0.4;
    if (world.y >= middle) {
        return 0.0;
    }
    vec2 xz = world.xz + toward.xz * ((middle - world.y) / toward.y);
    float cover = layerCover(layer, xz);
    return layer == LAYER_ALTO ? cover * cover * ALTO_DENSITY / LARGE_DENSITY : cover;
}

// Optical depth toward the light: a few short steps through the layer itself, plus the
// layers above it read once each.
float lightOpticalDepth(int layer, vec3 world, float jitter) {
    float depth = 0.0;
    float stepLength = layer == LAYER_ALTO ? ALTO_LIGHT_STEP : LIGHT_STEP * layerThickness(layer) / 200.0;
    int steps = layer == LAYER_ALTO ? 2 : LIGHT_STEPS;
    for (int i = 0; i < steps; i++) {
        vec3 position = world + LightDirection * (stepLength * (float(i) + jitter));
        depth += layerDensity(layer, position, false) * stepLength;
    }
    float shade = 0.0;
    if (layer == LAYER_SMALL) {
        shade += coverAbove(LAYER_LARGE, world, LightDirection) * LARGE_DENSITY * LARGE_SHADOW_DEPTH;
    }
    if (layer != LAYER_ALTO) {
        // A sheet seen edge on toward a low sun shades far more than one overhead.
        shade += coverAbove(LAYER_ALTO, world, LightDirection) * LARGE_DENSITY * ALTO_SHADOW_DEPTH
            * (1.0 - abs(LightDirection.y));
    }
    return (depth + shade * Density) * EXTINCTION;
}

// Cloud straight up through a cumulus dome, per block of the layer's thickness: the density,
// EDGE_GAIN times the dome cover less the height fraction squared and full once that passes
// 1, summed over the height. Detail erosion is left out.
float domeColumn(float cover) {
    float full = sqrt(max(cover - 1.0 / EDGE_GAIN, 0.0));
    float edge = sqrt(cover);
    return full + EDGE_GAIN * (cover * (edge - full) - (edge * edge * edge - full * full * full) / 3.0);
}

// The share of the light the clouds above a point let through toward it, each layer read once
// where the line toward the light crosses its middle: a cumulus layer by the column of its
// dome there, so a shadow has its cloud's outline, and the altostratus by its sheet crossed
// on the slant.
float shadowTransmittance(vec3 world) {
    float depth = domeColumn(domeCover(coverAbove(LAYER_SMALL, world, LightDirection)))
            * layerThickness(LAYER_SMALL) * SMALL_DENSITY
        + domeColumn(domeCover(coverAbove(LAYER_LARGE, world, LightDirection)))
            * layerThickness(LAYER_LARGE) * LARGE_DENSITY
        + coverAbove(LAYER_ALTO, world, LightDirection) * LARGE_DENSITY * ALTO_THICKNESS
            / max(LightDirection.y, 0.1);
    return exp(-depth * Density * EXTINCTION);
}

// Thin edges scatter forward in a narrow lobe, the silver lining against the sun, while the
// dense interior, where light has scattered many times, spreads it widely; a little comes
// back toward a viewer with the sun behind. The lobes depend only on the ray, so a march
// evaluates them once.
struct CloudPhase {
    float narrow;
    float middle;
    float wide;
    float back;
};

CloudPhase cloudPhase(float cosTheta) {
    return CloudPhase(henyeyGreenstein(cosTheta, FORWARD_G), henyeyGreenstein(cosTheta, MIDDLE_G),
        henyeyGreenstein(cosTheta, WIDE_G), henyeyGreenstein(cosTheta, BACK_G));
}

// The light a unit of density scatters toward the viewer at a point h of the way up its layer.
vec3 cloudSource(int layer, vec3 world, float density, float h, CloudPhase phase, float jitter) {
    h = clamp(h, 0.0, 1.0);
    float depth = lightOpticalDepth(layer, world, jitter);
    float powder = 1.0 - exp(-POWDER_RATE * density);
    float lobes = mix(phase.narrow, mix(phase.middle, phase.wide, powder), powder) + phase.back * powder * BACK_WEIGHT;
    // Light that has scattered inside reaches deeper than the direct beam, and leaves nearly
    // evenly in every direction.
    vec3 sun = LightIrradiance * (exp(-depth) * lobes
        + exp(-MULTIPLE_SCATTERING_REACH * depth) * MULTIPLE_SCATTERING_GAIN / (4.0 * PI));
    // The sky lights the tops; bases, deep in their own cloud and under any layer above, see
    // much less of it.
    float skyShade = mix(1.0, exp2(-BASE_SHADE * density / max(Density, 0.05)), (1.0 - h) * (1.0 - h));
    float overhead = layer == LAYER_SMALL ? coverAbove(LAYER_LARGE, world, vec3(0.0, 1.0, 0.0)) : 0.0;
    vec3 sky = mix(AmbientBottom, AmbientTop, h) * skyShade * (1.0 - overhead * UPPER_SKY_OCCLUSION);
    return sun + sky * AMBIENT_GAIN;
}

// A small planet, so the layers curve down to the horizon within the march distance.
const float PLANET_RADIUS = 1.0e6;
const float MAX_DISTANCE = 30000.0;
const float MAX_SPAN = 9000.0;
#ifndef CLOUD_ALTO_STEPS
#define CLOUD_ALTO_STEPS 2
#endif

// Distances along a ray to where it reaches the given altitude. The ground curves away at
// t^2 (1 - dirY^2) / 2R, which is the planet to well within the march distance.
bool altitudeRoots(float altitude, float dirY, out float near, out float far) {
    float k = (1.0 - dirY * dirY) / (2.0 * PLANET_RADIUS);
    float c = CameraHeight - altitude;
    if (k < 1.0e-12) {
        if (abs(dirY) < 1.0e-6) {
            return false;
        }
        near = far = -c / dirY;
        return true;
    }
    float discriminant = dirY * dirY - 4.0 * k * c;
    if (discriminant < 0.0) {
        return false;
    }
    float root = sqrt(discriminant);
    float q = -0.5 * (dirY + (dirY >= 0.0 ? root : -root));
    float a = q / k;
    float b = q != 0.0 ? c / q : a;
    near = min(a, b);
    far = max(a, b);
    return true;
}

bool layerSpan(float base, float top, float dirY, out float start, out float end) {
    float baseNear, baseFar, topNear, topFar;
    bool hitBase = altitudeRoots(base, dirY, baseNear, baseFar);
    bool hitTop = altitudeRoots(top, dirY, topNear, topFar);
    if (CameraHeight < base) {
        if (!hitBase || !hitTop) {
            return false;
        }
        start = baseFar;
        end = topFar;
    } else if (CameraHeight < top) {
        start = 0.0;
        end = hitBase && baseNear > 0.0 ? baseNear : topFar;
    } else {
        if (!hitTop || topFar <= 0.0) {
            return false;
        }
        start = max(topNear, 0.0);
        end = hitBase && baseNear > 0.0 ? baseNear : topFar;
    }
    return end > start && start < MAX_DISTANCE;
}

// Premultiplied radiance and opacity of one layer along a direction out to limit blocks, and
// where it begins.
vec4 marchLayer(int layer, vec3 direction, float jitter, float limit, out float entry) {
    entry = MAX_DISTANCE;
    float base = layerBase(layer);
    float thickness = layerThickness(layer);
    float start, end;
    if (!layerSpan(base, base + thickness, direction.y, start, end)) {
        return vec4(0.0);
    }
    end = min(end, min(start + MAX_SPAN, min(limit, MAX_DISTANCE)));
    if (end <= start) {
        return vec4(0.0);
    }
    int steps = layer == LAYER_ALTO ? CLOUD_ALTO_STEPS : int(mix(float(CLOUD_MIN_STEPS), float(CLOUD_MAX_STEPS),
        clamp((end - start) / (thickness * 4.0), 0.0, 1.0)));
    float stepLength = (end - start) / float(steps);
    float t = start + stepLength * jitter;

    CloudPhase phase = cloudPhase(dot(direction, LightDirection));
    float horizontalLength = sqrt(max(0.0, 1.0 - direction.y * direction.y));
    float curvature = horizontalLength * horizontalLength / (2.0 * PLANET_RADIUS);

    vec3 scattered = vec3(0.0);
    float transmittance = 1.0;
    for (int i = 0; i < steps; i++) {
        float altitude = CameraHeight + direction.y * t + curvature * t * t;
        vec3 world = vec3(CameraXZ.x + direction.x * t, altitude, CameraXZ.y + direction.z * t);
        float density = layerDensity(layer, world, true);
        if (density > 0.0) {
            entry = min(entry, t);
            vec3 source = cloudSource(layer, world, density, (altitude - base) / thickness, phase, jitter);
            float stepTransmittance = exp(-density * EXTINCTION * stepLength);
            scattered += transmittance * source * (1.0 - stepTransmittance);
            transmittance *= stepTransmittance;
            if (transmittance < 0.01) {
                break;
            }
        }
        t += stepLength;
    }
    if (entry >= MAX_DISTANCE) {
        return vec4(0.0);
    }
    // Distant clouds sink into the haze, which the sky behind them already shows.
    float haze = exp(-entry / HazeDistance);
    return vec4(scattered * Brightness, 1.0 - transmittance) * haze;
}

// Premultiplied radiance and opacity of the clouds along a direction out to limit blocks,
// nearest layer first, and where the nearest begins, MAX_DISTANCE with none.
vec4 marchCloudsTo(vec3 direction, float jitter, float limit, out float entry) {
    vec4 layers[3];
    float entries[3];
    for (int layer = 0; layer < 3; layer++) {
        layers[layer] = marchLayer(layer, direction, jitter, limit, entries[layer]);
    }
    entry = min(entries[0], min(entries[1], entries[2]));
    vec4 result = vec4(0.0);
    for (int pass = 0; pass < 3; pass++) {
        int nearest = -1;
        for (int layer = 0; layer < 3; layer++) {
            if (entries[layer] < MAX_DISTANCE && (nearest < 0 || entries[layer] < entries[nearest])) {
                nearest = layer;
            }
        }
        if (nearest < 0) {
            break;
        }
        result += (1.0 - result.a) * layers[nearest];
        entries[nearest] = MAX_DISTANCE;
    }
    return result;
}

// Thin ice cloud: forward scattering, lit by the sun and the sky, and barely opaque.
const float CIRRUS_G = 0.6;
const float CIRRUS_SUN_GAIN = 1.6;
const float CIRRUS_SKY_GAIN = 0.7;
const float CIRRUS_OPACITY = 0.3;

// The cirrus layer seen along a direction, premultiplied. Its texture is laid out as the
// sky layers' cirrus dome maps it, turned slowly about the vertical by CirrusAngle.
vec4 cirrusLayer(vec3 direction) {
    if (CirrusStrength <= 0.0 || direction.y <= sin(radians(CIRRUS_MIN_ELEVATION_DEGREES))) {
        return vec4(0.0);
    }
    float c = cos(CirrusAngle);
    float s = sin(CirrusAngle);
    vec2 local = vec2(c * direction.x - s * direction.z, s * direction.x + c * direction.z);
    float horizontal = length(local);
    float elevation = degrees(asin(clamp(direction.y, -1.0, 1.0)));
    float r = (90.0 - elevation) / (90.0 - CIRRUS_MIN_ELEVATION_DEGREES);
    vec2 uv = 0.5 + 0.5 * r * (horizontal > 1.0e-5 ? local / horizontal : vec2(0.0));
    float density = texture(Cirrus, uv).r * CirrusStrength;
    if (density <= 0.0) {
        return vec4(0.0);
    }
    float cosTheta = dot(direction, LightDirection);
    float phase = mix(henyeyGreenstein(cosTheta, CIRRUS_G), 1.0 / (4.0 * PI), 0.5);
    vec3 radiance = LightIrradiance * (phase * CIRRUS_SUN_GAIN) + AmbientTop * CIRRUS_SKY_GAIN;
    return vec4(radiance * density, density * CIRRUS_OPACITY);
}

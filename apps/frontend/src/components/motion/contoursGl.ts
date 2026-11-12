/**
 * The contour map drawn by the GPU (WebGL2). Contours.tsx still runs the terrain, the
 * membrane, the grains and the exposure on a coarse grid; this module only turns that grid
 * into pixels, which is where nearly all the time went with the 2D canvas.
 *
 * Three passes per frame, all into the canvas's own buffer:
 * 1. Map: every pixel reads the grid with a smooth (cubic B-spline) filter, so the lines
 *    curve as smoothly as the 2D version's midpoint curves, and draws a contour wherever
 *    the height crosses one of the exposed levels. Line width comes from the screen-space
 *    rate of change, so lines stay as thin as the 2D strokes at any slope and resolution.
 *    The relief shading underneath is the same per-node lighting, filtered bilinearly.
 * 2. Grains: one short capsule per trail and per head, drawn as instanced quads.
 * 3. Centre fade: the whole picture is scaled by the same elliptical fade the 2D canvas
 *    applies with destination-in.
 */

export interface GlFrame {
  /** CSS size of the canvas and device pixels per CSS pixel. */
  width: number;
  height: number;
  ratio: number;
  /** Grid nodes across and down, and the CSS distance between nodes. */
  nodesX: number;
  nodesY: number;
  cell: number;
  /** Field height at every node, row by row. */
  values: Float32Array;
  /** Relief shading per node, straight (not premultiplied) RGBA. */
  shade: Uint8ClampedArray;
  /** Exposed height range and the number of contour levels across it. */
  lo: number;
  span: number;
  levels: number;
  /** Line colour, 0–1 per channel. */
  ink: readonly [number, number, number];
  /** Grain capsules: x0, y0, x1, y1, half width (CSS px), alpha — six floats each. */
  segments: Float32Array;
  segmentCount: number;
}

export interface GlRenderer {
  draw(frame: GlFrame): void;
  dispose(): void;
}

const FULL_VERTEX = `#version 300 es
const vec2 CORNERS[3] = vec2[3](vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
void main() {
  gl_Position = vec4(CORNERS[gl_VertexID], 0.0, 1.0);
}`;

const MAP_FRAGMENT = `#version 300 es
precision highp float;
uniform sampler2D uField;
uniform sampler2D uShade;
uniform vec2 uGrid;
uniform float uCell;
uniform float uRatio;
uniform float uBufferHeight;
uniform float uLo;
uniform float uSpan;
uniform float uLevels;
uniform vec3 uInk;
out vec4 color;

// Cubic B-spline filter from four bilinear taps; g is in grid units, nodes at integers.
float field(vec2 g) {
  vec2 i = floor(g);
  vec2 f = g - i;
  vec2 f2 = f * f;
  vec2 f3 = f2 * f;
  vec2 w0 = (1.0 - 3.0 * f + 3.0 * f2 - f3) / 6.0;
  vec2 w1 = (4.0 - 6.0 * f2 + 3.0 * f3) / 6.0;
  vec2 w2 = (1.0 + 3.0 * f + 3.0 * f2 - 3.0 * f3) / 6.0;
  vec2 w3 = f3 / 6.0;
  vec2 g0 = w0 + w1;
  vec2 g1 = w2 + w3;
  vec2 t0 = (i - 0.5 + w1 / g0) / uGrid;
  vec2 t1 = (i + 1.5 + w3 / g1) / uGrid;
  return g0.y * (g0.x * texture(uField, vec2(t0.x, t0.y)).r +
                 g1.x * texture(uField, vec2(t1.x, t0.y)).r) +
         g1.y * (g0.x * texture(uField, vec2(t0.x, t1.y)).r +
                 g1.x * texture(uField, vec2(t1.x, t1.y)).r);
}

void main() {
  vec2 p = vec2(gl_FragCoord.x, uBufferHeight - gl_FragCoord.y) / uRatio;
  vec2 g = p / uCell;
  float t = (field(g) - uLo) / uSpan * uLevels - 0.5;
  float n = floor(t + 0.5);
  // Distance to the nearest level, turned into device pixels by the local slope.
  float slope = max(length(vec2(dFdx(t), dFdy(t))), 1e-5);
  float away = abs(t - n) / slope;
  bool major = mod(n, 5.0) == 2.0;
  float halfWidth = (major ? 0.75 : 0.45) * uRatio;
  float cover = clamp(halfWidth + 0.5 - away, 0.0, 1.0) * min(1.0, 2.0 * halfWidth);
  if (n < 0.0 || n > uLevels - 1.0) cover = 0.0;
  float a = cover * (major ? 0.24 : 0.11);
  vec4 relief = texture(uShade, (g + 0.5) / uGrid);
  color = vec4(uInk * a, a) + relief * (1.0 - a);
}`;

const GRAIN_VERTEX = `#version 300 es
layout(location = 0) in vec2 corner;
layout(location = 1) in vec4 ends;
layout(location = 2) in vec2 style;
uniform vec2 uSize;
uniform float uRatio;
out vec2 vPos;
flat out vec4 vEnds;
flat out vec2 vStyle;
void main() {
  vec2 a = ends.xy;
  vec2 b = ends.zw;
  vec2 dir = b - a;
  float len = length(dir);
  vec2 along = len > 1e-4 ? dir / len : vec2(1.0, 0.0);
  vec2 across = vec2(-along.y, along.x);
  float pad = style.x + 1.0 / uRatio;
  vec2 p = mix(a, b, corner.x * 0.5 + 0.5) + along * corner.x * pad + across * corner.y * pad;
  vPos = p;
  vEnds = ends;
  vStyle = style;
  vec2 clip = p / uSize * 2.0 - 1.0;
  gl_Position = vec4(clip.x, -clip.y, 0.0, 1.0);
}`;

const GRAIN_FRAGMENT = `#version 300 es
precision highp float;
in vec2 vPos;
flat in vec4 vEnds;
flat in vec2 vStyle;
uniform vec3 uInk;
uniform float uRatio;
out vec4 color;
void main() {
  vec2 a = vEnds.xy;
  vec2 ab = vEnds.zw - a;
  float h = clamp(dot(vPos - a, ab) / max(dot(ab, ab), 1e-6), 0.0, 1.0);
  float away = length(vPos - a - ab * h) * uRatio;
  float halfWidth = vStyle.x * uRatio;
  float cover = clamp(halfWidth + 0.5 - away, 0.0, 1.0) * min(1.0, 2.0 * halfWidth);
  float alpha = cover * vStyle.y;
  color = vec4(uInk * alpha, alpha);
}`;

const FADE_FRAGMENT = `#version 300 es
precision highp float;
uniform vec2 uBuffer;
out vec4 color;
void main() {
  vec2 q = gl_FragCoord.xy / uBuffer - 0.5;
  float r = length(q / vec2(1.2, 0.9));
  color = vec4(mix(0.45, 1.0, clamp(r / 0.75, 0.0, 1.0)));
}`;

interface Program {
  program: WebGLProgram;
  uniform: (name: string) => WebGLUniformLocation | null;
}

function compile(gl: WebGL2RenderingContext, vertex: string, fragment: string) {
  const program = gl.createProgram();
  for (const [type, source] of [
    [gl.VERTEX_SHADER, vertex],
    [gl.FRAGMENT_SHADER, fragment],
  ] as const) {
    const shader = gl.createShader(type)!;
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    if (
      !gl.getShaderParameter(shader, gl.COMPILE_STATUS) &&
      !gl.isContextLost()
    )
      throw new Error(gl.getShaderInfoLog(shader) ?? "shader");
    gl.attachShader(program, shader);
    gl.deleteShader(shader);
  }
  gl.linkProgram(program);
  if (!gl.getProgramParameter(program, gl.LINK_STATUS) && !gl.isContextLost())
    throw new Error(gl.getProgramInfoLog(program) ?? "program");
  const cache = new Map<string, WebGLUniformLocation | null>();
  return {
    program,
    uniform: (name: string) => {
      if (!cache.has(name))
        cache.set(name, gl.getUniformLocation(program, name));
      return cache.get(name)!;
    },
  } satisfies Program;
}

// A GPU only: where Chrome would emulate WebGL on the CPU (no usable graphics driver),
// context creation fails and the cheaper 2D canvas takes over.
export const GL_OPTIONS: WebGLContextAttributes = {
  failIfMajorPerformanceCaveat: true,
  alpha: true,
  premultipliedAlpha: true,
  antialias: false,
  depth: false,
  stencil: false,
  preserveDrawingBuffer: false,
};

/** Builds everything the renderer needs on one context; throws if a shader fails. */
function build(gl: WebGL2RenderingContext) {
  const map = compile(gl, FULL_VERTEX, MAP_FRAGMENT);
  const grain = compile(gl, GRAIN_VERTEX, GRAIN_FRAGMENT);
  const fade = compile(gl, FULL_VERTEX, FADE_FRAGMENT);
  // Full-precision filtering where the GPU offers it; half floats filter everywhere.
  const precise = !!gl.getExtension("OES_texture_float_linear");
  const texture = () => {
    const t = gl.createTexture()!;
    gl.bindTexture(gl.TEXTURE_2D, t);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    return t;
  };
  const fieldTexture = texture();
  const shadeTexture = texture();
  const empty = gl.createVertexArray()!;
  const grains = gl.createVertexArray()!;
  gl.bindVertexArray(grains);
  const corners = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, corners);
  gl.bufferData(
    gl.ARRAY_BUFFER,
    new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]),
    gl.STATIC_DRAW,
  );
  gl.enableVertexAttribArray(0);
  gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
  const instances = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, instances);
  gl.enableVertexAttribArray(1);
  gl.vertexAttribPointer(1, 4, gl.FLOAT, false, 24, 0);
  gl.vertexAttribDivisor(1, 1);
  gl.enableVertexAttribArray(2);
  gl.vertexAttribPointer(2, 2, gl.FLOAT, false, 24, 16);
  gl.vertexAttribDivisor(2, 1);
  gl.bindVertexArray(null);
  return {
    map,
    grain,
    fade,
    precise,
    fieldTexture,
    shadeTexture,
    empty,
    grains,
    instances,
  };
}

const options = (allowSoftware: boolean): WebGLContextAttributes => ({
  ...GL_OPTIONS,
  failIfMajorPerformanceCaveat: !allowSoftware,
});

const SOFTWARE = /swiftshader|llvmpipe|softpipe|software|basic render driver/i;

/** Whether this browser can run the renderer at all, tried on a throwaway canvas. */
function supported(allowSoftware: boolean) {
  try {
    const gl = document
      .createElement("canvas")
      .getContext("webgl2", options(allowSoftware));
    if (!gl) return false;
    // Some browsers still hand out a CPU emulation (e.g. with the GPU blocklisted, or
    // headless); it would be far slower than the 2D canvas.
    const info = gl.getExtension("WEBGL_debug_renderer_info");
    const renderer = info
      ? String(gl.getParameter(info.UNMASKED_RENDERER_WEBGL))
      : "";
    if (!allowSoftware && SOFTWARE.test(renderer)) return false;
    build(gl);
    gl.getExtension("WEBGL_lose_context")?.loseContext();
    return true;
  } catch {
    return false;
  }
}

/**
 * A renderer bound to `canvas`, or null when WebGL2 is unavailable or only emulated on
 * the CPU (the canvas is then left untouched for the 2D fallback). `allowSoftware`
 * accepts an emulation anyway, for side-by-side checks only.
 */
export function createGlRenderer(
  canvas: HTMLCanvasElement,
  allowSoftware = false,
): GlRenderer | null {
  if (
    typeof WebGL2RenderingContext === "undefined" ||
    !supported(allowSoftware)
  )
    return null;
  const gl = canvas.getContext("webgl2", options(allowSoftware));
  if (!gl) return null;
  let parts: ReturnType<typeof build> | null = build(gl);
  let premultiplied = new Uint8Array(0);

  // A lost context (driver reset, GPU switch) draws nothing until it is restored.
  const lost = (event: Event) => {
    event.preventDefault();
    parts = null;
  };
  const restored = () => {
    try {
      parts = build(gl);
    } catch {
      parts = null;
    }
  };
  canvas.addEventListener("webglcontextlost", lost);
  canvas.addEventListener("webglcontextrestored", restored);

  const draw = (frame: GlFrame) => {
    if (!parts || gl.isContextLost()) return;
    const { map, grain, fade } = parts;
    const bufferWidth = canvas.width;
    const bufferHeight = canvas.height;
    gl.viewport(0, 0, bufferWidth, bufferHeight);

    // Field and relief, one texel per grid node.
    gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1);
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, parts.fieldTexture);
    gl.texImage2D(
      gl.TEXTURE_2D,
      0,
      parts.precise ? gl.R32F : gl.R16F,
      frame.nodesX,
      frame.nodesY,
      0,
      gl.RED,
      gl.FLOAT,
      frame.values,
    );
    if (premultiplied.length !== frame.shade.length)
      premultiplied = new Uint8Array(frame.shade.length);
    for (let p = 0; p < frame.shade.length; p += 4) {
      const a = frame.shade[p + 3]!;
      const c = (frame.shade[p]! * a) / 255;
      premultiplied[p] = premultiplied[p + 1] = premultiplied[p + 2] = c;
      premultiplied[p + 3] = a;
    }
    gl.activeTexture(gl.TEXTURE1);
    gl.bindTexture(gl.TEXTURE_2D, parts.shadeTexture);
    gl.texImage2D(
      gl.TEXTURE_2D,
      0,
      gl.RGBA8,
      frame.nodesX,
      frame.nodesY,
      0,
      gl.RGBA,
      gl.UNSIGNED_BYTE,
      premultiplied,
    );

    // 1. Map (overwrites every pixel, so no clear is needed).
    gl.disable(gl.BLEND);
    gl.useProgram(map.program);
    gl.uniform1i(map.uniform("uField"), 0);
    gl.uniform1i(map.uniform("uShade"), 1);
    gl.uniform2f(map.uniform("uGrid"), frame.nodesX, frame.nodesY);
    gl.uniform1f(map.uniform("uCell"), frame.cell);
    gl.uniform1f(map.uniform("uRatio"), frame.ratio);
    gl.uniform1f(map.uniform("uBufferHeight"), bufferHeight);
    gl.uniform1f(map.uniform("uLo"), frame.lo);
    gl.uniform1f(map.uniform("uSpan"), frame.span);
    gl.uniform1f(map.uniform("uLevels"), frame.levels);
    gl.uniform3f(map.uniform("uInk"), ...frame.ink);
    gl.bindVertexArray(parts.empty);
    gl.drawArrays(gl.TRIANGLES, 0, 3);

    // 2. Grains, blended over the map.
    gl.enable(gl.BLEND);
    if (frame.segmentCount > 0) {
      gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
      gl.useProgram(grain.program);
      gl.uniform2f(grain.uniform("uSize"), frame.width, frame.height);
      gl.uniform1f(grain.uniform("uRatio"), frame.ratio);
      gl.uniform3f(grain.uniform("uInk"), ...frame.ink);
      gl.bindVertexArray(parts.grains);
      gl.bindBuffer(gl.ARRAY_BUFFER, parts.instances);
      gl.bufferData(
        gl.ARRAY_BUFFER,
        frame.segments.subarray(0, frame.segmentCount * 6),
        gl.STREAM_DRAW,
      );
      gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, frame.segmentCount);
    }

    // 3. Centre fade: destination × fade.
    gl.blendFunc(gl.ZERO, gl.SRC_ALPHA);
    gl.useProgram(fade.program);
    gl.uniform2f(fade.uniform("uBuffer"), bufferWidth, bufferHeight);
    gl.bindVertexArray(parts.empty);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
    gl.bindVertexArray(null);
  };

  return {
    draw,
    dispose: () => {
      canvas.removeEventListener("webglcontextlost", lost);
      canvas.removeEventListener("webglcontextrestored", restored);
    },
  };
}

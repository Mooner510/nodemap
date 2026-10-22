import maplibregl, { type CustomLayerInterface, type CustomRenderMethodInput } from 'maplibre-gl'
import { GAP_MS, isMeaningfulGap } from './geo'
import type { TrackPoint } from './types'

/**
 * The route drawn straight on the GPU. Every segment is uploaded once per data load and the shader decides from the
 * displayed time what is past, recent or still ahead, so scrubbing only changes a uniform. (Layer filters or
 * data-driven paint would make MapLibre re-tile the whole GeoJSON source in its worker on every frame.)
 */

export interface RoutePalette {
  recent: string
  mid: string
  old: string
  casing: string
  ghost: string
  marker: string
}

export const LIGHT_ROUTE: RoutePalette = { recent: '#1b64f2', mid: '#5f9ef8', old: '#b3cdf5', casing: '#ffffff', ghost: 'rgba(84,96,116,0.5)', marker: '#3182f6' }
export const DARK_ROUTE: RoutePalette = { recent: '#5aa5ff', mid: '#3a74c4', old: '#2c4466', casing: '#12151b', ghost: 'rgba(150,160,178,0.42)', marker: '#4d9bff' }

export interface RouteFrame {
  time: number
  detailMs: number
  totalMs: number
  /** Context drawn faintly ahead of the displayed time. */
  aheadMs: number
  marker: { lng: number; lat: number } | null
}

// Half widths in CSS px.
const RECENT_HALF = 3.4
const OLD_HALF = 2.1
const CASING = 1.7
const GHOST_HALF = 1.3

const FLOATS_PER_VERTEX = 10
const CORNERS: [number, number][] = [[0, -1], [1, -1], [0, 1], [0, 1], [1, -1], [1, 1]]

const ROUTE_VS = `
precision highp float;
uniform mat4 u_matrix;
uniform vec2 u_viewport;
uniform float u_ratio;
uniform float u_lo;
uniform float u_hi;
uniform float u_half;
uniform float u_jumpWhole;
attribute vec2 a_a;
attribute vec2 a_b;
attribute vec2 a_t;
attribute vec2 a_corner;
attribute vec2 a_flags;
varying vec2 v_px;
varying vec2 v_pa;
varying vec2 v_pb;
varying vec2 v_t;
varying float v_gap;

vec2 toPx(vec2 m) {
  vec4 c = u_matrix * vec4(m, 0.0, 1.0);
  return (c.xy / c.w * 0.5 + 0.5) * u_viewport;
}

void main() {
  float ta = a_t.x;
  float tb = a_t.y;
  float s0;
  float s1;
  if (a_flags.y > 0.5) {
    // Nothing was recorded in between, so the marker jumps: show the whole leg once its end is reached
    // (or, for the context ahead, as soon as any of it lies ahead).
    bool on = u_jumpWhole > 0.5 ? (tb > u_lo && ta < u_hi) : (tb <= u_hi && tb > u_lo);
    s0 = 0.0;
    s1 = on ? 1.0 : 0.0;
  } else {
    float span = max(tb - ta, 1e-3);
    s0 = clamp((u_lo - ta) / span, 0.0, 1.0);
    s1 = clamp((u_hi - ta) / span, 0.0, 1.0);
  }
  if (s1 <= s0) {
    gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
    return;
  }
  vec2 pa = toPx(mix(a_a, a_b, s0));
  vec2 pb = toPx(mix(a_a, a_b, s1));
  vec2 d = pb - pa;
  float len = length(d);
  vec2 dir = len > 1e-4 ? d / len : vec2(1.0, 0.0);
  vec2 nrm = vec2(-dir.y, dir.x);
  float h = u_half * u_ratio + 1.5;
  vec2 p = mix(pa, pb, a_corner.x) + dir * (a_corner.x * 2.0 - 1.0) * h + nrm * a_corner.y * h;
  gl_Position = vec4(p / u_viewport * 2.0 - 1.0, 0.0, 1.0);
  v_px = p;
  v_pa = pa;
  v_pb = pb;
  v_t = vec2(mix(ta, tb, s0), mix(ta, tb, s1));
  v_gap = a_flags.x;
}
`

const ROUTE_FS = `
precision highp float;
uniform float u_ratio;
uniform float u_now;
uniform float u_detail;
uniform float u_total;
uniform float u_mode;
uniform float u_extra;
uniform vec4 u_recent;
uniform vec4 u_mid;
uniform vec4 u_old;
uniform vec4 u_flat;
varying vec2 v_px;
varying vec2 v_pa;
varying vec2 v_pb;
varying vec2 v_t;
varying float v_gap;

void main() {
  vec2 ab = v_pb - v_pa;
  float l2 = dot(ab, ab);
  float u = l2 > 0.0 ? clamp(dot(v_px - v_pa, ab) / l2, 0.0, 1.0) : 0.0;
  float dist = length(v_px - (v_pa + ab * u));
  float age = u_now - mix(v_t.x, v_t.y, u);
  // 0 within the detail window, rising to 1 at the end of the total window.
  float fade = u_mode > 0.5 ? clamp((age - u_detail) / max(u_total - u_detail, 1.0), 0.0, 1.0) : 0.0;
  float hw = (u_mode > 0.5 ? mix(${RECENT_HALF.toFixed(2)}, ${OLD_HALF.toFixed(2)}, fade) : ${GHOST_HALF.toFixed(2)}) + u_extra;
  float w = hw * u_ratio;
  float alpha = clamp(w - dist + 0.5, 0.0, 1.0);
  if (v_gap > 0.5) {
    // Dashes, measured from the segment start so they stay put while it grows.
    float dash = 7.0 * u_ratio;
    float along = sqrt(l2) * u + u_extra * u_ratio;
    if (mod(along, 13.0 * u_ratio) > dash + 2.0 * u_extra * u_ratio) discard;
  }
  vec4 c = u_flat;
  if (u_mode > 1.5) {
    float recent = clamp(age / max(u_detail, 1.0), 0.0, 1.0);
    c = fade > 0.0 ? mix(u_mid, u_old, fade) : mix(u_recent, u_mid, recent * recent);
  }
  if (alpha <= 0.0) discard;
  gl_FragColor = vec4(c.rgb * c.a * alpha, c.a * alpha);
}
`

const MARKER_VS = `
precision highp float;
uniform mat4 u_matrix;
uniform vec2 u_viewport;
uniform vec2 u_pos;
uniform float u_size;
attribute vec2 a_corner;
varying vec2 v_off;
void main() {
  vec4 c = u_matrix * vec4(u_pos, 0.0, 1.0);
  vec2 center = (c.xy / c.w * 0.5 + 0.5) * u_viewport;
  vec2 off = a_corner * u_size;
  v_off = off;
  gl_Position = vec4((center + off) / u_viewport * 2.0 - 1.0, 0.0, 1.0);
}
`

const MARKER_FS = `
precision highp float;
uniform float u_ratio;
uniform vec4 u_color;
varying vec2 v_off;
vec4 over(vec4 top, vec4 under) { return top + under * (1.0 - top.a); }
float disc(float d, float r) { return clamp(r * u_ratio - d + 0.5, 0.0, 1.0); }
void main() {
  float d = length(v_off);
  // Soft halo, white ring, accent dot.
  vec4 col = vec4(u_color.rgb, 1.0) * 0.2 * disc(d, 20.0);
  col = over(vec4(1.0) * disc(d, 12.5), col);
  col = over(vec4(u_color.rgb, 1.0) * disc(d, 9.0), col);
  if (col.a <= 0.0) discard;
  gl_FragColor = col;
}
`

function rgba(css: string): [number, number, number, number] {
  const m = css.match(/rgba?\(([^)]+)\)/)
  if (m) {
    const [r, g, b, a = '1'] = m[1].split(',').map((s) => s.trim())
    return [+r / 255, +g / 255, +b / 255, +a]
  }
  const h = css.replace('#', '')
  return [parseInt(h.slice(0, 2), 16) / 255, parseInt(h.slice(2, 4), 16) / 255, parseInt(h.slice(4, 6), 16) / 255, 1]
}

function compile(gl: WebGLRenderingContext, vs: string, fs: string): WebGLProgram {
  const program = gl.createProgram()!
  for (const [type, src] of [[gl.VERTEX_SHADER, vs], [gl.FRAGMENT_SHADER, fs]] as const) {
    const s = gl.createShader(type)!
    gl.shaderSource(s, src)
    gl.compileShader(s)
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s) ?? 'shader')
    gl.attachShader(program, s)
  }
  gl.linkProgram(program)
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(program) ?? 'link')
  return program
}

function uniforms(gl: WebGLRenderingContext, p: WebGLProgram) {
  const cache = new Map<string, WebGLUniformLocation | null>()
  return (name: string) => {
    if (!cache.has(name)) cache.set(name, gl.getUniformLocation(p, name))
    return cache.get(name)!
  }
}

/** Shared state between the route layer and the marker layer (they sit at different heights in the style). */
export class RouteRenderer {
  private points: readonly TrackPoint[] = []
  private dirty = true
  /** Mercator origin the vertices are stored relative to: float32 alone wobbles by metres at street zoom. */
  private origin = { x: 0, y: 0 }
  /** Times are stored as seconds from here, also for float32's sake. */
  private t0 = 0
  private vertexCount = 0
  frame: RouteFrame = { time: 0, detailMs: 0, totalMs: 0, aheadMs: 0, marker: null }
  palette: RoutePalette = LIGHT_ROUTE
  map: maplibregl.Map | null = null

  setPoints(points: readonly TrackPoint[]) {
    if (points === this.points) return
    this.points = points
    this.dirty = true
    this.map?.triggerRepaint()
  }

  invalidate() {
    this.dirty = true
    this.map?.triggerRepaint()
  }

  setFrame(frame: RouteFrame) {
    this.frame = frame
    this.map?.triggerRepaint()
  }

  /** u_matrix for vertices relative to the origin: viewProj × scale(worldSize) × translate(origin), in float64. */
  matrix(options: CustomRenderMethodInput): Float32Array {
    const m = options.modelViewProjectionMatrix as unknown as ArrayLike<number>
    const ws = 512 * Math.pow(2, this.map!.getZoom())
    const { x, y } = this.origin
    const out = new Float32Array(16)
    for (let r = 0; r < 4; r++) {
      out[r] = m[r] * ws
      out[4 + r] = m[4 + r] * ws
      out[8 + r] = m[8 + r]
      out[12 + r] = m[r] * ws * x + m[4 + r] * ws * y + m[12 + r]
    }
    return out
  }

  relative(lng: number, lat: number): [number, number] {
    const c = maplibregl.MercatorCoordinate.fromLngLat([lng, lat])
    return [c.x - this.origin.x, c.y - this.origin.y]
  }

  seconds(t: number) {
    return (t - this.t0) / 1000
  }

  /** Rebuilds the vertex buffer when the points changed; returns the vertex count. */
  upload(gl: WebGLRenderingContext, buffer: WebGLBuffer): number {
    if (!this.dirty) return this.vertexCount
    this.dirty = false
    const pts = this.points
    if (pts.length) {
      const o = maplibregl.MercatorCoordinate.fromLngLat([pts[0][2], pts[0][1]])
      this.origin = { x: o.x, y: o.y }
      this.t0 = pts[0][0]
    }
    const n = Math.max(0, pts.length - 1)
    const data = new Float32Array(n * 6 * FLOATS_PER_VERTEX)
    let prev = pts.length ? this.relative(pts[0][2], pts[0][1]) : [0, 0]
    let o = 0
    for (let i = 1; i < pts.length; i++) {
      const a = pts[i - 1]
      const b = pts[i]
      const cur = this.relative(b[2], b[1])
      const gap = isMeaningfulGap(a, b) ? 1 : 0
      const jump = b[0] - a[0] > GAP_MS ? 1 : 0
      const ta = this.seconds(a[0])
      const tb = this.seconds(b[0])
      for (const [along, side] of CORNERS) {
        data[o++] = prev[0]; data[o++] = prev[1]
        data[o++] = cur[0]; data[o++] = cur[1]
        data[o++] = ta; data[o++] = tb
        data[o++] = along; data[o++] = side
        data[o++] = gap; data[o++] = jump
      }
      prev = cur
    }
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer)
    gl.bufferData(gl.ARRAY_BUFFER, data, gl.STATIC_DRAW)
    this.vertexCount = n * 6
    return this.vertexCount
  }
}

export function routeLayer(id: string, r: RouteRenderer): CustomLayerInterface {
  let program: WebGLProgram
  let buffer: WebGLBuffer
  let u: (name: string) => WebGLUniformLocation | null
  const attrs: Record<string, number> = {}
  return {
    id,
    type: 'custom',
    renderingMode: '2d',
    onAdd(map, gl) {
      r.map = map
      program = compile(gl, ROUTE_VS, ROUTE_FS)
      buffer = gl.createBuffer()!
      u = uniforms(gl, program)
      for (const a of ['a_a', 'a_b', 'a_t', 'a_corner', 'a_flags']) attrs[a] = gl.getAttribLocation(program, a)
      // A new buffer (style reload) needs the geometry again.
      r.invalidate()
    },
    onRemove(_map, gl) {
      gl.deleteProgram(program)
      gl.deleteBuffer(buffer)
    },
    render(gl, options) {
      const count = r.upload(gl, buffer)
      if (!count) return
      const f = r.frame
      const p = r.palette
      gl.useProgram(program)
      gl.bindBuffer(gl.ARRAY_BUFFER, buffer)
      const stride = FLOATS_PER_VERTEX * 4
      const bind = (name: string, size: number, offset: number) => {
        gl.enableVertexAttribArray(attrs[name])
        gl.vertexAttribPointer(attrs[name], size, gl.FLOAT, false, stride, offset * 4)
      }
      bind('a_a', 2, 0)
      bind('a_b', 2, 2)
      bind('a_t', 2, 4)
      bind('a_corner', 2, 6)
      bind('a_flags', 2, 8)
      gl.uniformMatrix4fv(u('u_matrix'), false, r.matrix(options))
      gl.uniform2f(u('u_viewport'), gl.drawingBufferWidth, gl.drawingBufferHeight)
      gl.uniform1f(u('u_ratio'), window.devicePixelRatio || 1)
      const now = r.seconds(f.time)
      gl.uniform1f(u('u_now'), now)
      gl.uniform1f(u('u_detail'), f.detailMs / 1000)
      gl.uniform1f(u('u_total'), f.totalMs / 1000)
      gl.uniform4fv(u('u_recent'), rgba(p.recent))
      gl.uniform4fv(u('u_mid'), rgba(p.mid))
      gl.uniform4fv(u('u_old'), rgba(p.old))
      gl.enable(gl.BLEND)
      gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA)
      gl.disable(gl.DEPTH_TEST)
      gl.disable(gl.STENCIL_TEST)

      const pass = (mode: number, lo: number, hi: number, half: number, extra: number, color: string, jumpWhole: number) => {
        gl.uniform1f(u('u_mode'), mode)
        gl.uniform1f(u('u_lo'), lo)
        gl.uniform1f(u('u_hi'), hi)
        gl.uniform1f(u('u_half'), half)
        gl.uniform1f(u('u_extra'), extra)
        gl.uniform1f(u('u_jumpWhole'), jumpWhole)
        gl.uniform4fv(u('u_flat'), rgba(color))
        gl.drawArrays(gl.TRIANGLES, 0, count)
      }
      const from = now - f.totalMs / 1000
      // Ahead (faint), then the travelled route's casing under all of its line so joints never show seams.
      if (f.aheadMs > 0) pass(0, now, now + f.aheadMs / 1000, GHOST_HALF, 0, p.ghost, 1)
      pass(1, from, now, RECENT_HALF + CASING, CASING, p.casing, 0)
      pass(2, from, now, RECENT_HALF, 0, p.recent, 0)

      for (const a of Object.values(attrs)) gl.disableVertexAttribArray(a)
    },
  }
}

export function markerLayer(id: string, r: RouteRenderer): CustomLayerInterface {
  let program: WebGLProgram
  let buffer: WebGLBuffer
  let u: (name: string) => WebGLUniformLocation | null
  let corner = -1
  return {
    id,
    type: 'custom',
    renderingMode: '2d',
    onAdd(_map, gl) {
      program = compile(gl, MARKER_VS, MARKER_FS)
      buffer = gl.createBuffer()!
      gl.bindBuffer(gl.ARRAY_BUFFER, buffer)
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, -1, 1, 1, -1, 1, 1]), gl.STATIC_DRAW)
      u = uniforms(gl, program)
      corner = gl.getAttribLocation(program, 'a_corner')
    },
    onRemove(_map, gl) {
      gl.deleteProgram(program)
      gl.deleteBuffer(buffer)
    },
    render(gl, options) {
      const m = r.frame.marker
      if (!m || !r.map) return
      const ratio = window.devicePixelRatio || 1
      gl.useProgram(program)
      gl.bindBuffer(gl.ARRAY_BUFFER, buffer)
      gl.enableVertexAttribArray(corner)
      gl.vertexAttribPointer(corner, 2, gl.FLOAT, false, 0, 0)
      gl.uniformMatrix4fv(u('u_matrix'), false, r.matrix(options))
      gl.uniform2f(u('u_viewport'), gl.drawingBufferWidth, gl.drawingBufferHeight)
      gl.uniform2fv(u('u_pos'), r.relative(m.lng, m.lat))
      gl.uniform1f(u('u_size'), 22 * ratio)
      gl.uniform1f(u('u_ratio'), ratio)
      gl.uniform4fv(u('u_color'), rgba(r.palette.marker))
      gl.enable(gl.BLEND)
      gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA)
      gl.disable(gl.DEPTH_TEST)
      gl.disable(gl.STENCIL_TEST)
      gl.drawArrays(gl.TRIANGLES, 0, 6)
      gl.disableVertexAttribArray(corner)
    },
  }
}

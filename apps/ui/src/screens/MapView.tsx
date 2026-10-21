import { useEffect, useMemo, useRef, useState } from 'react'
import maplibregl, { type ExpressionSpecification, type GeoJSONSource, type StyleSpecification } from 'maplibre-gl'
import { positionAt, routeFeatures, lowerBound } from '../lib/geo'
import { MINUTE } from '../lib/time'
import type { Pin, TrackPoint } from '../lib/types'

const LIBERTY = 'https://tiles.openfreemap.org/styles/liberty'
const DARK = 'https://tiles.openfreemap.org/styles/dark'

/** Used when the tile server can't be reached so the route is still readable offline. */
const OFFLINE_STYLE: StyleSpecification = {
  version: 8,
  sources: {},
  layers: [{ id: 'bg', type: 'background', paint: { 'background-color': '#e8ebef' } }],
}

const prefersDark = () => window.matchMedia('(prefers-color-scheme: dark)').matches
const resolveStyle = (uri: string) => (uri === LIBERTY && prefersDark() ? DARK : uri)

/** The stock dark style is near-black with ~1.1:1 contrast between land, roads and water; lift them so the map reads. */
const DARK_PAINT: Record<string, [string, string | ExpressionSpecification][]> = {
  background: [['background-color', '#1a1d24']],
  water: [['fill-color', '#0f2a44']],
  waterway: [['line-color', '#0f2a44']],
  landuse_residential: [['fill-color', '#1d2028']],
  landcover_wood: [['fill-color', '#1b2b26']],
  landuse_park: [['fill-color', '#1b2b26']],
  building: [['fill-color', '#2b2f39'], ['fill-outline-color', '#3a3f4b']],
  highway_path: [['line-color', '#4a505c']],
  highway_minor: [['line-color', '#3d4350']],
  highway_major_casing: [['line-color', '#1a1d24']],
  highway_major_inner: [['line-color', '#5a6171']],
  highway_major_subtle: [['line-color', '#4a505c']],
  highway_motorway_casing: [['line-color', '#1a1d24']],
  highway_motorway_inner: [['line-color', '#7c8496']],
  highway_motorway_subtle: [['line-color', '#4a505c']],
  railway: [['line-color', '#4a505c']],
  railway_minor: [['line-color', '#4a505c']],
  railway_transit: [['line-color', '#4a505c']],
  boundary_state: [['line-color', '#5a6171']],
  water_name: [['text-color', '#7fb2e5'], ['text-halo-color', '#0f2a44']],
  highway_name_other: [['text-color', '#aeb6c4'], ['text-halo-color', '#14161b']],
  highway_name_motorway: [['text-color', '#c4cad6'], ['text-halo-color', '#14161b']],
  ...Object.fromEntries(
    ['other', 'suburb', 'village', 'town', 'city', 'city_large', 'state'].map((k) => [
      `place_${k}`,
      [['text-color', '#d3d8e0'], ['text-halo-color', '#14161b']] as [string, string][],
    ]),
  ),
}

function brightenDark(map: maplibregl.Map) {
  if (!(map.getStyle()?.name?.toLowerCase().includes('dark') ?? false)) return
  for (const [id, props] of Object.entries(DARK_PAINT)) {
    if (!map.getLayer(id)) continue
    for (const [k, v] of props) map.setPaintProperty(id, k, v)
  }
}

const EMPTY: GeoJSON.FeatureCollection = { type: 'FeatureCollection', features: [] }

// The map mounts before the timeline loads; opening where it was last left lets the right tiles start streaming at once.
const LAST_CAMERA_KEY = 'nm:lastCamera'
function lastCamera(): { center: [number, number]; zoom: number } | null {
  try {
    const v = JSON.parse(localStorage.getItem(LAST_CAMERA_KEY) ?? 'null')
    return Array.isArray(v?.center) && typeof v.zoom === 'number' ? v : null
  } catch {
    return null
  }
}

export interface MapViewProps {
  points: TrackPoint[]
  pins: Pin[]
  renderTime: number
  styleUri: string
  detailWindowMinutes: number
  totalWindowMinutes: number
  cameraWindowMinutes: number
  scrubbing: boolean
  /** Bumped to fly to the current position. */
  recenterKey: number
  /** Bumped to fit the camera window of the displayed time. */
  fitKey: number
  /** Screen areas covered by overlays, so the camera frames the route in the visible part only. */
  padding: { top: number; bottom: number }
  onPinTap: (pin: Pin) => void
  onOffline: (offline: boolean) => void
}

export function MapView(props: MapViewProps) {
  const host = useRef<HTMLDivElement>(null)
  const mapRef = useRef<maplibregl.Map | null>(null)
  const props$ = useRef(props)
  props$.current = props
  const [styleVersion, setStyleVersion] = useState(0)
  const usedFallback = useRef(false)
  // isStyleLoaded() also turns false while tiles stream in, so track style readiness ourselves.
  const styleReady = useRef(false)

  const route = useMemo(() => routeFeatures(props.points), [props.points])
  const pinFeatures = useMemo<GeoJSON.FeatureCollection>(
    () => ({
      type: 'FeatureCollection',
      features: props.pins
        .filter((p) => p.lat !== null && p.lng !== null)
        .map((p) => ({
          type: 'Feature',
          id: undefined,
          properties: { id: p.id, t: p.t, color: p.color },
          geometry: { type: 'Point', coordinates: [p.lng as number, p.lat as number] },
        })),
    }),
    [props.pins],
  )

  // Create the map once.
  useEffect(() => {
    const first = props$.current.points[0]
    const saved = lastCamera()
    const map = new maplibregl.Map({
      container: host.current!,
      style: resolveStyle(props$.current.styleUri),
      center: first ? [first[2], first[1]] : saved?.center ?? [127.0276, 37.4979],
      zoom: saved?.zoom ?? 14,
      attributionControl: false,
      pitchWithRotate: false,
      dragRotate: false,
      fadeDuration: 0,
    })
    map.touchZoomRotate.disableRotation()
    mapRef.current = map
    map.on('style.load', () => {
      styleReady.current = true
      setStyleVersion((v) => v + 1)
    })
    map.on('error', (e) => {
      // A failed style fetch leaves the map without a style; swap in the offline background once.
      if (!styleReady.current && !usedFallback.current && String((e as { error?: Error }).error?.message ?? '').match(/style|fetch|Failed/i)) {
        usedFallback.current = true
        props$.current.onOffline(true)
        styleReady.current = false
        map.setStyle(OFFLINE_STYLE)
      }
    })
    map.on('moveend', () => {
      const c = map.getCenter()
      try {
        localStorage.setItem(LAST_CAMERA_KEY, JSON.stringify({ center: [c.lng, c.lat], zoom: map.getZoom() }))
      } catch {
        // Storage unavailable: next launch just opens at the default spot.
      }
    })
    map.on('click', (e) => {
      if (!map.getLayer('pins')) return
      const hit = map.queryRenderedFeatures([[e.point.x - 14, e.point.y - 14], [e.point.x + 14, e.point.y + 14]], { layers: ['pins'] })[0]
      const id = hit?.properties?.id
      const pin = props$.current.pins.find((p) => p.id === id)
      if (pin) props$.current.onPinTap(pin)
    })
    const ro = new ResizeObserver(() => map.resize())
    ro.observe(host.current!)
    return () => {
      ro.disconnect()
      map.remove()
      mapRef.current = null
    }
  }, [])

  // Style changes from settings.
  const lastStyle = useRef(props.styleUri)
  useEffect(() => {
    if (lastStyle.current === props.styleUri) return
    lastStyle.current = props.styleUri
    usedFallback.current = false
    props.onOffline(false)
    styleReady.current = false
    mapRef.current?.setStyle(resolveStyle(props.styleUri))
  }, [props.styleUri, props])

  // (Re)create sources and layers whenever a style finishes loading.
  useEffect(() => {
    const map = mapRef.current
    if (!map || !styleReady.current) return
    const dark = map.getStyle()?.name?.toLowerCase().includes('dark') ?? false
    brightenDark(map)
    const casing = dark ? 'rgba(15,17,22,0.85)' : 'rgba(255,255,255,0.9)'
    if (!map.getSource('route')) {
      map.addSource('route', { type: 'geojson', data: EMPTY, tolerance: 0.4 })
      const line = (id: string, color: string, width: number, extra: Record<string, unknown> = {}) =>
        map.addLayer({
          id,
          type: 'line',
          source: 'route',
          layout: { 'line-cap': 'round', 'line-join': 'round' },
          paint: { 'line-color': color, 'line-width': width, ...extra },
        })
      line('route-old-casing', casing, 7)
      line('route-old', '#6aa6fa', 4)
      line('route-detail-casing', casing, 9)
      line('route-detail', '#1d5fe0', 6)
      line('route-gap', '#f59e0b', 3, { 'line-dasharray': [1.6, 1.6] })
    }
    if (!map.getSource('pins')) {
      map.addSource('pins', { type: 'geojson', data: EMPTY })
      map.addLayer({ id: 'pins-halo', type: 'circle', source: 'pins', paint: { 'circle-radius': 15, 'circle-color': ['get', 'color'], 'circle-opacity': 0.22 } })
      map.addLayer({ id: 'pins', type: 'circle', source: 'pins', paint: { 'circle-radius': 8, 'circle-color': ['get', 'color'], 'circle-stroke-color': '#fff', 'circle-stroke-width': 3 } })
    }
    if (!map.getSource('now')) {
      map.addSource('now', { type: 'geojson', data: EMPTY })
      map.addLayer({ id: 'now-halo', type: 'circle', source: 'now', paint: { 'circle-radius': 20, 'circle-color': '#3182f6', 'circle-opacity': 0.2 } })
      map.addLayer({ id: 'now', type: 'circle', source: 'now', paint: { 'circle-radius': 9, 'circle-color': '#3182f6', 'circle-stroke-color': '#fff', 'circle-stroke-width': 3.5 } })
    }
    ;(map.getSource('route') as GeoJSONSource).setData(route)
    ;(map.getSource('pins') as GeoJSONSource).setData(pinFeatures)
  }, [styleVersion, route, pinFeatures])

  // Time-dependent look: only filters/paint expressions change while scrubbing — no geometry is rebuilt.
  const { renderTime, detailWindowMinutes, totalWindowMinutes } = props
  useEffect(() => {
    const map = mapRef.current
    if (!map || !styleReady.current || !map.getLayer('route-old')) return
    const total = renderTime - totalWindowMinutes * MINUTE
    const detail = renderTime - detailWindowMinutes * MINUTE
    const t: ExpressionSpecification = ['get', 't']
    const solid: ExpressionSpecification = ['==', ['get', 'gap'], 0]
    const old: ExpressionSpecification = ['all', solid, ['>', t, total], ['<=', t, detail]]
    const recent: ExpressionSpecification = ['all', solid, ['>', t, detail], ['<=', t, renderTime]]
    map.setFilter('route-old', old)
    map.setFilter('route-old-casing', old)
    map.setFilter('route-detail', recent)
    map.setFilter('route-detail-casing', recent)
    map.setFilter('route-gap', ['all', ['==', ['get', 'gap'], 1], ['>', t, total], ['<=', t, renderTime]])
    const visible: ExpressionSpecification = ['all', ['>', t, total], ['<=', t, renderTime + 1000]]
    map.setFilter('pins', visible)
    map.setFilter('pins-halo', visible)
    const age: ExpressionSpecification = ['interpolate', ['linear'], ['-', renderTime, t], 0, 1, totalWindowMinutes * MINUTE, 0.4]
    map.setPaintProperty('pins', 'circle-opacity', age)
    map.setPaintProperty('pins', 'circle-stroke-opacity', age)

    const pos = positionAt(props$.current.points, renderTime)
    ;(map.getSource('now') as GeoJSONSource | undefined)?.setData(
      pos ? { type: 'Feature', properties: {}, geometry: { type: 'Point', coordinates: [pos.lng, pos.lat] } } : EMPTY,
    )

    // While dragging, only pan when the marker nears the edge of the visible area.
    if (props$.current.scrubbing && pos) {
      const { top, bottom } = props$.current.padding
      const p = map.project([pos.lng, pos.lat])
      const h = map.getContainer().clientHeight
      const w = map.getContainer().clientWidth
      if (p.x < 60 || p.x > w - 60 || p.y < top + 40 || p.y > h - bottom - 40) {
        map.easeTo({ center: [pos.lng, pos.lat], duration: 220 })
      }
    }
  }, [renderTime, detailWindowMinutes, totalWindowMinutes, styleVersion, route])

  // Camera: frame the camera window when a scrub ends / the day changes.
  useEffect(() => {
    const map = mapRef.current
    if (!map || props.fitKey === 0) return
    const { renderTime: rt, cameraWindowMinutes, points, padding } = props$.current
    const from = lowerBound(points, rt - cameraWindowMinutes * MINUTE)
    const to = lowerBound(points, rt + 1)
    const slice = points.slice(from, to)
    const pos = positionAt(points, rt)
    if (!slice.length && !pos) return
    const bounds = new maplibregl.LngLatBounds()
    slice.forEach((p) => bounds.extend([p[2], p[1]]))
    if (pos) bounds.extend([pos.lng, pos.lat])
    // A stationary stretch collapses to a point; keep a ~250m minimum frame so the map stays readable.
    const c = bounds.getCenter()
    bounds.extend([c.lng - 0.0016, c.lat - 0.0012]).extend([c.lng + 0.0016, c.lat + 0.0012])
    map.fitBounds(bounds, {
      padding: { top: padding.top + 24, bottom: padding.bottom + 24, left: 48, right: 48 },
      maxZoom: 17,
      // The first frame after launch should land directly on the route.
      duration: props.fitKey === 1 ? 0 : 500,
    })
  }, [props.fitKey])

  useEffect(() => {
    const map = mapRef.current
    if (!map || props.recenterKey === 0) return
    const pos = positionAt(props$.current.points, props$.current.renderTime)
    if (pos) map.flyTo({ center: [pos.lng, pos.lat], zoom: Math.max(map.getZoom(), 16), duration: 600 })
  }, [props.recenterKey])

  // maplibre's stylesheet forces position:relative on its container, so keep it inside a plain absolute wrapper.
  return (
    <div className="absolute inset-0">
      <div ref={host} style={{ width: '100%', height: '100%' }} />
    </div>
  )
}

import { useEffect, useMemo, useRef, useState } from 'react'
import maplibregl, { type ExpressionSpecification, type GeoJSONSource, type LngLatBoundsLike, type StyleSpecification } from 'maplibre-gl'
import { positionAt, lowerBound } from '../lib/geo'
import { DARK_ROUTE, LIGHT_ROUTE, RouteRenderer, markerLayer, routeLayer } from '../lib/routeLayer'
import { MINUTE } from '../lib/time'
import type { Pin, TrackPoint } from '../lib/types'

const LIBERTY = 'https://tiles.openfreemap.org/styles/liberty'
const DARK = 'https://tiles.openfreemap.org/styles/dark'

const offlineStyle = (dark: boolean): StyleSpecification => ({
  version: 8,
  sources: {},
  layers: [{ id: 'bg', type: 'background', paint: { 'background-color': dark ? '#22262e' : '#e8ebef' } }],
})

const prefersDark = () => window.matchMedia('(prefers-color-scheme: dark)').matches
const resolveStyle = (uri: string) => (uri === LIBERTY && prefersDark() ? DARK : uri)

const LAND = '#22262e'
const LABEL_HALO = '#15181d'

/**
 * The stock dark style is near-black with ~1.1:1 contrast between land, roads and water; lift them so the map reads.
 * Keyed by the style's layer ids (the style JSON has no `name`, so dark-ness is tracked from the URI we asked for).
 */
const DARK_PAINT: Record<string, [string, string | number | ExpressionSpecification][]> = {
  background: [['background-color', LAND]],
  water: [['fill-color', '#163a5c']],
  waterway: [['line-color', '#163a5c']],
  landcover_ice_shelf: [['fill-color', '#2c313a']],
  landcover_glacier: [['fill-color', '#2c313a']],
  landuse_residential: [['fill-color', '#272b34'], ['fill-opacity', 1]],
  landcover_wood: [['fill-color', '#1f3b30'], ['fill-opacity', 0.8]],
  landuse_park: [['fill-color', '#1f3b30']],
  building: [['fill-color', '#323844'], ['fill-outline-color', '#404754']],
  'aeroway-area': [['fill-color', '#2c313a']],
  'aeroway-taxiway': [['line-color', '#454c5a']],
  'aeroway-runway-casing': [['line-color', '#2c313a']],
  'aeroway-runway': [['line-color', '#454c5a']],
  road_area_pier: [['fill-color', LAND]],
  road_pier: [['line-color', LAND]],
  highway_path: [['line-color', '#4f5665']],
  highway_minor: [['line-color', '#464e5d'], ['line-opacity', 1]],
  highway_major_casing: [['line-color', '#1a1d23']],
  highway_major_inner: [['line-color', '#646d80']],
  highway_major_subtle: [['line-color', '#4f5665']],
  highway_motorway_casing: [['line-color', '#1a1d23']],
  highway_motorway_inner: [['line-color', '#8f8166']],
  highway_motorway_subtle: [['line-color', '#5d5747']],
  railway: [['line-color', '#5a6170']],
  railway_minor: [['line-color', '#5a6170']],
  railway_transit: [['line-color', '#5a6170']],
  railway_dashline: [['line-color', LAND]],
  railway_minor_dashline: [['line-color', LAND]],
  railway_transit_dashline: [['line-color', LAND]],
  boundary_state: [['line-color', '#5f6676']],
  'boundary_country_z0-4': [['line-color', '#7a8292']],
  'boundary_country_z5-': [['line-color', '#7a8292']],
  water_name: [['text-color', '#8dbbe8'], ['text-halo-color', '#163a5c']],
  highway_name_other: [['text-color', '#b4bcc9'], ['text-halo-color', LABEL_HALO]],
  highway_name_motorway: [['text-color', '#d0c4a8'], ['text-halo-color', LABEL_HALO]],
  ...Object.fromEntries(
    ['other', 'suburb', 'village', 'town', 'city', 'city_large', 'state', 'country_other', 'country_minor', 'country_major'].map((k) => [
      `place_${k}`,
      [['text-color', '#dfe3ea'], ['text-halo-color', LABEL_HALO]] as [string, string][],
    ]),
  ),
}

function brightenDark(map: maplibregl.Map) {
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

/**
 * - `commit`: a scrub ended — reframe only while following.
 * - `jump`: moved to another time on purpose (day, pin, "now") — follow again and frame it.
 * - `locate`: the locate button — follow again; when already framed, zoom in on the marker.
 */
export type FrameKind = 'commit' | 'jump' | 'locate'
export interface FrameRequest {
  key: number
  kind: FrameKind
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
  frame: FrameRequest
  /** Screen areas covered by overlays, so the camera frames the route in the visible part only. */
  padding: { top: number; bottom: number }
  /** Following turns off when the user moves the map by hand. */
  onFollowChange: (following: boolean) => void
  onPinTap: (pin: Pin) => void
  onOffline: (offline: boolean) => void
}

/** Closest the camera frames a stop: about 450m across on a phone, enough to see the surrounding streets. */
const FRAME_MAX_ZOOM = 15.8
const LOCATE_ZOOM = 17
/** How far from the edges the marker may roam (share of the visible area) before the camera follows it. */
const COMFORT_FOLLOWING = 0.24
const COMFORT_FREE = 0.14
/** Time constant of the camera chasing the marker. */
const CHASE_TAU_MS = 110
const FRAME_MARGIN = { top: 32, bottom: 32, left: 44, right: 44 }
const easeOutCubic = (x: number) => 1 - (1 - x) ** 3

export function MapView(props: MapViewProps) {
  const host = useRef<HTMLDivElement>(null)
  const mapRef = useRef<maplibregl.Map | null>(null)
  const props$ = useRef(props)
  props$.current = props
  const [styleVersion, setStyleVersion] = useState(0)
  const usedFallback = useRef(false)
  // isStyleLoaded() also turns false while tiles stream in, so track style readiness ourselves.
  const styleReady = useRef(false)
  // Whether the style currently requested is a dark one (the dark style JSON carries no name to tell by).
  const darkStyle = useRef(false)
  const renderer = useMemo(() => new RouteRenderer(), [])
  const following = useRef(true)
  const chaseRaf = useRef(0)
  const chaseLast = useRef(0)

  const setFollowing = (v: boolean) => {
    if (following.current === v) return
    following.current = v
    props$.current.onFollowChange(v)
  }

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

  /** The part of the canvas not covered by overlays, in CSS px. */
  const visibleArea = (map: maplibregl.Map) => {
    const { top, bottom } = props$.current.padding
    const c = map.getContainer()
    return { left: 0, top, right: c.clientWidth, bottom: Math.max(top + 1, c.clientHeight - bottom) }
  }

  const stopChase = () => {
    cancelAnimationFrame(chaseRaf.current)
    chaseRaf.current = 0
  }

  /** Pans (never zooms) just enough to keep the marker inside the comfort area, easing like a spring. */
  const chaseStep = (now: number) => {
    chaseRaf.current = 0
    const map = mapRef.current
    const pos = renderer.frame.marker
    if (!map || !pos) return
    const dt = Math.min(64, Math.max(0, now - chaseLast.current))
    chaseLast.current = now
    const v = visibleArea(map)
    const inset = following.current ? COMFORT_FOLLOWING : COMFORT_FREE
    const ix = (v.right - v.left) * inset
    const iy = (v.bottom - v.top) * inset
    const p = map.project([pos.lng, pos.lat])
    const dx = p.x < v.left + ix ? p.x - (v.left + ix) : p.x > v.right - ix ? p.x - (v.right - ix) : 0
    const dy = p.y < v.top + iy ? p.y - (v.top + iy) : p.y > v.bottom - iy ? p.y - (v.bottom - iy) : 0
    if (Math.abs(dx) < 0.5 && Math.abs(dy) < 0.5) return
    // Far away (a fast fling, a subway hop): skip most of the way instead of sweeping across the city.
    const far = Math.hypot(dx, dy)
    const reach = Math.max(v.right - v.left, v.bottom - v.top)
    const k = far > reach ? 1 - reach / 2 / far : 1 - Math.exp(-dt / CHASE_TAU_MS)
    const c = map.project(map.getCenter())
    map.jumpTo({ center: map.unproject([c.x + dx * k, c.y + dy * k]) })
    chaseRaf.current = requestAnimationFrame(chaseStep)
  }

  const chase = () => {
    if (chaseRaf.current) return
    chaseLast.current = performance.now()
    chaseRaf.current = requestAnimationFrame(chaseStep)
  }

  // Create the map once.
  useEffect(() => {
    const first = props$.current.points[0]
    const saved = lastCamera()
    const map = new maplibregl.Map({
      container: host.current!,
      style: (() => {
        const uri = resolveStyle(props$.current.styleUri)
        darkStyle.current = uri === DARK
        return uri
      })(),
      center: first ? [first[2], first[1]] : saved?.center ?? [127.0276, 37.4979],
      zoom: saved?.zoom ?? 14,
      attributionControl: false,
      pitchWithRotate: false,
      dragRotate: false,
      fadeDuration: 0,
    })
    map.touchZoomRotate.disableRotation()
    mapRef.current = map
    renderer.map = map
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
        map.setStyle(offlineStyle(darkStyle.current))
      }
    })
    // Only gestures carry the DOM event; our own camera moves don't.
    map.on('movestart', (e) => {
      if (!(e as { originalEvent?: Event }).originalEvent) return
      stopChase()
      setFollowing(false)
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
      stopChase()
      ro.disconnect()
      map.remove()
      mapRef.current = null
      renderer.map = null
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Style changes from settings.
  const lastStyle = useRef(props.styleUri)
  useEffect(() => {
    if (lastStyle.current === props.styleUri) return
    lastStyle.current = props.styleUri
    usedFallback.current = false
    props.onOffline(false)
    styleReady.current = false
    const uri = resolveStyle(props.styleUri)
    darkStyle.current = uri === DARK
    mapRef.current?.setStyle(uri)
  }, [props.styleUri, props])

  // (Re)create layers whenever a style finishes loading, above everything the style draws (3D buildings and labels
  // included) so nothing on the map hides the route: route, then pins, then the marker.
  useEffect(() => {
    const map = mapRef.current
    if (!map || !styleReady.current) return
    const dark = darkStyle.current
    if (dark) brightenDark(map)
    renderer.palette = dark ? DARK_ROUTE : LIGHT_ROUTE
    if (!map.getLayer('route')) map.addLayer(routeLayer('route', renderer))
    if (!map.getSource('pins')) {
      map.addSource('pins', { type: 'geojson', data: EMPTY })
      map.addLayer({ id: 'pins-halo', type: 'circle', source: 'pins', paint: { 'circle-radius': 15, 'circle-color': ['get', 'color'], 'circle-opacity': 0.22 } })
      map.addLayer({ id: 'pins', type: 'circle', source: 'pins', paint: { 'circle-radius': 8, 'circle-color': ['get', 'color'], 'circle-stroke-color': '#fff', 'circle-stroke-width': 3 } })
    }
    if (!map.getLayer('marker')) map.addLayer(markerLayer('marker', renderer))
    ;(map.getSource('pins') as GeoJSONSource).setData(pinFeatures)
  }, [styleVersion, pinFeatures, renderer])

  useEffect(() => renderer.setPoints(props.points), [props.points, renderer])

  // Time-dependent look. The route only needs new uniforms; pins change filters, so they move in coarser steps.
  const { renderTime, detailWindowMinutes, totalWindowMinutes, points } = props
  const pinTime = Math.floor(renderTime / 15_000) * 15_000
  useEffect(() => {
    const pos = positionAt(points, renderTime)
    renderer.setFrame({
      time: renderTime,
      detailMs: detailWindowMinutes * MINUTE,
      totalMs: totalWindowMinutes * MINUTE,
      aheadMs: totalWindowMinutes * MINUTE,
      marker: pos && { lng: pos.lng, lat: pos.lat },
    })
    if (props$.current.scrubbing || following.current) chase()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [renderTime, detailWindowMinutes, totalWindowMinutes, points, renderer])

  useEffect(() => {
    const map = mapRef.current
    if (!map || !styleReady.current || !map.getLayer('pins')) return
    const total = pinTime - totalWindowMinutes * MINUTE
    const t: ExpressionSpecification = ['get', 't']
    const visible: ExpressionSpecification = ['all', ['>', t, total], ['<=', t, pinTime + 15_000]]
    map.setFilter('pins', visible)
    map.setFilter('pins-halo', visible)
    const age: ExpressionSpecification = ['interpolate', ['linear'], ['-', pinTime, t], 0, 1, totalWindowMinutes * MINUTE, 0.4]
    map.setPaintProperty('pins', 'circle-opacity', age)
    map.setPaintProperty('pins', 'circle-stroke-opacity', age)
  }, [pinTime, totalWindowMinutes, styleVersion])

  // Camera framing on request.
  useEffect(() => {
    const map = mapRef.current
    const { key, kind } = props.frame
    if (!map || key === 0) return
    const { renderTime: rt, cameraWindowMinutes, points } = props$.current
    const pos = positionAt(points, rt)
    if (!pos) return
    stopChase()
    const wasFollowing = following.current
    if (kind !== 'commit') setFollowing(true)
    const v = visibleArea(map)
    const centerOffset: [number, number] = [0, (v.top - (map.getContainer().clientHeight - v.bottom)) / 2]

    if (!following.current) {
      // A map the user moved stays put unless the marker has left the screen.
      const p = map.project([pos.lng, pos.lat])
      if (p.x >= v.left + 24 && p.x <= v.right - 24 && p.y >= v.top + 24 && p.y <= v.bottom - 24) return
      map.easeTo({ center: [pos.lng, pos.lat], offset: centerOffset, duration: 450, easing: easeOutCubic })
      return
    }

    // Frame the recent stretch of route that led here.
    const from = lowerBound(points, rt - cameraWindowMinutes * MINUTE)
    const to = lowerBound(points, rt + 1)
    const bounds = new maplibregl.LngLatBounds([pos.lng, pos.lat], [pos.lng, pos.lat])
    for (let i = from; i < to; i++) bounds.extend([points[i][2], points[i][1]])
    const container = map.getContainer()
    const margin = {
      top: v.top + FRAME_MARGIN.top,
      bottom: container.clientHeight - v.bottom + FRAME_MARGIN.bottom,
      left: FRAME_MARGIN.left,
      right: FRAME_MARGIN.right,
    }
    const cam = map.cameraForBounds(bounds as LngLatBoundsLike, { padding: margin, maxZoom: FRAME_MAX_ZOOM })
    if (!cam || cam.zoom === undefined || !cam.center) return
    const zoom = map.getZoom()

    // The first frame after launch lands directly on the route.
    if (key === 1) {
      map.jumpTo({ center: cam.center, zoom: cam.zoom })
      return
    }

    // Already showing all of it at a similar scale: leave the camera alone rather than nudge it on every scrub.
    const sw = map.project(bounds.getSouthWest())
    const ne = map.project(bounds.getNorthEast())
    const inView = Math.min(sw.x, ne.x) >= v.left + 12 && Math.max(sw.x, ne.x) <= v.right - 12 && Math.min(sw.y, ne.y) >= v.top + 12 && Math.max(sw.y, ne.y) <= v.bottom - 12
    const settled = inView && cam.zoom - zoom < 0.75 && zoom - cam.zoom < 1.25
    if (settled) {
      if (kind === 'locate' && wasFollowing) {
        map.easeTo({ center: [pos.lng, pos.lat], offset: centerOffset, zoom: Math.max(zoom, LOCATE_ZOOM), duration: 500, easing: easeOutCubic })
      }
      return
    }

    const target = map.project(cam.center)
    const dist = Math.hypot(target.x - container.clientWidth / 2, target.y - container.clientHeight / 2)
    if (dist > 2.5 * Math.max(container.clientWidth, container.clientHeight) || Math.abs(cam.zoom - zoom) > 3) {
      map.flyTo({ center: cam.center, zoom: cam.zoom, speed: 1.6, curve: 1.42, maxDuration: 1600 })
    } else {
      map.easeTo({ center: cam.center, zoom: cam.zoom, duration: Math.min(750, 380 + dist * 0.3), easing: easeOutCubic })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [props.frame])

  // maplibre's stylesheet forces position:relative on its container, so keep it inside a plain absolute wrapper.
  return (
    <div className="absolute inset-0">
      <div ref={host} style={{ width: '100%', height: '100%' }} />
    </div>
  )
}

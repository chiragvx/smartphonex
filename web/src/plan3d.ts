// The plan in 3D: the route as a curtain from the ground up to its height, over satellite imagery and, if wanted,
// terrain. Loaded only when someone opens it, so the rest of the ground station stays small.
import * as maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';

type Path = [number, number, number, number][];
export type Scene = {
  path: Path;
  gaps: { fromS: number; toS: number }[];
  marks: { lat: number; lon: number; kind: string; text: string }[];
  zones: { at: [number, number]; radius?: number; kind: string }[];
  /** A tile URL template for satellite imagery, {z}/{x}/{y}. */
  imagery: string;
};

/** A thin wall under each piece of the route, as tall as its height above home. */
function curtain(path: Path, inGap: (t: number) => boolean) {
  const features: any[] = [];
  for (let i = 1; i < path.length; i++) {
    const [la1, lo1, h1, t1] = path[i - 1], [la2, lo2, h2] = path[i];
    const k = Math.cos(la1 * Math.PI / 180);
    const dx = (lo2 - lo1) * k, dy = la2 - la1;
    const len = Math.hypot(dx, dy);
    if (len < 1e-9) continue;
    // 0.8 m either side of the line, in degrees.
    const w = 0.8 / 111_320;
    const nx = -dy / len * w / k, ny = dx / len * w;
    features.push({
      type: 'Feature',
      properties: { h: Math.max(0.5, (h1 + h2) / 2), gap: inGap(t1) },
      geometry: { type: 'Polygon', coordinates: [[[lo1 + nx, la1 + ny], [lo2 + nx, la2 + ny], [lo2 - nx, la2 - ny], [lo1 - nx, la1 - ny], [lo1 + nx, la1 + ny]]] },
    });
  }
  return { type: 'FeatureCollection', features } as any;
}

const circle = (lat: number, lon: number, r: number) => {
  const k = Math.cos(lat * Math.PI / 180);
  const ring = Array.from({ length: 37 }, (_, i) => { const a = i * Math.PI / 18; return [lon + Math.sin(a) * r / (111_320 * k), lat + Math.cos(a) * r / 111_320]; });
  return { type: 'Polygon', coordinates: [ring] };
};

/** Shows the scene in [el]. Returns a function that changes terrain, and one that closes it. */
export function mount3d(el: HTMLElement, s: Scene, terrain: boolean) {
  const inGap = (t: number) => s.gaps.some(g => t >= g.fromS && t <= g.toS);
  const lats = s.path.map(p => p[0]), lons = s.path.map(p => p[1]);
  const map = new maplibregl.Map({
    container: el,
    style: {
      version: 8,
      sources: {
        imagery: { type: 'raster', tiles: [s.imagery], tileSize: 256, maxzoom: 19, attribution: 'Imagery Esri, Maxar, Earthstar Geographics' },
        dem: { type: 'raster-dem', tiles: ['https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png'], encoding: 'terrarium', tileSize: 256, maxzoom: 15, attribution: 'Terrain: Mapzen, AWS Open Data' },
      },
      layers: [{ id: 'imagery', type: 'raster', source: 'imagery' }],
    },
    center: [lons.length ? (Math.min(...lons) + Math.max(...lons)) / 2 : 0, lats.length ? (Math.min(...lats) + Math.max(...lats)) / 2 : 0],
    zoom: 15, pitch: 60, bearing: -20, maxPitch: 80,
  });
  map.addControl(new maplibregl.NavigationControl({ visualizePitch: true }));
  map.on('load', () => {
    map.addSource('route', { type: 'geojson', data: { type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: s.path.map(p => [p[1], p[0]]) } } as any });
    map.addSource('curtain', { type: 'geojson', data: curtain(s.path, inGap) });
    map.addSource('zones', { type: 'geojson', data: { type: 'FeatureCollection', features: s.zones.map(z => ({ type: 'Feature', properties: { kind: z.kind }, geometry: circle(z.at[0], z.at[1], z.radius ?? 20) })) } as any });
    map.addSource('marks', { type: 'geojson', data: { type: 'FeatureCollection', features: s.marks.map(m => ({ type: 'Feature', properties: { kind: m.kind, text: m.text }, geometry: { type: 'Point', coordinates: [m.lon, m.lat] } })) } as any });
    map.addLayer({ id: 'zones', type: 'fill', source: 'zones', paint: { 'fill-color': ['match', ['get', 'kind'], 'ditch', '#fb923c', '#4ade80'], 'fill-opacity': 0.35 } });
    map.addLayer({ id: 'ground', type: 'line', source: 'route', paint: { 'line-color': '#ffffff', 'line-width': 2, 'line-opacity': 0.7, 'line-dasharray': [2, 2] } });
    map.addLayer({ id: 'curtain', type: 'fill-extrusion', source: 'curtain', paint: { 'fill-extrusion-color': ['case', ['get', 'gap'], '#e5484d', '#58a6ff'], 'fill-extrusion-height': ['get', 'h'], 'fill-extrusion-base': 0, 'fill-extrusion-opacity': 0.75 } });
    map.addLayer({ id: 'marks', type: 'circle', source: 'marks', paint: { 'circle-radius': 5, 'circle-color': ['match', ['get', 'kind'], 'action', '#facc15', 'circle', '#e5a823', '#c4b5fd'], 'circle-stroke-width': 1.5, 'circle-stroke-color': '#0e1116' } });
    if (lats.length) map.fitBounds([[Math.min(...lons), Math.min(...lats)], [Math.max(...lons), Math.max(...lats)]], { padding: 60, pitch: 60, duration: 0 });
    if (terrain) map.setTerrain({ source: 'dem', exaggeration: 1 });
  });
  const popup = new maplibregl.Popup({ closeButton: false });
  map.on('click', 'marks', (e: maplibregl.MapLayerMouseEvent) => { const f = e.features?.[0]; if (f) popup.setLngLat(e.lngLat).setText(String(f.properties?.text ?? '')).addTo(map); });
  return {
    setTerrain: (on: boolean) => { if (map.isStyleLoaded()) map.setTerrain(on ? { source: 'dem', exaggeration: 1 } : null); },
    close: () => map.remove(),
  };
}

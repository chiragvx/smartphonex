// A small tile map on a canvas. It's our own because the overlays and hit-testing are simple, and it
// keeps the bundle small enough for a phone to serve over a mobile link.
export type LatLon = { lat: number; lon: number };
export type Layer = 'satellite' | 'street';
const TILE = 256;
/** Zoom levels: tiles stop at 19, and the last two stretch them, for small plans like a 20 m test square. */
const MIN_ZOOM = 2, MAX_ZOOM = 21;
/** How far the map moves before a press counts as a drag rather than a click, in pixels. */
const DRAG_START = 5;
const SOURCES: Record<Layer, { url: (z: number, x: number, y: number) => string; credit: string; maxZ: number }> = {
  satellite: { url: (z, x, y) => `https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/${z}/${y}/${x}`, credit: 'Imagery Esri, Maxar, Earthstar Geographics', maxZ: 19 },
  street: { url: (z, x, y) => `https://tile.openstreetmap.org/${z}/${x}/${y}.png`, credit: '© OpenStreetMap contributors', maxZ: 19 },
};

/** Where to ask for a tile instead of its source: set when the phone can serve them. Null asks the source. */
let tileVia: ((layer: Layer, z: number, x: number, y: number) => string | null) | null = null;
export const setTileVia = (f: typeof tileVia) => { tileVia = f; };

const project = (lat: number, lon: number, z: number) => {
  const s = Math.sin(lat * Math.PI / 180);
  const n = TILE * 2 ** z;
  return { x: (lon + 180) / 360 * n, y: (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * n };
};
const unproject = (x: number, y: number, z: number): LatLon => {
  const n = TILE * 2 ** z;
  return { lon: x / n * 360 - 180, lat: Math.atan(Math.sinh(Math.PI * (1 - 2 * y / n))) * 180 / Math.PI };
};

export class TileMap {
  center: LatLon = { lat: 20, lon: 78 };
  zoom = 3;
  layer: Layer = 'satellite';
  overlay: (m: TileMap, g: CanvasRenderingContext2D) => void = () => {};
  /** Returns true if it took the press, such as starting to drag a waypoint. */
  onPress: (p: LatLon, x: number, y: number) => boolean = () => false;
  onDrag: (p: LatLon) => void = () => {};
  onRelease: () => void = () => {};
  onClick: (p: LatLon, x: number, y: number) => void = () => {};
  userMoved = false;
  private tiles = new Map<string, HTMLImageElement>();
  private g: CanvasRenderingContext2D;
  private dead = false;
  private pointers = new Map<number, { x: number; y: number }>();
  private pinch: { spread: number; zoom: number; mid: { x: number; y: number } } | null = null;
  /** Where an eased zoom is heading, and the screen point that stays put while it does. */
  private target: { zoom: number; x: number; y: number } | null = null;
  private dragging: 'map' | 'item' | null = null;
  private moved = 0;
  private ro: ResizeObserver;

  constructor(private c: HTMLCanvasElement) {
    this.g = c.getContext('2d')!;
    this.ro = new ResizeObserver(() => this.draw());
    this.ro.observe(c);
    c.style.touchAction = 'none';
    c.addEventListener('wheel', e => {
      e.preventDefault();
      const r = c.getBoundingClientRect();
      // A mouse notch (about 100 px) is half a level; a trackpad's many small steps add up the same way.
      // Ctrl+wheel is a trackpad pinch, whose steps are smaller.
      const px = e.deltaY * (e.deltaMode === 1 ? 33 : e.deltaMode === 2 ? 600 : 1);
      const step = Math.max(-0.75, Math.min(0.75, -px * (e.ctrlKey ? 0.012 : 0.005)));
      this.easeZoom(e.clientX - r.left, e.clientY - r.top, step);
    }, { passive: false });
    c.addEventListener('pointerdown', e => this.down(e));
    c.addEventListener('pointermove', e => this.move(e));
    c.addEventListener('pointerup', e => this.up(e));
    c.addEventListener('pointercancel', e => this.up(e));
  }
  destroy() { this.dead = true; this.ro.disconnect(); }

  toScreen(lat: number, lon: number) {
    const p = project(lat, lon, this.zoom);
    const m = project(this.center.lat, this.center.lon, this.zoom);
    return { x: p.x - m.x + this.w / 2, y: p.y - m.y + this.h / 2 };
  }
  fromScreen(x: number, y: number): LatLon {
    const m = project(this.center.lat, this.center.lon, this.zoom);
    return unproject(m.x + x - this.w / 2, m.y + y - this.h / 2, this.zoom);
  }
  private get w() { return this.c.clientWidth; }
  get width() { return this.w; }
  get height() { return this.h; }
  private get h() { return this.c.clientHeight; }
  metersPerPixel() { return 156543.03 * Math.cos(this.center.lat * Math.PI / 180) / 2 ** this.zoom; }

  /** Sets the zoom straight away, keeping the map under screen point (x, y) where it is. */
  zoomTo(x: number, y: number, zoom: number) {
    const z = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
    const p = this.fromScreen(x, y);
    this.zoom = z;
    // Put that same place back under (x, y).
    const pp = project(p.lat, p.lon, z);
    this.center = unproject(pp.x - (x - this.w / 2), pp.y - (y - this.h / 2), z);
    this.userMoved = true;
    this.draw();
  }
  /** Whole steps, for buttons and older callers. Eased, around (x, y). */
  zoomAt(x: number, y: number, dir: number) { this.easeZoom(x, y, dir); }
  /** Zooms by [by] levels around the middle, eased: for the + and − buttons. */
  zoomBy(by: number) { this.easeZoom(this.w / 2, this.h / 2, by); }
  private easeZoom(x: number, y: number, by: number) {
    const from = this.target?.zoom ?? this.zoom;
    this.target = { zoom: Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, from + by)), x, y };
    this.userMoved = true;
    this.step();
  }
  private stepping = false;
  private step() {
    if (this.stepping || this.dead) return;
    this.stepping = true;
    requestAnimationFrame(() => {
      this.stepping = false;
      const t = this.target;
      if (!t) return;
      const d = t.zoom - this.zoom;
      // A third of the way each frame: about 150 ms to settle, smooth but never sluggish.
      if (Math.abs(d) < 0.002) { this.zoomTo(t.x, t.y, t.zoom); this.target = null; return; }
      this.zoomTo(t.x, t.y, this.zoom + d * 0.35);
      this.step();
    });
  }
  fit(points: LatLon[]) {
    if (!points.length) return;
    const lats = points.map(p => p.lat), lons = points.map(p => p.lon);
    this.center = { lat: (Math.min(...lats) + Math.max(...lats)) / 2, lon: (Math.min(...lons) + Math.max(...lons)) / 2 };
    this.target = null;
    for (this.zoom = 19; this.zoom > 2; this.zoom--) {
      const a = project(Math.max(...lats), Math.min(...lons), this.zoom), b = project(Math.min(...lats), Math.max(...lons), this.zoom);
      if (b.x - a.x < this.w * 0.8 && b.y - a.y < this.h * 0.8) break;
    }
    this.draw();
  }

  private pos(e: PointerEvent) { const r = this.c.getBoundingClientRect(); return { x: e.clientX - r.left, y: e.clientY - r.top }; }
  private down(e: PointerEvent) {
    this.c.setPointerCapture(e.pointerId);
    const p = this.pos(e);
    this.pointers.set(e.pointerId, p);
    this.moved = 0;
    if (this.pointers.size === 2) { this.target = null; this.pinch = { spread: this.spread(), zoom: this.zoom, mid: this.mid() }; this.dragging = 'map'; return; }
    this.dragging = this.onPress(this.fromScreen(p.x, p.y), p.x, p.y) ? 'item' : 'map';
  }
  private spread() { const [a, b] = [...this.pointers.values()]; return Math.max(1, Math.hypot(a.x - b.x, a.y - b.y)); }
  private mid() { const [a, b] = [...this.pointers.values()]; return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }; }
  private move(e: PointerEvent) {
    const last = this.pointers.get(e.pointerId);
    if (!last) return;
    const p = this.pos(e);
    this.pointers.set(e.pointerId, p);
    const dx = p.x - last.x, dy = p.y - last.y;
    this.moved += Math.abs(dx) + Math.abs(dy);
    if (this.pointers.size === 2 && this.pinch) {
      // Two fingers: the zoom follows how far apart they are, and the map follows their middle.
      const mid = this.mid();
      const m = project(this.center.lat, this.center.lon, this.zoom);
      this.center = unproject(m.x - (mid.x - this.pinch.mid.x), m.y - (mid.y - this.pinch.mid.y), this.zoom);
      this.pinch.mid = mid;
      this.zoomTo(mid.x, mid.y, this.pinch.zoom + Math.log2(this.spread() / this.pinch.spread));
      return;
    }
    if (this.dragging === 'item') { this.onDrag(this.fromScreen(p.x, p.y)); return; }
    // A press that hasn't moved a few pixels is still a click: the map stays put.
    if (this.dragging === 'map' && this.moved >= DRAG_START) {
      const m = project(this.center.lat, this.center.lon, this.zoom);
      this.center = unproject(m.x - dx, m.y - dy, this.zoom);
      this.userMoved = true;
      this.draw();
    }
  }
  private up(e: PointerEvent) {
    const p = this.pointers.get(e.pointerId);
    this.pointers.delete(e.pointerId);
    if (this.pointers.size < 2) this.pinch = null;
    if (!p || this.pointers.size) return;
    if (this.dragging === 'item') this.onRelease();
    else if (this.moved < DRAG_START) this.onClick(this.fromScreen(p.x, p.y), p.x, p.y);
    this.dragging = null;
  }

  private tile(z: number, x: number, y: number) {
    const key = `${this.layer}/${z}/${x}/${y}`;
    let t = this.tiles.get(key);
    if (!t) {
      if (this.tiles.size > 400) this.tiles.clear();
      const img = new Image();
      t = img;
      // The relay's page hides its address from others, but OSM's terms need a referrer on requests.
      img.referrerPolicy = 'strict-origin-when-cross-origin';
      img.onload = () => this.draw();
      const direct = SOURCES[this.layer].url(z, x, y);
      // Through the phone when connected to it directly: it keeps each tile, so the map works in the field offline.
      const via = tileVia?.(this.layer, z, x, y);
      if (via) img.onerror = () => { img.onerror = null; img.src = direct; };
      img.src = via ?? direct;
      this.tiles.set(key, img);
    }
    return t.complete && t.naturalWidth ? t : null;
  }

  /** A tile already loaded, without asking for it. */
  private cached(z: number, x: number, y: number) {
    const t = this.tiles.get(`${this.layer}/${z}/${x}/${y}`);
    return t && t.complete && t.naturalWidth ? t : null;
  }

  private queued = false;
  draw() {
    if (this.queued || this.dead) return;
    this.queued = true;
    requestAnimationFrame(() => { this.queued = false; if (!this.dead) this.paint(); });
  }
  private paint() {
    const { c, g, w, h } = this;
    if (!w || !h) return;
    const ratio = devicePixelRatio || 1;
    if (c.width !== Math.round(w * ratio) || c.height !== Math.round(h * ratio)) { c.width = Math.round(w * ratio); c.height = Math.round(h * ratio); }
    g.setTransform(ratio, 0, 0, ratio, 0, 0);
    g.fillStyle = '#20262e';
    g.fillRect(0, 0, w, h);
    // Tiles come in whole zoom levels; between them, the nearest level is drawn stretched or shrunk to fit.
    const maxZ = SOURCES[this.layer].maxZ;
    const tz = Math.max(MIN_ZOOM, Math.min(maxZ, Math.round(this.zoom)));
    const scale = 2 ** (this.zoom - tz);
    const size = TILE * scale;
    const m = project(this.center.lat, this.center.lon, tz);
    const left = m.x - w / 2 / scale, top = m.y - h / 2 / scale, n = 2 ** tz;
    g.imageSmoothingQuality = 'high';
    for (let ty = Math.floor(top / TILE); ty * TILE < top + h / scale; ty++) {
      if (ty < 0 || ty >= n) continue;
      for (let tx = Math.floor(left / TILE); tx * TILE < left + w / scale; tx++) {
        const x = Math.floor((tx * TILE - left) * scale), y = Math.floor((ty * TILE - top) * scale);
        const s = Math.ceil(size) + 1;
        const wx = ((tx % n) + n) % n;
        const t = this.tile(tz, wx, ty);
        if (t) { g.drawImage(t, x, y, s, s); continue; }
        // Not loaded yet: the quarter of the coarser tile that covers it, so there's never a blank square.
        const up = tz > MIN_ZOOM ? this.cached(tz - 1, wx >> 1, ty >> 1) : null;
        if (up) g.drawImage(up, (wx & 1) * TILE / 2, (ty & 1) * TILE / 2, TILE / 2, TILE / 2, x, y, s, s);
      }
    }
    this.overlay(this, g);
    const credit = SOURCES[this.layer].credit;
    g.font = '11px system-ui, sans-serif';
    const tw = g.measureText(credit).width;
    g.fillStyle = 'rgba(0,0,0,.6)';
    g.fillRect(w - tw - 10, h - 17, tw + 10, 17);
    g.fillStyle = '#fff';
    g.fillText(credit, w - tw - 5, h - 5);
  }
}

/** Draws a route, its numbered points, and the vehicle's fence on any map. */
export function drawPlan(m: TileMap, g: CanvasRenderingContext2D, items: { lat: number; lon: number }[], current: number, fence?: { polygon: [number, number][]; keepOut: [number, number][][] } | null, selected = -1) {
  const poly = (pts: [number, number][], stroke: string, fill: string) => {
    if (!pts.length) return;
    g.beginPath();
    pts.forEach(([la, lo], i) => { const p = m.toScreen(la, lo); i ? g.lineTo(p.x, p.y) : g.moveTo(p.x, p.y); });
    g.closePath();
    g.fillStyle = fill; g.strokeStyle = stroke; g.lineWidth = 2;
    g.fill(); g.stroke();
  };
  if (fence) {
    poly(fence.polygon, '#e5a823', 'rgba(229,168,35,.08)');
    fence.keepOut.forEach(k => poly(k, '#e5484d', 'rgba(229,72,77,.25)'));
  }
  g.lineWidth = 3; g.strokeStyle = '#fff'; g.setLineDash([8, 6]);
  g.beginPath();
  items.forEach((w, i) => { const p = m.toScreen(w.lat, w.lon); i ? g.lineTo(p.x, p.y) : g.moveTo(p.x, p.y); });
  g.stroke();
  g.setLineDash([]);
  g.font = 'bold 12px system-ui, sans-serif';
  g.textAlign = 'center'; g.textBaseline = 'middle';
  items.forEach((w, i) => {
    const p = m.toScreen(w.lat, w.lon);
    g.beginPath(); g.arc(p.x, p.y, 13, 0, 7);
    g.fillStyle = i === current ? '#3dbe6b' : i === selected ? '#58a6ff' : '#0e1116';
    g.fill(); g.lineWidth = 2; g.strokeStyle = '#fff'; g.stroke();
    g.fillStyle = '#fff'; g.fillText(String(i + 1), p.x, p.y + 1);
  });
  g.textAlign = 'start'; g.textBaseline = 'alphabetic';
}

/**
 * The vehicle where navigation thinks it is, with a circle for how unsure that is. Blue when navigation trusts it,
 * amber when it doesn't. [raw] is the GPS's own latest fix, drawn as a small grey dot so jumps are visible.
 */
export function drawVehicle(m: TileMap, g: CanvasRenderingContext2D, lat: number, lon: number, headingDeg: number, accM: number, trusted = true, raw?: { lat: number; lon: number }) {
  if (raw) {
    const r = m.toScreen(raw.lat, raw.lon);
    g.beginPath(); g.arc(r.x, r.y, 3.5, 0, 7); g.fillStyle = 'rgba(160,160,160,.9)'; g.fill();
  }
  const p = m.toScreen(lat, lon);
  g.beginPath(); g.arc(p.x, p.y, accM / m.metersPerPixel(), 0, 7);
  g.fillStyle = trusted ? 'rgba(88,166,255,.2)' : 'rgba(229,168,35,.25)'; g.fill();
  if (!trusted) { g.lineWidth = 2; g.setLineDash([6, 4]); g.strokeStyle = '#e5a823'; g.stroke(); g.setLineDash([]); }
  g.save(); g.translate(p.x, p.y); g.rotate(headingDeg * Math.PI / 180);
  g.beginPath(); g.moveTo(0, -14); g.lineTo(9, 10); g.lineTo(0, 5); g.lineTo(-9, 10); g.closePath();
  g.fillStyle = '#58a6ff'; g.strokeStyle = '#fff'; g.lineWidth = 2; g.fill(); g.stroke();
  g.restore();
}

export function drawHome(m: TileMap, g: CanvasRenderingContext2D, lat: number, lon: number) {
  const p = m.toScreen(lat, lon);
  g.beginPath(); g.arc(p.x, p.y, 9, 0, 7); g.fillStyle = '#3dbe6b'; g.fill(); g.lineWidth = 2; g.strokeStyle = '#fff'; g.stroke();
  g.font = 'bold 11px system-ui, sans-serif'; g.fillStyle = '#fff'; g.textAlign = 'center'; g.textBaseline = 'middle';
  g.fillText('H', p.x, p.y + 1);
  g.textAlign = 'start'; g.textBaseline = 'alphabetic';
}

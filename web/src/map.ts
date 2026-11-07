// A small tile map on a canvas. It's our own because the overlays and hit-testing are simple, and it
// keeps the bundle small enough for a phone to serve over a mobile link.
export type LatLon = { lat: number; lon: number };
export type Layer = 'satellite' | 'street';
const TILE = 256;
const SOURCES: Record<Layer, { url: (z: number, x: number, y: number) => string; credit: string; maxZ: number }> = {
  satellite: { url: (z, x, y) => `https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/${z}/${y}/${x}`, credit: 'Imagery Esri, Maxar, Earthstar Geographics', maxZ: 19 },
  street: { url: (z, x, y) => `https://tile.openstreetmap.org/${z}/${x}/${y}.png`, credit: '© OpenStreetMap contributors', maxZ: 19 },
};

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
  private pinch = 0;
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
      this.zoomAt(e.clientX - r.left, e.clientY - r.top, e.deltaY < 0 ? 1 : -1);
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
  private get h() { return this.c.clientHeight; }
  metersPerPixel() { return 156543.03 * Math.cos(this.center.lat * Math.PI / 180) / 2 ** this.zoom; }

  zoomAt(x: number, y: number, dir: number) {
    const before = this.fromScreen(x, y);
    this.zoom = Math.max(2, Math.min(SOURCES[this.layer].maxZ, this.zoom + dir));
    const after = this.fromScreen(x, y);
    this.center = { lat: this.center.lat + before.lat - after.lat, lon: this.center.lon + before.lon - after.lon };
    this.userMoved = true;
    this.draw();
  }
  fit(points: LatLon[]) {
    if (!points.length) return;
    const lats = points.map(p => p.lat), lons = points.map(p => p.lon);
    this.center = { lat: (Math.min(...lats) + Math.max(...lats)) / 2, lon: (Math.min(...lons) + Math.max(...lons)) / 2 };
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
    if (this.pointers.size === 2) { this.pinch = this.spread(); this.dragging = 'map'; return; }
    this.dragging = this.onPress(this.fromScreen(p.x, p.y), p.x, p.y) ? 'item' : 'map';
  }
  private spread() { const [a, b] = [...this.pointers.values()]; return Math.hypot(a.x - b.x, a.y - b.y); }
  private move(e: PointerEvent) {
    const last = this.pointers.get(e.pointerId);
    if (!last) return;
    const p = this.pos(e);
    this.pointers.set(e.pointerId, p);
    const dx = p.x - last.x, dy = p.y - last.y;
    this.moved += Math.abs(dx) + Math.abs(dy);
    if (this.pointers.size === 2) {
      const s = this.spread();
      if (Math.abs(s - this.pinch) > 50) { this.zoomAt(this.w / 2, this.h / 2, s > this.pinch ? 1 : -1); this.pinch = s; }
      return;
    }
    if (this.dragging === 'item') { this.onDrag(this.fromScreen(p.x, p.y)); return; }
    if (this.dragging === 'map') {
      const m = project(this.center.lat, this.center.lon, this.zoom);
      this.center = unproject(m.x - dx, m.y - dy, this.zoom);
      this.userMoved = true;
      this.draw();
    }
  }
  private up(e: PointerEvent) {
    const p = this.pointers.get(e.pointerId);
    this.pointers.delete(e.pointerId);
    if (!p || this.pointers.size) return;
    if (this.dragging === 'item') this.onRelease();
    else if (this.moved < 6) this.onClick(this.fromScreen(p.x, p.y), p.x, p.y);
    this.dragging = null;
  }

  private tile(z: number, x: number, y: number) {
    const key = `${this.layer}/${z}/${x}/${y}`;
    let t = this.tiles.get(key);
    if (!t) {
      if (this.tiles.size > 400) this.tiles.clear();
      t = new Image();
      // The relay's page hides its address from others, but OSM's terms need a referrer on requests.
      t.referrerPolicy = 'strict-origin-when-cross-origin';
      t.onload = () => this.draw();
      t.src = SOURCES[this.layer].url(z, x, y);
      this.tiles.set(key, t);
    }
    return t.complete && t.naturalWidth ? t : null;
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
    const m = project(this.center.lat, this.center.lon, this.zoom);
    const left = m.x - w / 2, top = m.y - h / 2, n = 2 ** this.zoom;
    for (let ty = Math.floor(top / TILE); ty * TILE < top + h; ty++) {
      if (ty < 0 || ty >= n) continue;
      for (let tx = Math.floor(left / TILE); tx * TILE < left + w; tx++) {
        const t = this.tile(this.zoom, ((tx % n) + n) % n, ty);
        if (t) g.drawImage(t, Math.round(tx * TILE - left), Math.round(ty * TILE - top), TILE, TILE);
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

export function drawVehicle(m: TileMap, g: CanvasRenderingContext2D, lat: number, lon: number, headingDeg: number, accM: number) {
  const p = m.toScreen(lat, lon);
  g.beginPath(); g.arc(p.x, p.y, accM / m.metersPerPixel(), 0, 7);
  g.fillStyle = 'rgba(88,166,255,.2)'; g.fill();
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

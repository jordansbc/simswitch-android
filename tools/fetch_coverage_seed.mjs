/**
 * One-time, bounded pull of CoverageMap's crowdsourced speed squares for your own area,
 * converted into the geohash7 seed the app's learned store uses.
 *
 * Scope is deliberately limited to a box around your own metro — the places you actually
 * drive. This is not a national harvest: it is the same handful of squares you can read off the
 * map yourself, written down so the app can use them for places you haven't been yet.
 *
 * The result is a *weak* prior. See docs/coverage-seeding.md — it lands with a sample count of 2
 * so that the third real measurement at a place makes it irrelevant.
 *
 *   node tools/fetch_coverage_seed.mjs
 */

import { writeFileSync, mkdirSync } from 'node:fs'
import { dirname } from 'node:path'

/**
 * SET THIS TO YOUR OWN AREA before running. A ~0.45° x 0.60° box is roughly one metro and pulls
 * 56 z12 tiles per carrier, which takes about a minute.
 *
 * Keep it bounded to where you actually drive. This deliberately is not a national harvest:
 * CoverageMap sells bulk access to the crowdsourced layer, and a metro-sized pull for personal
 * use is a different thing from mirroring their dataset. The generated asset is gitignored for
 * the same reason — regenerate it locally rather than redistributing it.
 */
const BBOX = { south: 0.00, north: 0.00, west: 0.00, east: 0.00 }
const ZOOM = 12
const PROVIDERS = { ATT: 'SIM A', TMO: 'SIM B' } // CoverageMap code -> your carrier label
const OUT = 'app/src/main/assets/coverage_seed.json'

if (BBOX.south === 0 && BBOX.north === 0) {
  console.error('Set BBOX at the top of this file to your own area first.')
  process.exit(1)
}

// ---------------------------------------------------------------- protobuf / MVT

class Reader {
  constructor(buf) { this.b = buf; this.p = 0 }
  varint() {
    let result = 0, shift = 0, byte
    do { byte = this.b[this.p++]; result += (byte & 0x7f) * 2 ** shift; shift += 7 } while (byte >= 0x80)
    return result
  }
  bytes() { const len = this.varint(); const v = this.b.subarray(this.p, this.p + len); this.p += len; return v }
  string() { return Buffer.from(this.bytes()).toString('utf8') }
  double() { const v = Buffer.from(this.b.buffer, this.b.byteOffset + this.p, 8).readDoubleLE(0); this.p += 8; return v }
  float() { const v = Buffer.from(this.b.buffer, this.b.byteOffset + this.p, 4).readFloatLE(0); this.p += 4; return v }
  skip(wire) {
    if (wire === 0) this.varint()
    else if (wire === 1) this.p += 8
    else if (wire === 2) this.p += this.varint()
    else if (wire === 5) this.p += 4
    else throw new Error('bad wire type ' + wire)
  }
  get done() { return this.p >= this.b.length }
}

function parseValue(buf) {
  const r = new Reader(buf)
  while (!r.done) {
    const tag = r.varint(), field = tag >> 3, wire = tag & 7
    if (field === 1) return r.string()
    if (field === 2) return r.float()
    if (field === 3) return r.double()
    if (field === 4 || field === 5) return r.varint()
    if (field === 6) { const v = r.varint(); return (v >> 1) ^ -(v & 1) }
    if (field === 7) return r.varint() !== 0
    r.skip(wire)
  }
  return null
}

/** Returns features as { props, x, y } where x/y are the first MoveTo in tile-extent space. */
function parseLayer(buf) {
  const r = new Reader(buf)
  const keys = [], values = [], rawFeatures = []
  let extent = 4096, name = ''

  while (!r.done) {
    const tag = r.varint(), field = tag >> 3, wire = tag & 7
    if (field === 1) name = r.string()
    else if (field === 2) rawFeatures.push(r.bytes())
    else if (field === 3) keys.push(r.string())
    else if (field === 4) values.push(parseValue(r.bytes()))
    else if (field === 5) extent = r.varint()
    else r.skip(wire)
  }

  const features = rawFeatures.map(fb => {
    const fr = new Reader(fb)
    const props = {}
    let px = null, py = null

    while (!fr.done) {
      const tag = fr.varint(), field = tag >> 3, wire = tag & 7
      if (field === 2) {
        const tags = fr.bytes(), tr = new Reader(tags)
        while (!tr.done) { const k = tr.varint(); const v = tr.varint(); props[keys[k]] = values[v] }
      } else if (field === 4) {
        const geo = fr.bytes(), gr = new Reader(geo)
        // Only the first MoveTo is needed — one representative point per square.
        const cmd = gr.varint()
        if ((cmd & 0x7) === 1) {
          const zx = gr.varint(), zy = gr.varint()
          px = (zx >> 1) ^ -(zx & 1)
          py = (zy >> 1) ^ -(zy & 1)
        }
      } else fr.skip(wire)
    }
    return { props, px, py }
  })

  return { name, extent, features }
}

function parseTile(buf) {
  const r = new Reader(buf), layers = []
  while (!r.done) {
    const tag = r.varint(), field = tag >> 3, wire = tag & 7
    if (field === 3) layers.push(parseLayer(r.bytes()))
    else r.skip(wire)
  }
  return layers
}

// ---------------------------------------------------------------- geo

const tileXY = (lat, lon, z) => {
  const n = 2 ** z
  const r = (lat * Math.PI) / 180
  return {
    x: Math.floor(((lon + 180) / 360) * n),
    y: Math.floor(((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2) * n),
  }
}

/** tile-local extent coords -> lat/lon */
function toLatLon(tx, ty, z, px, py, extent) {
  const n = 2 ** z
  const lon = ((tx + px / extent) / n) * 360 - 180
  const ny = Math.PI - 2 * Math.PI * (ty + py / extent) / n
  const lat = (180 / Math.PI) * Math.atan(0.5 * (Math.exp(ny) - Math.exp(-ny)))
  return { lat, lon }
}

const B32 = '0123456789bcdefghjkmnpqrstuvwxyz'
function geohash(lat, lon, precision = 7) {
  let laMin = -90, laMax = 90, loMin = -180, loMax = 180
  let hash = '', bit = 0, ch = 0, even = true
  while (hash.length < precision) {
    if (even) { const m = (loMin + loMax) / 2; if (lon >= m) { ch = ch * 2 + 1; loMin = m } else { ch *= 2; loMax = m } }
    else { const m = (laMin + laMax) / 2; if (lat >= m) { ch = ch * 2 + 1; laMin = m } else { ch *= 2; laMax = m } }
    even = !even
    if (++bit === 5) { hash += B32[ch]; bit = 0; ch = 0 }
  }
  return hash
}

// ---------------------------------------------------------------- fetch

const HEADERS = {
  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148 Safari/537.36',
  Referer: 'https://map.coveragemap.com/',
  Accept: '*/*',
}

async function fetchTile(provider, z, x, y) {
  const url = `https://map.coveragemap.com/api/v1/speedTests/squares/tiles` +
    `?z=${z}&x=${x}&y=${y}&country=US&provider=${provider}&field=avg&userOnly=false`
  const res = await fetch(url, { headers: HEADERS })
  if (!res.ok) return null
  return new Uint8Array(await res.arrayBuffer())
}

// ---------------------------------------------------------------- main

// Tile ranges covering the bbox. y increases southward in slippy-map coords, so north maps to yMin.
const nw = tileXY(BBOX.north, BBOX.west, ZOOM)
const se = tileXY(BBOX.south, BBOX.east, ZOOM)
const xMin = Math.min(nw.x, se.x), xMax = Math.max(nw.x, se.x)
const yMin = Math.min(nw.y, se.y), yMax = Math.max(nw.y, se.y)
const tileCount = (xMax - xMin + 1) * (yMax - yMin + 1)
console.log(`tile grid       : x ${xMin}..${xMax}, y ${yMin}..${yMax} = ${tileCount} tiles per carrier`)

const cells = new Map() // geohash -> { [carrier]: {sum, n} }
const propKeys = new Set()
const rawDownloads = []
const rawLatencies = []
let tilesFetched = 0

for (const [code, carrier] of Object.entries(PROVIDERS)) {
  for (let x = xMin; x <= xMax; x++) {
    for (let y = yMin; y <= yMax; y++) {
      const buf = await fetchTile(code, ZOOM, x, y)
      if (!buf) { console.error(`  miss ${code} ${x},${y}`); continue }
      tilesFetched++

      for (const layer of parseTile(buf)) {
        for (const f of layer.features) {
          if (f.px == null) continue
          Object.keys(f.props).forEach(k => propKeys.add(k))

          const value = f.props.avg_download
          if (typeof value !== 'number' || !isFinite(value)) continue
          rawDownloads.push(value)
          if (typeof f.props.avg_latency === 'number') rawLatencies.push(f.props.avg_latency)

          const { lat, lon } = toLatLon(x, y, ZOOM, f.px, f.py, layer.extent)
          const key = geohash(lat, lon)
          if (!cells.has(key)) cells.set(key, {})
          const cell = cells.get(key)
          cell[carrier] ??= { sum: 0, n: 0 }
          cell[carrier].sum += value
          cell[carrier].n++
        }
      }
      await new Promise(r => setTimeout(r, 250)) // be polite
    }
  }
}

const seed = {}
let both = 0
for (const [key, carriers] of cells) {
  const entry = {}
  for (const [carrier, agg] of Object.entries(carriers)) {
    entry[carrier] = Math.round((agg.sum / agg.n) * 1000) // Mbps -> kbps, matching kbps_ewma
  }
  if (Object.keys(entry).length > 1) both++
  seed[key] = entry
}

mkdirSync(dirname(OUT), { recursive: true })
writeFileSync(OUT, JSON.stringify({
  source: 'coveragemap.com crowdsourced speed tests',
  note: 'Weak prior only — enters the store with samples=2 and is superseded by real measurements.',
  area: `bbox ${BBOX.south},${BBOX.west} -> ${BBOX.north},${BBOX.east}`,
  bbox: BBOX,
  zoom: ZOOM,
  generated: new Date().toISOString().slice(0, 10),
  cells: seed,
}))

// Units sanity check. Mobile download should land in the tens of Mbps; if the p50 comes back in
// the thousands the field is kbps, and the conversion below is wrong.
const pct = (arr, p) => { const a = [...arr].sort((x, y) => x - y); return a[Math.floor(a.length * p)] }
console.log(`raw avg_download: min=${pct(rawDownloads, 0)} p50=${pct(rawDownloads, 0.5)} p90=${pct(rawDownloads, 0.9)} max=${pct(rawDownloads, 0.999)}`)
console.log(`raw avg_latency : p50=${pct(rawLatencies, 0.5)} (ms if this looks like 20-80)`)
console.log(`tiles fetched   : ${tilesFetched}`)
console.log(`property keys   : ${[...propKeys].join(', ') || '(none)'}`)
console.log(`geohash7 cells  : ${cells.size}`)
console.log(`cells w/ both   : ${both}`)
console.log(`written         : ${OUT}`)

const sample = Object.entries(seed).filter(([, v]) => Object.keys(v).length > 1).slice(0, 8)
for (const [k, v] of sample) {
  const dark = v['SIM A'], us = v['the MVNO']
  const winner = dark != null && us != null ? (dark > us ? 'SIM A' : 'the MVNO') : '—'
  console.log(`  ${k}  SIM A=${dark ?? '-'} kbps  USMobile=${us ?? '-'} kbps  -> ${winner}`)
}

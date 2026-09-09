# Seeding the learned store with public coverage data

> **Implemented 2026-08-30** using CoverageMap's crowdsourced speed squares (not FCC — see below).
> Puller: `tools/fetch_coverage_seed.mjs`. Asset: `app/src/main/assets/coverage_seed.json` (223 KB,
> **6,376 geohash7 cells, 1,496 with both carriers**). Loader: `data/CoverageSeed.kt`.
>
> Area: **one metro**, set via `BBOX` in the puller — a ~0.45° × 0.60° box is 56 z12 tiles per
> carrier. Set it to wherever you actually drive.
>
> Notable: across the metro the seed favours **the MVNO/T-Mobile in 833 cells vs SIM A in 542**
> — the opposite of the picture at the owner's house, which is precisely the case for the app existing.
> (Your own numbers will differ; that asymmetry is the whole point.)
>
> Bump `CoverageSeed.VERSION` whenever the asset is regenerated, or the loader will skip the import.

## What was actually used, and why

CoverageMap's map is backed by a public endpoint the site itself calls:

```
GET https://map.coveragemap.com/api/v1/speedTests/squares/tiles
    ?z={z}&x={x}&y={y}&country=US&provider=ATT|TMO&field=avg&userOnly=false
```

Returns Mapbox Vector Tiles whose features carry `avg_download`, `med_download`, `best_download`,
matching upload fields, and `avg_latency`. Providers come from `/api/v1/providers?country=US`
(AT&T = `ATT`, T-Mobile = `TMO`). `field=avg` is the accepted value — `field` is the *aggregate*,
not a composite like `avg_download`.

**Scope is deliberately bounded** to a 3×3 tile box at z12 around your home area — the places you
actually drives, i.e. the same squares you can read off the map yourself. This is not a national
harvest; CoverageMap sells bulk access to this dataset, and taking it wholesale would be taking
their product.

### Units — checked, not assumed

`avg_download` p50 ≈ **202**, p90 ≈ 641, max ≈ 1426, and `avg_latency` p50 ≈ **43.9**. Latency in
that range is unambiguously milliseconds, which fixes the scale: downloads are **Mbps**.

These are *speed tests* — run deliberately, usually stationary on good signal — so they are
best-case, not typical. That matters, and drove the storage decision below.

### The bug this nearly caused

Seed figures are speed-test scale (~200 Mbps median). Our own `kbps_ewma` is passive everyday
traffic, orders of magnitude smaller. Writing the seed into `kbps_ewma` would have meant a carrier
that had **never been measured** beating one that had, purely on units.

So the seed lives in its own `seed_score` column, normalised 0..1 against `EXCELLENT_MBPS = 400`,
and is never compared against measured throughput. Its weight in `ScoreEngine` is
`SEED_WEIGHT * (1 - confidence)` — capped at 0.35 where nothing is known, decaying to **zero** once
a place has real samples.



The learned place store (Phase 2) starts empty, so for the first few days SimSwitch has no opinion
anywhere it hasn't been. Public coverage data can give it a starting guess — provided that guess is
held loosely enough that it never overrides a real measurement.

## Source: FCC Broadband Data Collection (primary)

Carriers are required to file mobile coverage with the FCC twice a year. The National Broadband Map
exposes it as **per-provider, per-technology, per-state** downloads in ESRI Shapefile or GeoPackage,
including both a hex-binned representation and the raw propagation-modeled polygons.

What we want: **AT&T Mobility** and **T-Mobile**, technology codes **400 (4G LTE)** and **500 (5G-NR)**,
your **state**. 5G is filed at two speed tiers, which is what makes the data useful as a *ranking*
rather than a binary "covered / not covered".

Pipeline (offline, run on the PC — never on the phone):

1. Download the four files (2 carriers × 2 technologies) for your state.
2. Clip to a bounding box around your metro. The statewide files are large and 99% of it
   is irrelevant.
3. Rasterize to the same **geohash7** key the learned store uses (~150m cells), so a seed lookup is
   a dictionary hit with no geometry code on the device.
4. Emit a compact asset: `geohash7 -> (attTier, tmobileTier)` where tier is an ordinal derived from
   technology + filed speed threshold.

Ship that as an app asset. No network access at runtime, no API key on the device.

### Why it is only a weak prior

This is **carrier self-reported, propagation-modeled** coverage — not measurement. It is well known
to be optimistic, and it cannot see the thing that actually decides speed in practice: congestion.
A tower you share with a high school at 3pm models identically to an empty one.

So the seed enters the store as a real row with a **confidence equal to ~2 samples**. Concretely:

- Place never visited → seed decides, weakly. Better than a coin flip.
- 1–2 real samples → seed and reality both matter.
- 3+ real samples → reality dominates; the seed is numerically irrelevant.
- The seed is never re-applied to a place that already has measurements.

That ordering is the whole point. If the FCC data claims T-Mobile wins at your house and three
measured samples say SIM A is twice as fast, SIM A wins and stays winning.

## Source: OpenCelliD (secondary, optional)

Free per-country CSV with an API key (downloads limited to the last 18 months; the API has no such
window). Gives tower positions keyed by MCC/MNC, so we can derive **distance to the nearest AT&T vs
T-Mobile tower** as an additional weak feature — useful mostly as a tiebreaker where FCC polygons
say "both covered".

US MCC is 310. Relevant MNCs: AT&T 310-410 / 310-280, T-Mobile 310-260. Filter to your metro bbox
before importing; the full US extract is multiple GB.

## Rejected: Ookla Open Data

Free, quarterly, ~600m tiles with real measured download/upload/latency — but **aggregated across
all carriers**. It can say "this tile is fast in general", which does not answer the only question
SimSwitch asks: *which of my two SIMs is better here*. A tile dominated by Verizon users tells us
nothing about SIM A vs SIM B.

## Implementation note

The seed is loaded once on first run and written into the same Room table as measured data, with a
`source` column (`SEED` vs `MEASURED`) and the low sample count described above. Keeping it in one
table means the scoring path has no special case — a seeded place and a measured place are read
identically, and the confidence weighting does the rest.

Sources:
- <https://www.fcc.gov/BroadbandData/MobileMaps/mobile-map>
- <https://help.bdc.fcc.gov/hc/en-us/articles/43909220634651-How-to-Download-Mobile-Broadband-Coverage-Data-from-the-FCC-s-National-Broadband-Map-Step-by-Step-Instructions>
- <https://www.opencellid.org/downloads.php>

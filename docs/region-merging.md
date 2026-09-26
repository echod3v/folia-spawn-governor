# Why spread-out players still share one Folia region

Folia runs each *region* (a connected group of loaded chunks) on its own thread. The intuition is that
players far apart get separate threads. The measurements below show that on Folia 26.2 with view distance 10,
players roughly **1,000–1,100 blocks** apart are still chained into a single region, so one CPU core ends up
ticking every player in a whole neighbourhood.

## Symptom

60 lightweight protocol bots were placed in fixed layouts on a production-sized world (view 10, simulation 6,
vanilla mob caps, 2×Xeon E5-2696 v4 at 2.8 GHz). Folia's `/profiler` was used to read per-region numbers.

| Layout | Regions | Players in the worst region | Worst region tick | TPS |
|---|---|---|---|---|
| 60 players packed in one spot | 1 | 60 | 15.7 ms | 20.0 |
| 10×6 grid, 400 blocks apart | 6 | 55 | 247.5 ms | 4.0 |
| 10×6 grid, 650 blocks apart | 6 | 55 | 201.6 ms | 5.0 |
| Uniform random within ±4500 | 22 | 18 | 87.1 ms | 11.5 |
| Uniform random within ±10000 | 43 | 11 | 18.5 ms | 20.0 |

The single regions holding one isolated player each ran at ~3–5 ms. The same players, chained, cost
~4.5 ms *each* on one thread. Only about 1.9 CPU cores were busy on an 88-thread machine.

The cost is also not "per player" but "per player's own surroundings". Packed players share one spawn
area (260 mobs for 60 players); spread players each bring their own mob cap (≈65 hostile mobs per player).

## Cause

1. **Chunk holders, not visible chunks, define a region.** Folia registers every chunk *holder* with the
   regioniser. With Moonrise, the ticket level spreads outwards from the player ticket (level 33, the loaded
   view area) up to level 44, so each player owns about 12 extra rings of `INACCESSIBLE` holders.
   A `paper debug chunks` dump with 40 bots held **74,410 holders**, and **51,242** of them were
   `INACCESSIBLE`.
2. **Empty neighbour sections.** The regioniser in `ServerLevel` is built with
   `emptySectionCreateRadius = 8 >> gridExponent` sections (8 chunks) and `regionSectionMergeRadius = 1`.
   Around every non-empty section it creates empty sections, and any new section within that search range
   merges the regions (`ThreadedRegionizer#addChunk`).
3. **Merging is transitive.** A–B at 900 blocks and B–C at 900 blocks puts A, B and C in one region.
   Splitting only happens lazily, when enough sections die.

Per player, a region spans about (view distance + 1 + ~12 propagation rings + 8 empty) ≈ 30 chunks in every
direction. Two players' footprints touch well before their visible areas do.

[`bench/analysis/region_components.py`](../bench/analysis/region_components.py) repeats this connectivity
rule on a chunk dump. For the 40-bot dump it predicts components of `[19, 5, 3, 2, 2, 1, ...]` players,
which matched the profiler (a 19-player region; the others small).

## Consequences

- **Distance settings are a weak lever.** Simulation distance 6 → 5 → 4 reduced the worst region by only
  10–25%. The per-player mob cap stays the same, so the same mobs spawn in a smaller area. View distance
  shrinks the footprint only slightly, because the fixed propagation and empty rings dominate.
- **Spawn spread is a strong lever.** A modelled 60-player random spawn gives a median largest cluster of 12
  at ±5000 but 4 at ±10000 (Chebyshev merge distance ≈ 960 blocks, 300 trials). Measured: ±10000 kept every
  region at 20 TPS with vanilla settings.
- **Crowded neighbourhoods remain a single-thread problem.** That is what the
  [governor](controller-design.md) addresses.

## Code references (Folia 26.2)

- `net/minecraft/server/level/ServerLevel.java`: regioniser construction (`minSectionRecalcCount`,
  `maxDeadRegionPercent = 1/6`, `emptySectionCreateRadius = 8 >> shift`, merge radius 1).
- `io/papermc/paper/threadedregions/ThreadedRegionizer.java`
  - `addChunk`: creates neighbour sections and merges nearby regions.
  - Region recalculation: BFS split, only after dead sections exceed 1/6.

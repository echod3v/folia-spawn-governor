# Governor design and how it got there

Goal set for the server: keep **≥ 18 TPS in the worst case** with **simulation distance 6 and view
distance 10 unchanged**, and keep vanilla mechanics wherever possible.

## Where the time goes

In the overloaded 55-player region, about two thirds of the tick was entity ticking (zombies, skeletons,
creepers, bats, fish), 12% was the natural spawner, 7% entity tracking and 4% random ticks. Almost all of it
scales with the number of naturally spawned mobs, which vanilla ties to the number of spread-out players.

## Mechanism

Paper fires `PlayerNaturallySpawnCreaturesEvent` for every player of a region before each spawn pass.
When it is cancelled, `ChunkMap#isChunkNearPlayer` ignores that player, so the chunks around them are left
out of the spawn list for that tick. The spawn attempts themselves are skipped, not just their results.

Cancelling the event for *every* player of a region in a given tick is therefore a per-region,
per-tick equivalent of Bukkit's `ticks-per.*-spawns`. The governor decides once per region tick from
Folia's own 5-second tick report (`TickRegionScheduler.RegionScheduleHandle#getTickReport5s`).

Existing mobs are untouched and follow vanilla despawn rules, so density falls only as far as needed.

## Iterations (all measured with the same 60-bot bench)

| Version | Change | Worst layout, just after relocation | Settled |
|---|---|---|---|
| none | vanilla | 247.5 ms / 4.0 TPS | — |
| v1 | throttle above 45 ms, recover below 35 ms, multiplicative | 130 ms / 7.7 | 56.9 ms / 17.4 |
| v2 | + last-resort despawn assist, + throttle follows players across region merges, + NUMA/THP | 44.0 ms / 19.2 | 46.5–52.7 ms / 18.3 |
| v3 | fix: assist never fired; stricter 40/30 band | 35.7 ms / 20.0 | 33.7–35.9 ms / 20.0 |
| v4 | target tracking (42 ms, 4 ms hold band) instead of on/off | 43.9 ms / 19.4 | 42.8 ms / 19.9 |

What each step taught:

- **v1: mob density lags the throttle.** Spawning was cut to 0% within seconds, yet the tick time took
  about 7 minutes to fall from 236 ms to 44 ms, because only vanilla despawning removed the mobs.
- **v1: new regions reset the state.** Relocating players created new regions that started at 100%
  spawning and refilled the mob cap. v2 lets a new region inherit the strictest throttle of the players
  entering it during its first second.
- **v2: the despawn assist never ran.** A multiplicative cut (`f *= 0.25…0.95`) approaches zero but never
  reaches it, and the assist was gated on `f == 0`. v3 snaps to the floor below 2%, and the unit test
  `overloadCutsProportionallyAndReachesTheFloor` guards it.
- **v3: on/off control over-suppresses.** A 50-player region settled at 33 ms with spawning still at 0%,
  because it never fell below the 30 ms recovery threshold. That removed more mobs than the TPS goal needed.
  v4 tracks a target and hands spawning back once the region is comfortably below it.

## The despawn assist (last resort)

This step runs only while spawning is already at 0% and the region is still above 46 ms. Mobs that vanilla
itself may despawn are removed sooner, without drops:
- hostiles, bats, fish and squid with `removeWhenFarAway`,
- more than 32 blocks from every player,
- with no name tag, leash or vehicle,
- never animals, villagers, bosses, shulkers, wardens, raid members or patrol leaders.

Each sweep covers at most three players' surroundings per tick, so the sweep itself cannot become a spike.

## Non-gameplay tuning measured alongside

The v2 to v4 runs also used two settings that change no game behaviour:
- **NUMA pinning:** the JVM is bound to one socket and its local memory
  (`numactl --cpunodebind=1 --membind=1`, `kernel.numa_balancing=0`).
- **Transparent huge pages for the ZGC heap:** `-XX:+UseTransparentHugePages` with shmem THP set to `advise`.

The runs were not designed to separate their share from the governor's.

## What players notice

Nothing, as long as a region stays at or below 42 ms. Without the governor the following gets slower,
depending on density:

| Spread players sharing a region (≈1 km) | Hostile density (estimate) | Without the governor |
|---|---|---|
| up to ~9 | 100% | 20 TPS |
| ~12 | ~70% | ~17 TPS |
| ~15 | ~50% | ~15 TPS |
| ~20 | ~30% | ~13 TPS |
| 30+ | ≤10% | 4–8 TPS |

Only the 50-player row was measured directly (hostiles per player: 65 → 2–5). The middle rows are estimates
from the measured per-player costs (≈4.3 ms with vanilla density, ≈0.95 ms with almost none).

Side effects appear only in a governed region:
- mob farms there run slower,
- lightning during thunderstorms is rarer (skipped spawn ticks also skip `tickThunder`),
- local difficulty rises more slowly.

Animals, villagers, breeding, iron golems, raids, patrols, phantoms, spawners and trial spawners are never
affected.

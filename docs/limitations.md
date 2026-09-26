# Limitations and open questions

- **Bots, not people.** The load generator is a lightweight protocol client. It does not move naturally,
  react to knockback, build farms or redstone, or render anything. Real servers accumulate player-made
  entity loads that this bench never had.
- **Animals and villagers set a floor.** Vanilla never despawns them, and the governor deliberately leaves
  them alone. In a wide area full of villages and pastures with ~50 spread players, the worst region stayed
  around 60–70 ms (≈14 TPS) even with hostile mobs cleared. An experimental "pause far animal AI" stage was
  prototyped but never validated, so it is not part of this repository.
- **Transients after sudden merges.** Teleporting 50+ players into one region at once is still slow for a
  short while. In the bench, relocating 53 bots made the server load ~23,000 chunks, including saved mobs,
  at the same time. A few bots stalled for over 30 s waiting for destination chunks and timed out. Avoid
  mass `/tp @a` style events on busy servers.
- **Folia internals.** `RegionClock` reads non-API classes (`io.papermc.paper.threadedregions`) through
  reflection. A Folia update may rename them. The plugin then fails to enable instead of misbehaving.
- **Single machine, single world.** All numbers come from one 2×Xeon E5-2696 v4 host (2.8 GHz all-core, no
  higher single-core turbo observed) and one pre-generated world. Faster cores move every threshold.
- **The middle of the impact table is modelled.** The 12–20-player estimates were not measured, because
  the bench runs for those steps were cut short by an external login throttle on the generator's IP.
- **The NUMA/THP share is unknown.** The runs with the pinned, huge-page JVM were not designed to separate
  its share from the governor's.

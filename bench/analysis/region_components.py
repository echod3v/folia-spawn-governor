#!/usr/bin/env python3
"""Reconstruct Folia's region layout from a `paper debug chunks` dump.

usage: region_components.py <chunks-dump.txt> [--world minecraft:overworld] [--grid-exponent 2]

Folia's regioniser tracks every chunk *holder*, not only loaded or ticking chunks. With Moonrise the
ticket level propagates outwards from player tickets (level 33) up to level 44, so each player owns a ring
of INACCESSIBLE holders around the visible area. On top of that the regioniser creates empty neighbour
sections within `emptySectionCreateRadius` (8 chunks) and merges regions whose sections are within the
merge radius. This script repeats that connectivity rule on the dumped holders and prints how many
players end up sharing each connected component (= each region, up to lazy splitting).

Constants follow Folia 26.2 `ServerLevel` (regioniser construction):
    sectionShift = grid-exponent, emptySectionCreateRadius = 8 >> grid-exponent,
    regionSectionMergeRadius = 1, recalculation radius = max(merge, create).
"""
import argparse
import collections
import json


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('dump')
    ap.add_argument('--world', default='minecraft:overworld')
    ap.add_argument('--grid-exponent', type=int, default=2)
    args = ap.parse_args()

    data = json.load(open(args.dump))
    world = next(w for w in data['worlds'] if w['name'] == args.world)
    holders = world['chunk-holder-manager']['chunkholders']
    shift = args.grid_exponent
    create = max(1, 8 >> shift)
    radius = max(1, create)

    status = collections.Counter(h['current_chunk_full_status'] for h in holders)
    sections = {(h['chunkX'] >> shift, h['chunkZ'] >> shift) for h in holders}

    parent = {s: s for s in sections}

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    # two non-empty sections join when their empty-neighbour rings touch: distance <= 2 * radius sections
    span = 2 * radius
    for (x, z) in sections:
        for dx in range(-span, span + 1):
            for dz in range(-span, span + 1):
                n = (x + dx, z + dz)
                if n in parent:
                    parent[find((x, z))] = find(n)

    players = [p for p in data['all-players'] if p['world-name'] == args.world]
    per_component = collections.Counter()
    for p in players:
        key = (int(p['x']) >> 4 >> shift, int(p['z']) >> 4 >> shift)
        if key in parent:
            per_component[find(key)] += 1

    print(f"players={len(players)} chunk_holders={len(holders)} by_status={dict(status)}")
    print(f"sections={len(sections)} components={len(set(find(s) for s in sections))}")
    print('players per component:', sorted(per_component.values(), reverse=True))


if __name__ == '__main__':
    main()

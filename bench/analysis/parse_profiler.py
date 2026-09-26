#!/usr/bin/env python3
"""Summarise a Folia `/profiler` output folder (one region-<id>.txt per region).

usage: parse_profiler.py <profiler-folder> [--min-ticks 200] [--json]

For every region file it reports tick statistics, the average number of ticked players (the
`Entity Tick: minecraft:player` avg counter), the share of the main work categories, and entity
counts per type. The "worst" region is the stable region (enough ticks) with the highest average MSPT.
"""
import argparse
import json
import re
import sys
from pathlib import Path

STATS = r'^(Total Ticks|Average TPS|Median TPS|Min TPS|Average MSPT|Median MSPT|Max MSPT): ([\d.,]+)'
WORK = ['Entity Tick', 'Entity Tracker Tick', 'Spawn Entities', 'Collect Spawning Chunks', 'Random Tick',
        'Connection Tick', 'Tile Entities', 'Player Packet Processing']
ENTITY = r'Entity Tick: ResourceKey\[minecraft:entity_type / minecraft:([a-z_]+)\] ([\d.]+)% total.*?avg ([\d.,]+)'


def number(text):
    return float(text.replace(',', ''))


def parse_region(path):
    text = path.read_text(errors='replace')
    row = {k: number(v) for k, v in re.findall(STATS, text, re.M)}
    if not row.get('Total Ticks'):
        return None
    entities = {}
    for kind, _share, avg in re.findall(ENTITY, text):
        # active and inactive ticks of the same type are listed separately; add them up
        entities[kind] = entities.get(kind, 0.0) + number(avg)
    row['region'] = path.stem
    row['players'] = entities.pop('player', 0.0)
    row['work_percent'] = {k: float(m[1]) for k in WORK
                           if (m := re.search(r'(?:─| )' + re.escape(k) + r' ([\d.]+)% total', text))}
    row['entities_per_tick'] = dict(sorted(entities.items(), key=lambda kv: -kv[1]))
    for key in ('Random Chunk Tick Count', 'Entity Spawn Chunk Count'):
        if (m := re.search(r'#' + key + r' avg ([\d.,]+)', text)):
            row[key.lower().replace(' ', '_')] = number(m[1])
    return row


def summarise(folder, min_ticks):
    regions = [r for p in sorted(Path(folder).glob('region-*.txt')) if (r := parse_region(p))]
    stable = [r for r in regions if r['Total Ticks'] >= min_ticks]
    worst = max(stable, key=lambda r: r['Average MSPT'], default=None)
    return {
        'regions': len(regions),
        'stable_regions': len(stable),
        'worst_region_mspt': worst and worst['Average MSPT'],
        'min_region_tps': min((r['Average TPS'] for r in stable), default=None),
        'max_tick_ms': max((r['Max MSPT'] for r in regions), default=None),
        'worst_region_players': worst and worst['players'],
        'worst_region': worst,
        'all_regions': regions,
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('folder')
    ap.add_argument('--min-ticks', type=int, default=200, help='regions with fewer ticks are reported but not ranked')
    ap.add_argument('--json', action='store_true', help='print the full JSON summary')
    args = ap.parse_args()
    s = summarise(args.folder, args.min_ticks)
    if args.json:
        json.dump(s, sys.stdout, indent=2)
        return
    print(f"regions={s['regions']} stable={s['stable_regions']} worst={s['worst_region_mspt']}ms "
          f"minTPS={s['min_region_tps']} maxTick={s['max_tick_ms']}ms players_in_worst={s['worst_region_players']}")
    for r in sorted(s['all_regions'], key=lambda r: -r['Average MSPT']):
        top = ', '.join(f"{k} {v:.0f}" for k, v in list(r['entities_per_tick'].items())[:5])
        print(f"  {r['region']:>14} ticks={r['Total Ticks']:.0f} mspt={r['Average MSPT']:.1f} tps={r['Average TPS']:.2f} "
              f"players={r['players']:.1f} | {top}")


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Render the headline charts of results/measurements.csv as dependency-free SVG.

usage: plot.py <measurements.csv> <output-dir>
"""
import csv
import sys
from html import escape
from pathlib import Path

INK, MUTED, GRID, BG = '#1f2328', '#59636e', '#d1d9e0', '#ffffff'
BAR, GOOD, BUDGET = '#8c959f', '#1a7f37', '#cf222e'


def load(path):
    with open(path, newline='') as f:
        return list(csv.DictReader(f))


def pick(rows, config, layout, bots='60'):
    """Latest matching row (later runs of the same configuration supersede earlier ones)."""
    match = [r for r in rows if r['config'] == config and r['layout'] == layout and r['bots'] == bots]
    if not match:
        raise KeyError((config, layout, bots))
    return float(match[-1]['worst_region_mspt']), float(match[-1]['min_region_tps'])


def bar_chart(title, subtitle, bars, out, budget=50.0, width=760):
    """bars: list of (label, mspt, tps, highlight)."""
    left, right, top, row_h = 250, 90, 92, 34
    height = top + row_h * len(bars) + 50
    scale_max = max(260.0, max(b[1] for b in bars) * 1.05)
    x = lambda v: left + (width - left - right) * v / scale_max
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}" '
             f'font-family="-apple-system,Segoe UI,Helvetica,Arial,sans-serif">',
             f'<rect width="100%" height="100%" fill="{BG}"/>',
             f'<text x="20" y="28" font-size="17" font-weight="600" fill="{INK}">{escape(title)}</text>',
             f'<text x="20" y="50" font-size="12.5" fill="{MUTED}">{escape(subtitle)}</text>']
    for tick in range(0, int(scale_max) + 1, 50):
        parts.append(f'<line x1="{x(tick):.1f}" y1="{top - 6}" x2="{x(tick):.1f}" y2="{height - 38}" stroke="{GRID}" stroke-width="1"/>')
        parts.append(f'<text x="{x(tick):.1f}" y="{height - 22}" font-size="11" text-anchor="middle" fill="{MUTED}">{tick} ms</text>')
    parts.append(f'<line x1="{x(budget):.1f}" y1="{top - 8}" x2="{x(budget):.1f}" y2="{height - 38}" stroke="{BUDGET}" stroke-width="2" stroke-dasharray="5 4"/>')
    parts.append(f'<text x="{x(budget) + 6:.1f}" y="{top - 12}" font-size="11.5" fill="{BUDGET}">50 ms = 20 TPS budget</text>')
    for i, (label, mspt, tps, good) in enumerate(bars):
        y = top + i * row_h
        parts.append(f'<text x="{left - 10}" y="{y + 19}" font-size="12.5" text-anchor="end" fill="{INK}">{escape(label)}</text>')
        parts.append(f'<rect x="{left}" y="{y + 6}" width="{x(mspt) - left:.1f}" height="20" rx="3" fill="{GOOD if good else BAR}"/>')
        parts.append(f'<text x="{x(mspt) + 6:.1f}" y="{y + 20}" font-size="12" fill="{INK}" stroke="{BG}" stroke-width="4" paint-order="stroke">{mspt:.0f} ms · {tps:.1f} TPS</text>')
    parts.append('</svg>')
    Path(out).write_text('\n'.join(parts) + '\n')


def main():
    rows, out = load(sys.argv[1]), Path(sys.argv[2])
    chain, settled = 'chain 400 blocks', 'chain 400 blocks (settled)'
    worst = [
        ('vanilla, simulation 6', *pick(rows, 'sim6', chain), False),
        ('simulation 5', *pick(rows, 'sim5', chain), False),
        ('simulation 4', *pick(rows, 'sim4', chain), False),
        ('sim 6 + activation range', *pick(rows, 'sim6 +EAR', chain), False),
        ('sim 4 + activation range', *pick(rows, 'sim4 +EAR', chain), False),
        ('governor v1 (settled)', *pick(rows, 'sim6 +governor v1', settled), False),
        ('governor v4 + NUMA/THP', *pick(rows, 'sim6 +governor v4 +NUMA/THP', chain), True),
        ('governor v4 + NUMA/THP (settled)', *pick(rows, 'sim6 +governor v4 +NUMA/THP', settled), True),
    ]
    bar_chart('Worst case: ~50-55 spread players in one Folia region',
              '60 bots on a 400-block grid, view 10 · sim 6 unless noted · worst region average tick time',
              worst, out / 'worst-case.svg')
    spread = [
        ('vanilla sim 6, spawn spread ±4500', *pick(rows, 'sim6', 'random ±4500'), False),
        ('vanilla sim 6, spawn spread ±10000', *pick(rows, 'sim6', 'random ±10000'), True),
        ('sim 4 + EAR, spread ±4500', *pick(rows, 'sim4 +EAR', 'random ±4500'), False),
        ('sim 4 + EAR, spread ±10000', *pick(rows, 'sim4 +EAR', 'random ±10000'), True),
    ]
    bar_chart('Spreading players beats shrinking distances',
              '60 bots at uniform random positions · worst region average tick time',
              spread, out / 'spawn-spread.svg')


if __name__ == '__main__':
    main()

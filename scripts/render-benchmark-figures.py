#!/usr/bin/env python3
import csv
from pathlib import Path

CSV = Path('benchmarks/results.csv')
OUT = Path('benchmarks/figures')
OUT.mkdir(parents=True, exist_ok=True)


def read_rows():
    if not CSV.exists():
        return []
    with CSV.open(newline='', encoding='utf-8') as f:
        return [r for r in csv.DictReader(f) if r.get('part')]


def num(row, key):
    try:
        return float(row.get(key, '0') or 0)
    except ValueError:
        return 0.0


def scenario_value(row):
    text = row.get('scenario', '')
    digits = ''.join(ch if ch.isdigit() else ' ' for ch in text).split()
    return float(digits[-1]) if digits else num(row, 'shardCount')


def svg_chart(path, title, rows, x_label, series):
    width, height = 900, 420
    left, right, top, bottom = 80, 40, 50, 70
    plot_w = width - left - right
    plot_h = height - top - bottom
    xs = [scenario_value(r) for r in rows] or [0]
    ys = []
    for _, key, _ in series:
        ys.extend(num(r, key) for r in rows)
    y_max = max(ys) if ys else 1.0
    if y_max <= 0:
        y_max = 1.0
    x_min, x_max = min(xs), max(xs)
    if x_min == x_max:
        x_min -= 1
        x_max += 1

    def px(x):
        return left + (x - x_min) / (x_max - x_min) * plot_w

    def py(y):
        return top + plot_h - (y / y_max) * plot_h

    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">']
    parts.append('<rect width="100%" height="100%" fill="white"/>')
    parts.append(f'<text x="{width/2}" y="28" text-anchor="middle" font-family="sans-serif" font-size="20">{title}</text>')
    parts.append(f'<line x1="{left}" y1="{top+plot_h}" x2="{left+plot_w}" y2="{top+plot_h}" stroke="#333"/>')
    parts.append(f'<line x1="{left}" y1="{top}" x2="{left}" y2="{top+plot_h}" stroke="#333"/>')
    parts.append(f'<text x="{width/2}" y="{height-22}" text-anchor="middle" font-family="sans-serif" font-size="13">{x_label}</text>')
    for tick in range(5):
        y = y_max * tick / 4
        yy = py(y)
        parts.append(f'<line x1="{left-4}" y1="{yy:.1f}" x2="{left}" y2="{yy:.1f}" stroke="#333"/>')
        parts.append(f'<text x="{left-8}" y="{yy+4:.1f}" text-anchor="end" font-family="sans-serif" font-size="11">{y:.1f}</text>')
    for x in sorted(set(xs)):
        xx = px(x)
        parts.append(f'<line x1="{xx:.1f}" y1="{top+plot_h}" x2="{xx:.1f}" y2="{top+plot_h+4}" stroke="#333"/>')
        parts.append(f'<text x="{xx:.1f}" y="{top+plot_h+18}" text-anchor="middle" font-family="sans-serif" font-size="11">{x:g}</text>')

    for name, key, color in series:
        points = [(px(scenario_value(r)), py(num(r, key))) for r in rows]
        if len(points) == 1:
            x, y = points[0]
            parts.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="4" fill="{color}"/>')
        elif points:
            d = ' '.join(f'{x:.1f},{y:.1f}' for x, y in points)
            parts.append(f'<polyline points="{d}" fill="none" stroke="{color}" stroke-width="2.5"/>')
            for x, y in points:
                parts.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="3" fill="{color}"/>')
    lx = left + 10
    for i, (name, _, color) in enumerate(series):
        y = top + 18 + i * 20
        parts.append(f'<rect x="{lx}" y="{y-10}" width="12" height="12" fill="{color}"/>')
        parts.append(f'<text x="{lx+18}" y="{y}" font-family="sans-serif" font-size="12">{name}</text>')
    parts.append('</svg>')
    path.write_text('\n'.join(parts), encoding='utf-8')


def main():
    rows = read_rows()
    by_part = lambda p: [r for r in rows if r.get('part') == p]
    svg_chart(OUT / 'part-a-scaling.svg', 'Part A: 2PC scaling', by_part('A'), 'participants', [
        ('throughput tx/s', 'throughputTxPerSec', '#1664c0'),
        ('p95 ms', 'p95Ms', '#c2410c'),
    ])
    svg_chart(OUT / 'part-b-aborts.svg', 'Part B: abort ratio vs balance', by_part('B'), 'initial balance from scenario', [
        ('abort ratio', 'abortRatio', '#a21caf'),
    ])
    svg_chart(OUT / 'part-c-contention.svg', 'Part C: contention', by_part('C'), 'sender pool size', [
        ('throughput tx/s', 'throughputTxPerSec', '#047857'),
        ('abort ratio', 'abortRatio', '#b91c1c'),
    ])
    d_rows = [r for r in rows if r.get('part') in {'A', 'D'} and r.get('scenario') in {'participants-2', 'local-credit'}]
    svg_chart(OUT / 'part-d-local-vs-2pc.svg', 'Part D: local vs 2PC latency', d_rows, 'scenario marker', [
        ('avg ms', 'avgMs', '#1d4ed8'),
        ('p95 ms', 'p95Ms', '#ea580c'),
    ])
    svg_chart(OUT / 'part-e-wal-volume.svg', 'Part E: WAL replay time', by_part('E'), 'transactions from scenario', [
        ('walReplayMs', 'avgMs', '#0f766e'),
    ])


if __name__ == '__main__':
    main()

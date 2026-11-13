#!/usr/bin/env python3
"""Print the nodes of a uiautomator dump: resource-id, text, bounds, centre."""
import re, sys

NODE = re.compile(r'<node\b([^>]*)>')
ATTR = re.compile(r'([\w-]+)="([^"]*)"')

def nodes(path):
    text = open(path, encoding='utf-8', errors='replace').read()
    for m in NODE.finditer(text):
        yield dict(ATTR.findall(m.group(1)))

def centre(bounds):
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', bounds or '')
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2

def main():
    path = sys.argv[1]
    want = sys.argv[2] if len(sys.argv) > 2 else None
    for node in nodes(path):
        rid = node.get('resource-id', '')
        label = node.get('text', '') or node.get('content-desc', '')
        if want and rid != want and node.get('text') != want and node.get('content-desc') != want:
            continue
        c = centre(node.get('bounds'))
        if c is None:
            continue
        print('%s\t%s\t%s\t%d %d' % (rid or '-', label or '-', node.get('bounds'), c[0], c[1]))

main()

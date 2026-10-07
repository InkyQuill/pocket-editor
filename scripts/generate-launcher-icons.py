#!/usr/bin/env python3
"""Build Android launcher assets from the approved SVG. Requires Inkscape CLI."""
from pathlib import Path
import os
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
BRAND = ROOT / 'assets/branding/pocket-logo'
RES = ROOT / 'app/src/main/res'
ANDROID = 'http://schemas.android.com/apk/res/android'
BG = '#F7F3EC'
SCALE = .108
TX, TY = 6.372, -.108
paths = ET.parse(BRAND / 'pocket-editor-logo.svg').getroot().findall('{http://www.w3.org/2000/svg}path')
ET.register_namespace('android', ANDROID)

def save_xml(root, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    ET.indent(root)
    ET.ElementTree(root).write(path, encoding='utf-8', xml_declaration=True)
    with path.open('a') as stream:
        stream.write('\n')

for name, mono in [('foreground', False), ('monochrome', True)]:
    vector = ET.Element('vector', {f'{{{ANDROID}}}{key}': value for key, value in {
        'width': '108dp', 'height': '108dp', 'viewportWidth': '108', 'viewportHeight': '108'
    }.items()})
    group = ET.SubElement(vector, 'group', {f'{{{ANDROID}}}{key}': str(value) for key, value in {
        'scaleX': SCALE, 'scaleY': SCALE, 'translateX': TX, 'translateY': TY
    }.items()})
    for path in paths:
        ET.SubElement(group, 'path', {f'{{{ANDROID}}}fillColor': '#000000' if mono else path.get('fill'),
                                      f'{{{ANDROID}}}pathData': path.get('d')})
    save_xml(vector, RES / f'drawable/ic_launcher_{name}.xml')

colors = ET.Element('resources')
ET.SubElement(colors, 'color', {'name': 'ic_launcher_background'}).text = BG
save_xml(colors, RES / 'values/launcher_colors.xml')
for version in [26]:
    adaptive = ET.Element('adaptive-icon')
    for layer, ref in [('background', '@color/ic_launcher_background'), ('foreground', '@drawable/ic_launcher_foreground')]:
        ET.SubElement(adaptive, layer, {f'{{{ANDROID}}}drawable': ref})
    ET.SubElement(adaptive, 'monochrome', {f'{{{ANDROID}}}drawable': '@drawable/ic_launcher_monochrome'})
    for name in ['ic_launcher', 'ic_launcher_round']:
        save_xml(adaptive, RES / f'mipmap-anydpi-v{version}/{name}.xml')

def symbol(mono=False):
    content = ''.join(f'<path fill="{("#D8E4ED" if mono else p.get("fill"))}" d="{p.get("d")}"/>' for p in paths)
    return f'<g transform="translate({TX} {TY}) scale({SCALE})">{content}</g>'

def icon(round_icon=False, mono=False):
    background = '#293E50' if mono else BG
    shape = '<circle cx="54" cy="54" r="36"' if round_icon else '<rect x="18" y="18" width="72" height="72" rx="16"'
    return f'{shape} fill="{background}"/>{symbol(mono)}'

def export(svg, destination, size):
    with tempfile.TemporaryDirectory() as directory:
        source = Path(directory) / 'icon.svg'
        source.write_text(svg)
        env = dict(os.environ)
        env.pop('DISPLAY', None)
        env.pop('WAYLAND_DISPLAY', None)
        subprocess.run(['inkscape', str(source), '--export-type=png', f'--export-filename={destination}',
                        f'--export-width={size}'], check=True, env=env, stdout=subprocess.DEVNULL)

for density, size in [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)]:
    for rounded in [False, True]:
        name = 'ic_launcher_round' if rounded else 'ic_launcher'
        svg = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72">{icon(rounded)}</svg>'
        export(svg, RES / f'mipmap-{density}/{name}.png', size)
preview = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 360 136"><rect width="360" height="136" fill="#E7E5E1"/>'
for offset, rounded, mono in [(8, False, False), (126, True, False), (244, True, True)]:
    preview += f'<g transform="translate({offset} 2)">{icon(rounded, mono)}</g>'
preview += '</svg>'
(BRAND / 'android-icon-preview.svg').write_text(preview + '\n')
export(preview, BRAND / 'android-icon-preview.png', 1440)
print('Generated adaptive vectors, themed layer, 10 PNG fallbacks, and preview.')

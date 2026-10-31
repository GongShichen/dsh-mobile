#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12"
# dependencies = ["fonttools==4.68.0", "pillow==13.0.0", "uharfbuzz==0.57.0", "cairosvg==2.9.1"]
# ///
"""Regenerate Harness Link's platform artwork from the approved SVG masters."""
from pathlib import Path
import argparse
import hashlib
import io
import json
import os
import shutil
import sys
import urllib.request
import xml.etree.ElementTree as ET
from html import escape

# Homebrew's Cairo is outside dyld's default search path on Apple Silicon.
if sys.platform == "darwin" and Path("/opt/homebrew/lib/libcairo.2.dylib").exists() and not os.environ.get("HL_CAIRO_READY"):
    env = dict(os.environ)
    env["DYLD_FALLBACK_LIBRARY_PATH"] = "/opt/homebrew/lib:" + env.get("DYLD_FALLBACK_LIBRARY_PATH", "")
    env["HL_CAIRO_READY"] = "1"
    os.execve(sys.executable, [sys.executable, *sys.argv], env)

import cairosvg
import uharfbuzz as hb
from PIL import Image
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen

ROOT = Path(__file__).resolve().parents[1]
BRAND = ROOT / "design-system/brand"
MASTERS = BRAND / "masters"
META = json.loads((BRAND / "HLBrand.json").read_text())
COLORS = META["colors"]


def write(path: Path, content: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content.rstrip() + "\n")


def write_json(path: Path, data):
    write(path, json.dumps(data, ensure_ascii=False, indent=2))


def svg(body: str, width=256, height=256, title="Harness Link — Glide Ray"):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width:g} {height:g}" width="{width:g}" height="{height:g}" '
            f'role="img" aria-labelledby="hl-title"><title id="hl-title">{escape(title)}</title>{body}</svg>')


def path_data(filename: str):
    root = ET.parse(MASTERS / filename).getroot()
    return next(node for node in root if node.tag.endswith("path")).attrib["d"]


def mark(data: str, color: str, scale=1.0):
    path = f'<path fill="{color}" fill-rule="evenodd" d="{data}"/>'
    if scale == 1.0:
        return path
    return f'<g transform="translate(128 128) scale({scale:g}) translate(-128 -128)">{path}</g>'


def png(source: str, destination: Path, width: int, mode="RGBA"):
    root = ET.fromstring(source)
    _, _, vw, vh = map(float, root.attrib["viewBox"].split())
    rendered = cairosvg.svg2png(bytestring=source.encode(), output_width=width, output_height=round(width * vh / vw))
    with Image.open(io.BytesIO(rendered)) as image:
        result = image.convert(mode)
        destination.parent.mkdir(parents=True, exist_ok=True)
        result.save(destination, format="PNG", optimize=True)


def wordmark():
    descriptor = META["wordmarkFont"]
    font_path = ROOT / ".local/hl-brand/font/Manrope[wght].ttf"
    if not font_path.exists():
        font_path.parent.mkdir(parents=True, exist_ok=True)
        url = descriptor.get("download") or ("https://raw.githubusercontent.com/google/fonts/" + descriptor["revision"] + "/ofl/manrope/Manrope%5Bwght%5D.ttf")
        font_path.write_bytes(urllib.request.urlopen(url, timeout=30).read())
    data = font_path.read_bytes()
    if hashlib.sha256(data).hexdigest() != descriptor["sha256"]:
        raise RuntimeError("Manrope source does not match the recorded SHA-256")
    font = instantiateVariableFont(TTFont(font_path), {"wght": descriptor["weight"]}, inplace=False)
    glyphs = font.getGlyphSet()
    order = font.getGlyphOrder()
    upem = font["head"].unitsPerEm
    shaper = hb.Font(hb.Face(data))
    hb.ot_font_set_funcs(shaper)
    shaper.scale = (upem, upem)
    shaper.set_variations({"wght": descriptor["weight"]})
    buffer = hb.Buffer()
    buffer.add_str(META["name"])
    buffer.guess_segment_properties()
    hb.shape(shaper, buffer, {"kern": True})
    cursor_x = cursor_y = 0
    paths = []
    for info, position in zip(buffer.glyph_infos, buffer.glyph_positions):
        pen = SVGPathPen(glyphs, ntos=lambda n: f"{n:.5f}".rstrip("0").rstrip(".") if n else "0")
        transform = TransformPen(pen, (1 / upem, 0, 0, -1 / upem,
                                      (cursor_x + position.x_offset) / upem,
                                      -(cursor_y + position.y_offset) / upem))
        glyphs[order[info.codepoint]].draw(transform)
        if pen.getCommands():
            paths.append(pen.getCommands())
        cursor_x += position.x_advance
        cursor_y += position.y_advance
    return paths, cursor_x / upem


def type_paths(paths, color, x, baseline, size):
    return (f'<g transform="translate({x:g} {baseline:g}) scale({size:g})" fill="{color}">'
            + "".join(f'<path d="{d}"/>' for d in paths) + "</g>")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plugin-dir", type=Path, help="Also update the standalone desktop plugin's mirrored vector")
    parser.add_argument("--out-dir", type=Path, default=ROOT / ".local/artifacts/HLLogoKit")
    args = parser.parse_args()
    kit = args.out_dir.resolve()
    primary = path_data("HLMark.svg")
    small = path_data("HLMarkSmall.svg")
    outlines, advance = wordmark()
    type_size = 104
    horizontal_width = round(240 + advance * type_size + 32)
    symbol_group = '<g transform="translate(16 13) scale(.82)">' + mark(primary, COLORS["blue"]) + "</g>"
    horizontal = svg(symbol_group + type_paths(outlines, COLORS["ink"], 240, 166, type_size), horizontal_width, 256)
    horizontal_dark = svg(symbol_group.replace(COLORS["blue"], COLORS["darkBlue"]) + type_paths(outlines, COLORS["white"], 240, 166, type_size), horizontal_width, 256)
    stacked = svg('<g transform="translate(243 -4) scale(.6)">' + mark(primary, COLORS["blue"]) + "</g>"
                  + type_paths(outlines, COLORS["ink"], (640 - advance * 60) / 2, 220, 60), 640, 256)
    word = svg(type_paths(outlines, COLORS["ink"], 28, 116, type_size), round(advance * type_size + 56), 160)
    masters = {"HLLogoHorizontal.svg": horizontal, "HLLogoHorizontalDark.svg": horizontal_dark,
               "HLLogoStacked.svg": stacked, "HLWordmark.svg": word,
               "HLMarkWhite.svg": svg(mark(primary, COLORS["white"], META["reverseScale"]))}
    for name, source in masters.items():
        write(MASTERS / name, source)

    (kit / "masters").mkdir(parents=True, exist_ok=True)
    for source in MASTERS.glob("*.svg"):
        shutil.copy2(source, kit / "masters" / source.name)
    for name in ["HLMark.svg", "HLMarkSmall.svg", "HLMarkWhite.svg", "HLLogoHorizontal.svg", "HLLogoHorizontalDark.svg", "HLLogoStacked.svg", "HLWordmark.svg"]:
        source = (MASTERS / name).read_text()
        png(source, kit / "png" / (Path(name).stem + "-1024.png"), 1024)

    icon_scale = META["appleScale"] * META["reverseScale"]
    light = svg(f'<path fill="{COLORS["blue"]}" d="M0 0H256V256H0Z"/>' + mark(primary, COLORS["white"], icon_scale))
    dark = svg(mark(primary, COLORS["darkBlue"], icon_scale))
    tinted = svg('<path fill="#000000" d="M0 0H256V256H0Z"/>' + mark(primary, "#FFFFFF", icon_scale))
    apple = ROOT / "iosApp/Resources/HLAssets.xcassets/HLAppIcon.appiconset"
    images = []
    for name, source, mode, appearance in [
        ("HLAppIcon1024.png", light, "RGB", None),
        ("HLAppIconDark1024.png", dark, "RGBA", "dark"),
        ("HLAppIconTinted1024.png", tinted, "L", "tinted"),
    ]:
        png(source, apple / name, 1024, mode)
        write(kit / "app-icons" / name.replace(".png", ".svg"), source)
        (kit / "app-icons").mkdir(parents=True, exist_ok=True)
        shutil.copy2(apple / name, kit / "app-icons" / name)
        item = {"filename": name, "idiom": "universal", "platform": "ios", "size": "1024x1024"}
        if appearance:
            item["appearances"] = [{"appearance": "luminosity", "value": appearance}]
        images.append(item)
    write_json(apple / "Contents.json", {"images": images, "info": {"author": "xcode", "version": 1}})
    write_json(apple.parent / "Contents.json", {"info": {"author": "xcode", "version": 1}})

    android = ROOT / "androidApp/src/main/res"
    write(android / "values/hl_brand.xml", '<?xml version="1.0" encoding="utf-8"?>\n<resources><color name="hl_brand_blue">' + COLORS["blue"] + '</color></resources>')
    foreground = ('<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="256" android:viewportHeight="256">'
                  f'<group android:pivotX="128" android:pivotY="128" android:scaleX="{META["androidForegroundScale"]}" android:scaleY="{META["androidForegroundScale"]}">'
                  f'<path android:fillColor="#FFFFFFFF" android:fillType="evenOdd" android:pathData="{primary}"/></group></vector>')
    notification = ('<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="256" android:viewportHeight="256">'
                    f'<path android:fillColor="#FFFFFFFF" android:fillType="evenOdd" android:pathData="{small}"/></vector>')
    write(android / "drawable/hl_launcher_foreground.xml", foreground)
    write(android / "drawable/hl_notification.xml", notification)
    for api in [26, 33]:
        monochrome = '<monochrome android:drawable="@drawable/hl_launcher_foreground"/>' if api >= 33 else ''
        adaptive = ('<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">'
                    '<background android:drawable="@color/hl_brand_blue"/><foreground android:drawable="@drawable/hl_launcher_foreground"/>'
                    + monochrome + '</adaptive-icon>')
        for name in ["hl_launcher", "hl_launcher_round"]:
            write(android / f"mipmap-anydpi-v{api}/{name}.xml", adaptive)
    png(light, kit / "app-icons/HLGooglePlay512.png", 512, "RGB")
    resource_names = ["values/hl_brand.xml", "drawable/hl_launcher_foreground.xml", "drawable/hl_notification.xml"]
    resource_names += [f"mipmap-anydpi-v{api}/{name}.xml" for api in [26, 33] for name in ["hl_launcher", "hl_launcher_round"]]
    for name in resource_names:
        destination = kit / "android-res" / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(android / name, destination)

    kotlin = f'''package io.dsh.mobile.design

// Generated by scripts/HLBuildBrand.py from the approved SVG masters.
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private const val HL_BRAND_PATH = "{primary}"
private const val HL_BRAND_SMALL_PATH = "{small}"

@Composable
fun HLBrandMark(modifier: Modifier = Modifier.size(64.dp), tint: Color = MaterialTheme.colorScheme.primary, small: Boolean = false) {{
    val path = remember(small) {{ PathParser().parsePathString(if (small) HL_BRAND_SMALL_PATH else HL_BRAND_PATH).toPath().apply {{ fillType = PathFillType.EvenOdd }} }}
    Canvas(modifier.semantics {{ contentDescription = "Harness Link" }}) {{
        val scaleX = size.width / 256f
        val scaleY = size.height / 256f
        withTransform({{ scale(scaleX, scaleY, pivot = Offset.Zero) }}) {{ drawPath(path, tint) }}
    }}
}}
'''
    write(ROOT / "composeApp/src/commonMain/kotlin/io/dsh/mobile/design/HLBrandMark.kt", kotlin)
    digest = hashlib.sha256((MASTERS / "HLMark.svg").read_bytes()).hexdigest()
    if args.plugin_dir:
        plugin = args.plugin_dir.resolve()
        if not (plugin / "src/client/HLAccess.tsx").exists():
            raise RuntimeError("--plugin-dir must point to the Harness Link desktop plugin")
        component = f'''// Generated from dsh-mobile/design-system/brand/masters. Source SHA-256: {digest}
const HL_BRAND_PATH = '{primary}'
const HL_BRAND_SMALL_PATH = '{small}'

export function HLBrandMark({{ size = 24, className }}: {{ size?: number; className?: string }}) {{
  return <svg width={{size}} height={{size}} viewBox="0 0 256 256" className={{className}} fill="currentColor" aria-hidden="true" focusable="false"><path fillRule="evenodd" d={{size <= 24 ? HL_BRAND_SMALL_PATH : HL_BRAND_PATH}}/></svg>
}}
'''
        write(plugin / "src/client/HLBrandMark.tsx", component)
        for name in ["HLMark.svg", "HLMarkSmall.svg"]:
            destination = plugin / "docs/assets" / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(MASTERS / name, destination)
        write_json(plugin / "docs/assets/HLBrand.json", {"name": META["name"], "sourceRepository": "GongShichen/dsh-mobile", "sourcePath": "design-system/brand/masters/HLMark.svg", "sha256": digest})
    write_json(kit / "HLBrand.json", META)
    (kit / "licenses").mkdir(parents=True, exist_ok=True)
    shutil.copy2(ROOT / "licenses/Manrope-OFL.txt", kit / "licenses/Manrope-OFL.txt")
    print(f"Generated approved vector, wordmarks, iOS/Android assets and shared UI artwork in {kit}")


if __name__ == "__main__":
    main()

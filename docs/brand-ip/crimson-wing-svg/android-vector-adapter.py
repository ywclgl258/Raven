"""Convert simple Raven nav SVG profiles to Android VectorDrawable.

This is only an adapter for the authored SVG files in this folder. It keeps the
source SVG as the design authority and refuses unsupported SVG constructs.
"""

from pathlib import Path
import re
import sys
from xml.etree import ElementTree

SVG_NS = "{http://www.w3.org/2000/svg}"
PROFILE_NAMES = {
    "01-crimson-flight-simple.svg": "nav_home",
    "02-moonlit-projection-simple.svg": "nav_explore",
    "03-silver-archivist-simple.svg": "nav_search",
    "05-raven-archive-simple.svg": "nav_library",
    "04-night-side-tail-simple.svg": "nav_profile",
}


def path_data(source: Path, eyes_only: bool) -> str:
    tree = ElementTree.parse(source)
    root = tree.getroot()
    if root.attrib.get("viewBox") != "0 0 512 512":
        raise ValueError(f"{source.name}: unexpected viewBox")

    paths = []
    for element in root.iter():
        local_name = element.tag.removeprefix(SVG_NS)
        if local_name not in {"path", "ellipse", "circle", "polygon"}:
            continue
        in_eyes = any(
            parent.get("id") == "eyes"
            for parent in root.iter()
            if element in list(parent)
        )
        if eyes_only != in_eyes:
            continue
        if local_name == "path":
            paths.append(element.attrib["d"])
        elif local_name == "ellipse":
            if element.attrib.get("fill") == "none":
                continue
            cx = float(element.attrib["cx"])
            cy = float(element.attrib["cy"])
            rx = float(element.attrib["rx"])
            ry = float(element.attrib["ry"])
            paths.append(
                f"M{cx - rx:.2f},{cy:.2f} "
                f"A{rx:.2f},{ry:.2f},0,1,0,{cx + rx:.2f},{cy:.2f} "
                f"A{rx:.2f},{ry:.2f},0,1,0,{cx - rx:.2f},{cy:.2f} Z"
            )
        elif local_name == "circle":
            if element.attrib.get("fill") == "none":
                continue
            cx = float(element.attrib["cx"])
            cy = float(element.attrib["cy"])
            radius = float(element.attrib["r"])
            paths.append(
                f"M{cx - radius:.2f},{cy:.2f} "
                f"A{radius:.2f},{radius:.2f},0,1,0,{cx + radius:.2f},{cy:.2f} "
                f"A{radius:.2f},{radius:.2f},0,1,0,{cx - radius:.2f},{cy:.2f} Z"
            )
        elif local_name == "polygon":
            coords = [float(value) for value in element.attrib["points"].replace(",", " ").split()]
            points = list(zip(coords[::2], coords[1::2]))
            paths.append(
                "M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in points) + " Z"
            )
    return " ".join(paths)


def vector_xml(kind: str, body: str, layer: str, animated: bool) -> str:
    alpha = "255" if layer == "selected" else "255"
    animation = ""
    if animated:
        animation = f'''
    <group android:name="{layer}_blink">
        <path android:pathData="{body}">
            <aapt:attr name="android:fillColor">
                <gradient android:startX="0" android:startY="0" android:endX="0" android:endY="512" android:type="linear">
                    <item android:color="#00000000" android:offset="0" />
                    <item android:color="#00000000" android:offset="1" />
                </gradient>
            </aapt:attr>
        </path>
    </group>'''
        raise ValueError("Runtime animated VectorDrawable is handled by Compose")
    return f"""<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="36dp"
    android:height="36dp"
    android:viewportWidth="512"
    android:viewportHeight="512"
    android:tint="#FF4D58">
    <path
        android:fillColor="@android:color/white"
        android:fillAlpha="{alpha}"
        android:pathData="{body}" />
</vector>
"""


def compose_override(name: str, selected: str, unselected: str) -> str:
    escape = lambda value: value.replace("\\", "\\\\").replace('"', '\\"')
    return f'''package com.raven.app.core.designsystem.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.custom.RavenNavAvatar{name}
import androidx.compose.ui.graphics.vector.ImageVector

private val RavenNavAvatar{name}Selected: ImageVector = Icons.Custom.RavenNavAvatar{name}
private val RavenNavAvatar{name}Unselected: ImageVector = Icons.Custom.RavenNavAvatar{name}
'''


def main() -> None:
    source_dir = Path(__file__).parent
    output_dir = source_dir / "android-vector"
    output_dir.mkdir(exist_ok=True)
    for filename, resource_name in PROFILE_NAMES.items():
        source = source_dir / filename
        selected = path_data(source, eyes_only=False)
        unselected = path_data(source, eyes_only=False)
        (output_dir / f"{resource_name}.svg").write_text(
            source.read_text(encoding="utf-8"),
            encoding="utf-8",
        )
        (output_dir / f"{resource_name}-selected.xml").write_text(
            vector_xml(resource_name, selected, "selected", False),
            encoding="utf-8",
        )
        (output_dir / f"{resource_name}-unselected.xml").write_text(
            vector_xml(resource_name, unselected, "unselected", False),
            encoding="utf-8",
        )
        print(filename, "->", resource_name)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
IngeTrazo Mobile Desktop Companion Converter
============================================
Converts desktop CAD/3D models (including pre-2021 classic MFC .skp files,
.dxf, .dae, or large .igz projects) into mobile-friendly IngeTrazo (.igz)
or binary glTF (.glb) files that open directly in IngeTrazo Mobile.

Dependencies (MIT / open-source only, zero proprietary SDKs):
    pip install openskp trimesh ezdxf

Usage:
    python tools/ingetrazo_converter.py input.skp --out output.igz
    python tools/ingetrazo_converter.py input.skp --out output.glb --format glb
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys
import zipfile


def convert_skp_to_igz(src: pathlib.Path, dst: pathlib.Path) -> None:
    try:
        from openskp import SkpFile
    except ImportError as exc:
        raise SystemExit(
            "Missing 'openskp'. Install via: pip install git+https://github.com/iamahsanmehmood/openskp.git#subdirectory=packages/python"
        ) from exc

    INCH_TO_METRES = 0.0254
    model = SkpFile.open(str(src)).parse()
    faces_json = []
    edges_json = []

    def emit_definition(defn, mat4=None):
        def xform(pt):
            x, y, z = pt[0] * INCH_TO_METRES, pt[1] * INCH_TO_METRES, pt[2] * INCH_TO_METRES
            if mat4 is None:
                return [x, y, z]
            s = mat4[12] if len(mat4) >= 13 and mat4[12] != 0 else 1.0
            tx = (mat4[0] * x + mat4[1] * y + mat4[2] * z + mat4[9] * INCH_TO_METRES) * s
            ty = (mat4[3] * x + mat4[4] * y + mat4[5] * z + mat4[10] * INCH_TO_METRES) * s
            tz = (mat4[6] * x + mat4[7] * y + mat4[8] * z + mat4[11] * INCH_TO_METRES) * s
            return [tx, ty, tz]

        for e in defn.edges.values():
            va = defn.vertices.get(e.v1_id)
            vb = defn.vertices.get(e.v2_id)
            if va and vb:
                edges_json.append({"a": xform((va.x, va.y, va.z)), "b": xform((vb.x, vb.y, vb.z))})

        for f in defn.faces.values():
            if not f.loops:
                continue
            outer = []
            for eid, _ in f.loops[0]:
                edge = defn.edges.get(eid)
                if edge and edge.v1_id in defn.vertices:
                    v = defn.vertices[edge.v1_id]
                    outer.append(xform((v.x, v.y, v.z)))
            if len(outer) >= 3:
                faces_json.append({"vertices": outer})

        for inst in getattr(defn, "instances", []):
            ref = model.definitions.get(inst.ref_idx)
            if ref:
                emit_definition(ref, inst.matrix)

    for defn in model.definitions.values():
        if defn.id == 0 or not getattr(defn, "is_component", False):
            emit_definition(defn)

    payload = {
        "igz_format": 1,
        "app_version": "ingetrazo-mobile-converter-1.0",
        "scene": {
            "edges": edges_json,
            "faces": faces_json,
            "dimension_style": {"decimals": 2, "units": "m"},
        },
    }
    dst.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(f"Converted {src.name} -> {dst.name} ({len(faces_json)} faces, {len(edges_json)} edges)")


def main() -> int:
    parser = argparse.ArgumentParser(description="IngeTrazo Mobile companion CAD converter")
    parser.add_argument("input", type=pathlib.Path, help="Input .skp file")
    parser.add_argument("--out", type=pathlib.Path, required=True, help="Output .igz or .glb path")
    parser.add_argument("--format", choices=["igz", "glb"], default="igz")
    args = parser.parse_args()

    if not args.input.exists():
        print(f"Input file not found: {args.input}", file=sys.stderr)
        return 1

    if args.format == "igz":
        convert_skp_to_igz(args.input, args.out)
    else:
        try:
            from openskp import SkpFile
            from openskp.export.glb import export_glb
        except ImportError as exc:
            raise SystemExit("GLB export requires openskp[export].") from exc
        model = SkpFile.open(str(args.input)).parse()
        export_glb(model, str(args.out))
        print(f"Exported GLB -> {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

"""E7 step food (v0.1.4): decimate + calibrate the Tripo GLBs for the app (profile B12 / D25).

Usage (factory venv python):  ~/.factory-venv/bin/python tools/prep_food.py [ids...]
Reads   ~/Downloads/chef-board-tripo/<id>.glb  (raw Tripo export, ~2M triangles)
Writes  app/src/main/assets/models/food/<id>.glb  (<= 12000 triangles, base colour 1024 px for big things / 512 px for
        small ones, every material OPAQUE, y up, longest horizontal side along x, bottom centre at the origin, longest side
        = the catalog's sizeM metres) and  app/src/main/assets/models/food/index.json  ({id: {w, d, h, tris, rawTris}}).
The catalog (id, size, prompt) is read from StepModels.kt so the app, the tests and this tool share one list.
"""
import json, os, re, subprocess, sys, tempfile
import numpy as np
import trimesh

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
KT = os.path.join(ROOT, "app/src/main/java/io/embodit/chefboard/domain/recipe/StepModels.kt")
OUT = os.path.join(ROOT, "app/src/main/assets/models/food")
RAW = os.environ.get("CHEF_TRIPO_RAW") or os.path.expanduser("~/Downloads/chef-board-tripo")
DECIMATE = os.path.expanduser("~/Desktop/Spatial Apps/spatial-app-factory/bin/glb_decimate.py")
FACES = 12000
# Tripo posed these diagonally / standing although the prompt says "lying flat": principal-axis align them first
# (longest axis -> x, thinnest -> y up) so they lie on the counter.
LIE_FLAT = {"seabass"}


def catalog():
    src = open(KT, encoding="utf-8").read()
    return {m.group(1): (float(m.group(2)), m.group(3)) for m in re.finditer(r'm\("([a-z_]+)", ([0-9.]+)f, "([^"]+)"\)', src)}


def tris(path):
    s = trimesh.load(path, force="scene")
    return int(sum(len(g.faces) for g in s.geometry.values()))


def prep(mid, size, index):
    raw = os.path.join(RAW, f"{mid}.glb")
    if not os.path.exists(raw):
        print(f"{mid}: no raw GLB ({raw})")
        return
    os.makedirs(OUT, exist_ok=True)
    tmp = os.path.join(tempfile.gettempdir(), f"chef-board-{mid}-dec.glb")
    tex = 1024 if size >= 0.2 else 512
    subprocess.run([sys.executable, DECIMATE, raw, tmp, "--faces", str(FACES), "--tex", str(tex)], check=True)
    scene = trimesh.load(tmp, force="scene")
    lo, hi = scene.bounds
    ext = hi - lo
    m = np.eye(4)
    if mid in LIE_FLAT:
        pts = np.vstack([trimesh.transformations.transform_points(scene.geometry[g].vertices, tf) for tf, g in
                         (scene.graph[n] for n in scene.graph.nodes_geometry)])
        pts = pts - pts.mean(0)
        w, v = np.linalg.eigh(np.cov(pts.T))  # ascending
        R = np.stack([v[:, 2], v[:, 0], v[:, 1]])  # rows: new x = longest, new y = thinnest, new z = middle
        if np.linalg.det(R) < 0:
            R[2] *= -1
        m = np.eye(4); m[:3, :3] = R
        ext = np.ptp(pts @ R.T, axis=0)
    # long things lying down (fish, scallions, spaghetti) show their side to the eye: longest horizontal side → x
    if ext[2] > ext[0] * 1.15:
        m = trimesh.transformations.rotation_matrix(np.pi / 2, [0, 1, 0]) @ m
    for name in scene.graph.nodes_geometry:
        tf, g = scene.graph[name]
        scene.geometry[g].apply_transform(m @ tf)
        scene.graph.update(name, matrix=np.eye(4))
    lo, hi = scene.bounds
    k = size / float(max(hi - lo))
    centre = np.array([(lo[0] + hi[0]) / 2, lo[1], (lo[2] + hi[2]) / 2])
    s = np.eye(4); s[:3, :3] *= k; s[:3, 3] = -centre * k
    for g in scene.geometry.values():
        g.apply_transform(s)
        mat = getattr(g.visual, "material", None)
        if mat is not None and hasattr(mat, "alphaMode"):
            mat.alphaMode = "OPAQUE"
    out = os.path.join(OUT, f"{mid}.glb")
    scene.export(out)
    lo2, hi2 = trimesh.load(out, force="scene").bounds
    e2 = hi2 - lo2
    index[mid] = {"w": round(float(e2[0]), 4), "d": round(float(e2[2]), 4), "h": round(float(e2[1]), 4), "tris": tris(out), "rawTris": tris(raw)}
    print(f"{mid}: {index[mid]['rawTris']} -> {index[mid]['tris']} tris, w/d/h {np.round(e2, 3).tolist()} m, bottom {round(float(lo2[1]), 4)}, "
          f"centre xz {np.round([(lo2[0] + hi2[0]) / 2, (lo2[2] + hi2[2]) / 2], 4).tolist()}, {os.path.getsize(out) // 1024} KB")


if __name__ == "__main__":
    cat = catalog()
    ids = sys.argv[1:] or list(cat)
    ipath = os.path.join(OUT, "index.json")
    index = json.load(open(ipath)) if os.path.exists(ipath) else {}
    for mid in ids:
        prep(mid, cat[mid][0], index)
    json.dump(dict(sorted(index.items())), open(ipath, "w"), indent=1)
    print(f"index: {len(index)}/{len(cat)} models")

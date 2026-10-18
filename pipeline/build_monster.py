"""Blender script: turn the high-poly "scary Verity" GLB into a game-ready skinned VMSH.

  blender -b --python pipeline/build_monster.py -- <in.glb> <out.vmesh> [targetTris]

Steps: import -> two-pass decimation (face kept dense) -> cut the mouth open along the line between the
tooth rows -> bone-heat skin weights + analytic jaw -> export with normals borrowed from the high-poly.
Model space everywhere below: +Y up, facing +Z (Blender is Z up, facing -Y).
"""
import math
import os
import sys

import bmesh
import bpy
import numpy as np
from mathutils import Vector, kdtree

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vmesh  # noqa: E402

argv = sys.argv[sys.argv.index('--') + 1:]
SRC, OUT = argv[0], argv[1]
TARGET = int(argv[2]) if len(argv) > 2 else 26000

# Mouth: the dark line between the upper and lower teeth is y = Y0 + K*x^2, for |x| < XC, on the front of the head.
Y0, K, XC = 0.753, 14.8, 0.047
JAW_HINGE = (0.0, 0.795, 0.0)

# name, parent, head, tail   (left = +X)
SKELETON = [
    ('root', None, (0, 0.10, -0.010), (0, 0.22, -0.015)),
    ('spine1', 'root', (0, 0.22, -0.015), (0, 0.40, -0.020)),
    ('spine2', 'spine1', (0, 0.40, -0.020), (0, 0.60, -0.045)),
    ('neck', 'spine2', (0, 0.60, -0.045), (0, 0.715, -0.020)),
    ('head', 'neck', (0, 0.715, -0.020), (0, 0.95, 0.010)),
]
for s, side in ((1, 'l'), (-1, 'r')):
    SKELETON += [
        (f'{side}_arm', 'spine2', (s * 0.135, 0.535, -0.055), (s * 0.228, -0.06, -0.070)),
        (f'{side}_forearm', f'{side}_arm', (s * 0.228, -0.06, -0.070), (s * 0.265, -0.40, -0.020)),
        (f'{side}_hand', f'{side}_forearm', (s * 0.265, -0.40, -0.020), (s * 0.258, -0.47, -0.012)),
        (f'{side}_fingers', f'{side}_hand', (s * 0.258, -0.47, -0.012), (s * 0.250, -0.57, -0.010)),
        (f'{side}_thigh', 'root', (s * 0.060, 0.08, 0.000), (s * 0.092, -0.44, -0.027)),
        (f'{side}_shin', f'{side}_thigh', (s * 0.092, -0.44, -0.027), (s * 0.118, -0.885, -0.055)),
        (f'{side}_foot', f'{side}_shin', (s * 0.118, -0.885, -0.055), (s * 0.140, -0.945, 0.100)),
    ]
# Bones without heat weights: the jaw (analytic) and two attachment points for the eye glints.
EXTRA = [('jaw', 'head', JAW_HINGE), ('l_eye', 'head', (0.031, 0.842, 0.0)), ('r_eye', 'head', (-0.031, 0.842, 0.0))]


def to_model(a):
    a = np.asarray(a, 'f4').reshape(-1, 3)
    return np.stack([a[:, 0], a[:, 2], -a[:, 1]], 1)


def to_blender(p):
    return Vector((p[0], -p[2], p[1]))


def smooth01(t):
    t = np.clip(t, 0.0, 1.0)
    return t * t * (3 - 2 * t)


def mesh_co(me):
    a = np.empty(len(me.vertices) * 3, 'f4'); me.vertices.foreach_get('co', a)
    return a.reshape(-1, 3)


# ---------------------------------------------------------------- import
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=SRC, merge_vertices=True)
scene = bpy.context.scene
obj = next(o for o in scene.objects if o.type == 'MESH')
for o in scene.objects:
    o.select_set(o is obj)
bpy.context.view_layer.objects.active = obj
bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
me = obj.data
bm = bmesh.new(); bm.from_mesh(me)      # weld UV-seam duplicates so decimation and bone heat see one closed surface
bmesh.ops.remove_doubles(bm, verts=bm.verts[:], dist=1e-6)
bm.to_mesh(me); bm.free(); me.update()
print(f'[monster] imported {len(me.vertices)} verts, {len(me.polygons)} tris')

hi_co = mesh_co(me)
hi_no = np.empty(len(me.vertices) * 3, 'f4'); me.vertices.foreach_get('normal', hi_no); hi_no = hi_no.reshape(-1, 3)
tree = kdtree.KDTree(len(hi_co))
for i, c in enumerate(hi_co):
    tree.insert(c, i)
tree.balance()


# ---------------------------------------------------------------- decimate
def decimate(ratio, group=None):
    mod = obj.modifiers.new('dec', 'DECIMATE')
    mod.decimate_type = 'COLLAPSE'; mod.ratio = ratio; mod.use_collapse_triangulate = True
    if group:
        mod.vertex_group = group; mod.vertex_group_factor = 1.0
    bpy.ops.object.modifier_apply(modifier=mod.name)


decimate(90000 / len(me.polygons))
P = to_model(mesh_co(me))
# weight 1 = free to collapse, 0 = protected (the face: mouth, eye sockets)
face = smooth01((P[:, 1] - 0.715) / 0.02) * smooth01((P[:, 2] - 0.0) / 0.03) * (P[:, 1] < 0.90)
vg = obj.vertex_groups.new(name='decim')
for i, w in enumerate(1.0 - face):
    vg.add([i], float(w), 'REPLACE')
decimate(TARGET / len(me.polygons), 'decim')
if obj.vertex_groups.get('decim'):
    obj.vertex_groups.remove(obj.vertex_groups['decim'])
print(f'[monster] decimated to {len(me.vertices)} verts, {len(me.polygons)} tris')

# ---------------------------------------------------------------- mouth cut
bm = bmesh.new(); bm.from_mesh(me)
tag = bm.verts.layers.int.new('cut')
for v in bm.verts:                      # straighten the smile so one plane cuts along it
    v.co.z -= K * v.co.x * v.co.x
faces = [f for f in bm.faces
         if abs((c := f.calc_center_median()).x) < XC + 0.004 and -c.y > 0.03 and abs(c.z - Y0) < 0.04]
geom = set(faces)
for f in faces:
    geom.update(f.edges); geom.update(f.verts)
res = bmesh.ops.bisect_plane(bm, geom=list(geom), dist=1e-6, plane_co=Vector((0, 0, Y0)), plane_no=Vector((0, 0, 1)),
                             clear_inner=False, clear_outer=False)
cut_edges = [e for e in res['geom_cut'] if isinstance(e, bmesh.types.BMEdge)]
cut_verts = [v for v in res['geom_cut'] if isinstance(v, bmesh.types.BMVert)]
for v in cut_verts:
    v[tag] = 9
key = lambda co: (round(co.x, 6), round(co.y, 6), round(co.z, 6))  # noqa: E731
cut_pairs = [(key(e.verts[0].co), key(e.verts[1].co)) for e in cut_edges]   # warped space keys
bmesh.ops.split_edges(bm, edges=cut_edges)
bm.verts.ensure_lookup_table()
for v in bm.verts:
    if v[tag] == 9:
        above = [f.calc_center_median().z > Y0 for f in v.link_faces]
        v[tag] = 1 if all(above) else 2 if not any(above) else 3      # upper lip / lower lip / end of the slit
cut_keys = {}
for v in bm.verts:
    if v[tag]:
        cut_keys.setdefault(key(v.co), {})[v[tag]] = v.index
for v in bm.verts:
    v.co.z += K * v.co.x * v.co.x
bmesh.ops.triangulate(bm, faces=bm.faces[:])
bm.to_mesh(me); me.update()
tags = np.array([v[tag] for v in bm.verts], 'i4')
bm.free()
print(f'[monster] mouth cut: {len(cut_pairs)} edges, tags {np.bincount(tags, minlength=4).tolist()}')

# ---------------------------------------------------------------- skin weights (bone heat)
arm = bpy.data.armatures.new('rig'); rig = bpy.data.objects.new('rig', arm)
scene.collection.objects.link(rig)
bpy.context.view_layer.objects.active = rig
bpy.ops.object.mode_set(mode='EDIT')
for name, parent, head, tail in SKELETON:
    eb = arm.edit_bones.new(name); eb.head = to_blender(head); eb.tail = to_blender(tail)
    if parent:
        eb.parent = arm.edit_bones[parent]
bpy.ops.object.mode_set(mode='OBJECT')
for o in scene.objects:
    o.select_set(o in (obj, rig))
bpy.context.view_layer.objects.active = rig
bpy.ops.object.parent_set(type='ARMATURE_AUTO')

names = [s[0] for s in SKELETON] + [e[0] for e in EXTRA]
P = to_model(mesh_co(me)); nv = len(P)
W = np.zeros((nv, len(names)), 'f4')
gmap = {g.index: names.index(g.name) for g in obj.vertex_groups if g.name in names}
for v in me.vertices:
    for g in v.groups:
        if g.group in gmap:
            W[v.index, gmap[g.group]] = g.weight
unweighted = W.sum(1) < 1e-4
print(f'[monster] bone heat: {int(unweighted.sum())} of {nv} verts unweighted')
if unweighted.any():                    # fallback: nearest bone segment
    idx = np.where(unweighted)[0]; best = np.full(len(idx), 1e9); who = np.zeros(len(idx), 'i4')
    for b, (_, _, head, tail) in enumerate(SKELETON):
        a, c = np.array(head, 'f4'), np.array(tail, 'f4'); d = c - a
        t = np.clip(((P[idx] - a) @ d) / (d @ d), 0, 1)
        dist = np.linalg.norm(P[idx] - (a + t[:, None] * d), axis=1)
        who = np.where(dist < best, b, who); best = np.minimum(best, dist)
    W[idx, who] = 1.0
W /= W.sum(1, keepdims=True)

# analytic jaw
x, y, z = P.T
yw = y - K * x * x
F = smooth01((z - 0.005) / 0.045) * smooth01((y - 0.698) / 0.024)
corner = 1.0 - smooth01((np.abs(x) - (XC - 0.012)) / 0.012)      # 1 inside the mouth, 0 past its corners
below = smooth01((Y0 - yw) / 0.02)                               # soft ramp on the cheeks
wj = F * np.maximum(corner * (yw < Y0), below)
wj = np.where(tags == 2, F * corner, wj)
wj = np.where((tags == 1) | (tags == 3) | ((yw >= Y0) & (tags != 2)) | (y < 0.69), 0.0, wj)
W *= (1.0 - wj)[:, None]
W[:, names.index('jaw')] = wj
print(f'[monster] jaw verts (w>0.5): {int((wj > 0.5).sum())}')

# keep the 4 strongest bones per vertex
order = np.argsort(-W, axis=1)[:, :4]
BW = np.take_along_axis(W, order, 1); BW /= BW.sum(1, keepdims=True)
BI = order.astype('u1')

# ---------------------------------------------------------------- export
me.calc_loop_triangles()
uv_layer = me.uv_layers.active.data
co_bl = mesh_co(me)
nrm = np.zeros((nv, 3), 'f4')
for i, c in enumerate(co_bl):
    nrm[i] = hi_no[tree.find(c)[1]]
nrm = to_model(nrm)

out_index = {}; o_pos, o_nrm, o_uv, o_bi, o_bw, o_rgba, tris = [], [], [], [], [], [], []
first_out = {}


def emit(vi, uv, rgba=(255, 255, 255, 255), pos=None, normal=None, bi=None, bw=None):
    o_pos.append(P[vi] if pos is None else pos); o_nrm.append(nrm[vi] if normal is None else normal)
    o_uv.append(uv); o_rgba.append(rgba)
    o_bi.append(BI[vi] if bi is None else bi); o_bw.append(BW[vi] if bw is None else bw)
    return len(o_pos) - 1


for lt in me.loop_triangles:
    tri = []
    for li in lt.loops:
        vi = me.loops[li].vertex_index; u, v = uv_layer[li].uv
        k = (vi, round(u, 5), round(v, 5))
        if k not in out_index:
            out_index[k] = emit(vi, (u, 1.0 - v)); first_out.setdefault(vi, out_index[k])
        tri.append(out_index[k])
    tris.append(tri)
body_tris = len(tris)

# mouth cavity: a dark pocket hanging between the upper and lower lip of the cut
RED, BLACK, IN = (95, 14, 14, 255), (0, 0, 0, 255), np.array([0, 0, 1], 'f4')


def blend(va, vb):
    w = np.zeros(len(names), 'f4')
    for vtx in (va, vb):
        np.add.at(w, BI[vtx], BW[vtx] * 0.5)
    top = np.argsort(-w)[:4]
    return top.astype('u1'), w[top] / w[top].sum()


cav = {}
for k, d in cut_keys.items():
    if 1 in d and 2 in d:
        up, lo = d[1], d[2]; uv = o_uv[first_out[up]]
        bi, bw = blend(up, lo)
        cav[k] = (emit(up, uv, RED, normal=IN), emit(lo, uv, RED, normal=IN),
                  emit(up, uv, BLACK, pos=P[up] + np.array([0, -0.004, -0.04], 'f4'), normal=IN, bi=bi, bw=bw))
    else:
        e = next(iter(d.values())); idx = emit(e, o_uv[first_out[e]], RED, normal=IN)
        cav[k] = (idx, idx, idx)
for ka, kb in cut_pairs:
    if ka not in cav or kb not in cav:
        continue
    (ua, la, ma), (ub, lb, mb) = cav[ka], cav[kb]
    for t in ((ua, ub, mb), (ua, mb, ma), (ma, mb, lb), (ma, lb, la)):
        if len(set(t)) == 3:
            tris.append(list(t))

bones = [(n, names.index(p) if p else -1, h) for n, p, h, _ in SKELETON] + [(n, names.index(p), h) for n, p, h in EXTRA]
# put the eye attachment points on the actual surface of the sockets
for i, (n, p, h) in enumerate(bones):
    if n.endswith('_eye'):
        near = (np.abs(P[:, 0] - h[0]) < 0.006) & (np.abs(P[:, 1] - h[1]) < 0.006) & (P[:, 2] > 0.03)
        bones[i] = (n, p, (h[0], h[1], float(P[near, 2].min()) if near.any() else 0.09))

vmesh.write(OUT, o_pos, o_nrm, o_uv, tris, o_rgba, o_bi, o_bw, bones)
head_tris = sum(1 for t in tris[:body_tris] if o_pos[t[0]][1] > 0.715)
print(f'[monster] wrote {OUT}: {len(o_pos)} verts, {len(tris)} tris ({head_tris} in the head, '
      f'{len(tris) - body_tris} cavity), {len(bones)} bones')

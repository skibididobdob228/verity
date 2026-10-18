"""VMSH: the tiny binary mesh format the Verity mod loads (little-endian).

header : 'VMSH', u32 version(1), u32 vertexCount, u32 triCount, u32 boneCount, u32 flags(bit0 = skinned)
arrays : f32 pos[vc*3], f32 nrm[vc*3], f32 uv[vc*2], u8 rgba[vc*4],
         (skinned) u8 boneIdx[vc*4], f32 boneWeight[vc*4],
         u32 index[tc*3]
bones  : boneCount x { u8 nameLen, name utf8, i32 parent, f32 head[3] }   (rest pose = translation only)

Model space: +Y up, character faces +Z, UV origin top-left (same as glTF and Minecraft).
"""
import struct
import numpy as np


def write(path, pos, nrm, uv, tris, rgba=None, bone_idx=None, bone_w=None, bones=()):
    pos = np.asarray(pos, '<f4').reshape(-1, 3); vc = len(pos)
    nrm = np.asarray(nrm, '<f4').reshape(vc, 3); uv = np.asarray(uv, '<f4').reshape(vc, 2)
    tris = np.asarray(tris, '<u4').reshape(-1, 3)
    rgba = np.full((vc, 4), 255, 'u1') if rgba is None else np.asarray(rgba, 'u1').reshape(vc, 4)
    skinned = bone_idx is not None
    with open(path, 'wb') as f:
        f.write(b'VMSH'); f.write(struct.pack('<5I', 1, vc, len(tris), len(bones), 1 if skinned else 0))
        f.write(pos.tobytes()); f.write(nrm.tobytes()); f.write(uv.tobytes()); f.write(rgba.tobytes())
        if skinned:
            f.write(np.asarray(bone_idx, 'u1').reshape(vc, 4).tobytes())
            f.write(np.asarray(bone_w, '<f4').reshape(vc, 4).tobytes())
        f.write(tris.tobytes())
        for name, parent, head in bones:
            nb = name.encode(); f.write(struct.pack('<B', len(nb))); f.write(nb)
            f.write(struct.pack('<i3f', parent, *head))


def read(path):
    d = open(path, 'rb').read(); assert d[:4] == b'VMSH'
    ver, vc, tc, bc, flags = struct.unpack_from('<5I', d, 4); o = 24
    def take(dt, n, cols):
        nonlocal o
        a = np.frombuffer(d, dt, n * cols, o).reshape(n, cols); o += a.nbytes; return a.copy()
    m = dict(pos=take('<f4', vc, 3), nrm=take('<f4', vc, 3), uv=take('<f4', vc, 2), rgba=take('u1', vc, 4))
    if flags & 1:
        m['bone_idx'] = take('u1', vc, 4); m['bone_w'] = take('<f4', vc, 4)
    m['tris'] = take('<u4', tc, 3); bones = []
    for _ in range(bc):
        n = d[o]; o += 1; name = d[o:o + n].decode(); o += n
        parent, hx, hy, hz = struct.unpack_from('<i3f', d, o); o += 16
        bones.append((name, parent, (hx, hy, hz)))
    m['bones'] = bones
    return m

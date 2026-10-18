"""Minimal GLB reader (first primitive of the first mesh) - numpy only."""
import json
import struct
import numpy as np

_CT = {5120: np.int8, 5121: np.uint8, 5122: np.int16, 5123: np.uint16, 5125: np.uint32, 5126: np.float32}
_NC = {'SCALAR': 1, 'VEC2': 2, 'VEC3': 3, 'VEC4': 4}


def load(path):
    with open(path, 'rb') as f:
        f.read(12)
        n, _ = struct.unpack('<II', f.read(8)); js = json.loads(f.read(n))
        n, _ = struct.unpack('<II', f.read(8)); blob = f.read(n)

    def acc(i):
        a = js['accessors'][i]; bv = js['bufferViews'][a['bufferView']]
        off = bv.get('byteOffset', 0) + a.get('byteOffset', 0); nc = _NC[a['type']]; dt = np.dtype(_CT[a['componentType']])
        stride = bv.get('byteStride') or dt.itemsize * nc
        return np.array(np.ndarray((a['count'], nc), dt, blob, off, (stride, dt.itemsize)))

    pr = js['meshes'][0]['primitives'][0]
    images = []
    for im in js.get('images', []):
        bv = js['bufferViews'][im['bufferView']]; o = bv.get('byteOffset', 0)
        images.append(blob[o:o + bv['byteLength']])
    base = js['textures'][js['materials'][0]['pbrMetallicRoughness']['baseColorTexture']['index']]['source']
    return dict(pos=acc(pr['attributes']['POSITION']).astype('f4'), nrm=acc(pr['attributes']['NORMAL']).astype('f4'),
                uv=acc(pr['attributes']['TEXCOORD_0']).astype('f4'), tris=acc(pr['indices']).astype('u4').reshape(-1, 3),
                images=images, base_image=images[base])

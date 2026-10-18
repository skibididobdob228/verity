"""Reference skinning + software preview of a VMSH (numpy only). Mirrors what the mod does in Java.

  python3 pipeline/preview.py <mesh.vmesh> <texture.png> <out.png>
"""
import sys
import numpy as np
from PIL import Image
import vmesh


def rot(axis, deg):
    a = np.radians(deg); c, s = np.cos(a), np.sin(a)
    return {'x': np.array([[1, 0, 0], [0, c, -s], [0, s, c]]), 'y': np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]]),
            'z': np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])}[axis].astype('f4')


def skin(m, pose):
    """pose: {bone: 3x3 local rotation}. Returns posed positions and normals."""
    bones = m['bones']; n = len(bones); Rw = [None] * n; Tw = [None] * n
    for i, (name, parent, head) in enumerate(bones):
        h = np.array(head, 'f4'); R = pose.get(name, np.eye(3, dtype='f4'))
        if parent < 0:
            Rw[i], Tw[i] = R, h
        else:
            hp = np.array(bones[parent][2], 'f4')
            Rw[i] = Rw[parent] @ R; Tw[i] = Tw[parent] + Rw[parent] @ (h - hp)
    P = np.zeros_like(m['pos']); N = np.zeros_like(m['nrm'])
    for k in range(4):
        bi = m['bone_idx'][:, k]; w = m['bone_w'][:, k:k + 1]
        Rk = np.stack(Rw)[bi]; Tk = np.stack(Tw)[bi]; Hk = np.array([b[2] for b in bones], 'f4')[bi]
        P += w * (np.einsum('nij,nj->ni', Rk, m['pos'] - Hk) + Tk)
        N += w * np.einsum('nij,nj->ni', Rk, m['nrm'])
    return P, N / np.maximum(np.linalg.norm(N, axis=1, keepdims=True), 1e-6)


def render(P, N, m, tex, yaw=0.0, center=(0, 0, 0), half=1.0, size=600, samples=2_500_000):
    c, s = np.cos(yaw), np.sin(yaw); R = np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]], 'f4')
    Q = (P - np.array(center, 'f4')) @ R.T; Nn = N @ R.T; T = m['tris'].astype(np.int64)
    a, b, d = Q[T[:, 0]], Q[T[:, 1]], Q[T[:, 2]]
    area = 0.5 * np.abs((b[:, 0] - a[:, 0]) * (d[:, 1] - a[:, 1]) - (d[:, 0] - a[:, 0]) * (b[:, 1] - a[:, 1])) + 1e-10
    rng = np.random.default_rng(0); t = rng.choice(len(T), samples, p=area / area.sum())
    r1 = np.sqrt(rng.random(samples, dtype='f4')); r2 = rng.random(samples, dtype='f4')
    w = np.stack([1 - r1, r1 * (1 - r2), r1 * r2], 1)[:, :, None]
    pos = (Q[T[t]] * w).sum(1); uv = (m['uv'][T[t]] * w).sum(1); nz = (Nn[T[t], 2:3] * w).sum(1)[:, 0]
    col = (m['rgba'][T[t], :3].astype('f4') * w).sum(1) / 255.0
    px = ((pos[:, 0] / half * 0.5 + 0.5) * (size - 1)).round().astype(int); py = ((-pos[:, 1] / half * 0.5 + 0.5) * (size - 1)).round().astype(int)
    ok = (px >= 0) & (px < size) & (py >= 0) & (py < size); px, py, pos, uv, nz, col = px[ok], py[ok], pos[ok], uv[ok], nz[ok], col[ok]
    th, tw = tex.shape[:2]
    rgb = tex[np.clip(uv[:, 1] * th, 0, th - 1).astype(int), np.clip(uv[:, 0] * tw, 0, tw - 1).astype(int), :3].astype('f4') * col
    rgb *= (0.5 + 0.5 * np.clip(np.abs(nz), 0, 1))[:, None]
    o = np.argsort(pos[:, 2], kind='stable'); img = np.full((size, size, 3), 34, 'f4'); img[py[o], px[o]] = rgb[o]
    return img.astype('u1')


if __name__ == '__main__':
    m = vmesh.read(sys.argv[1]); tex = np.array(Image.open(sys.argv[2]).convert('RGB'))
    print(len(m['pos']), 'verts', len(m['tris']), 'tris', [b[0] for b in m['bones']])
    walk = {'l_thigh': rot('x', -40), 'l_shin': rot('x', 35), 'r_thigh': rot('x', 30), 'r_shin': rot('x', 55),
            'l_arm': rot('x', 35), 'r_arm': rot('x', -30), 'l_forearm': rot('x', -30), 'r_forearm': rot('x', -45),
            'spine1': rot('x', 12), 'spine2': rot('x', 10), 'neck': rot('x', -10), 'head': rot('y', 25) @ rot('x', -8)}
    reach = {'l_arm': rot('x', -95) @ rot('z', 8), 'r_arm': rot('x', -80) @ rot('z', -20), 'l_forearm': rot('x', -25),
             'r_forearm': rot('x', -50), 'l_hand': rot('x', -30), 'r_hand': rot('x', -30), 'l_fingers': rot('x', -50),
             'r_fingers': rot('x', -50), 'spine2': rot('x', 20), 'neck': rot('x', 15), 'head': rot('x', -20) @ rot('z', 18),
             'jaw': rot('x', 32), 'l_thigh': rot('x', -15), 'l_shin': rot('x', 30), 'r_thigh': rot('x', 10)}
    tiles = []
    for pose, yaw in (({}, 0.0), (walk, -0.9), (reach, -0.5), (reach, -np.pi / 2)):
        P, N = skin(m, pose); tiles.append(render(P, N, m, tex, yaw, (0, 0, 0), 1.05))
    row1 = np.concatenate(tiles, 1); tiles = []
    for jaw, yaw in ((0, 0.0), (18, 0.0), (38, 0.0), (38, -0.9)):
        P, N = skin(m, {'jaw': rot('x', jaw)}); tiles.append(render(P, N, m, tex, yaw, (0, 0.82, 0.02), 0.14, samples=1_500_000))
    Image.fromarray(np.concatenate([row1, np.concatenate(tiles, 1)], 0)).save(sys.argv[3])

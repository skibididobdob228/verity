package dev.verity.client.mesh;

import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Poses a skinned {@link VMesh} on the CPU. The rest pose of every bone is a pure translation, so a pose is
 * just one local rotation per bone (plus an offset for the root).
 */
public final class Skeleton {
    public final VMesh mesh;
    public final Quaternionf[] local;
    public final Vector3f rootOffset = new Vector3f();
    /** Skinned positions and normals, valid after {@link #skin()}. */
    public final float[] pos, nrm;

    private final int n;
    private final Matrix3f[] worldRot;
    private final Vector3f[] worldPos;
    /** Per bone: 3x3 rotation (row-major) followed by the translation that maps rest-space points to posed space. */
    private final float[] m;
    private final Vector3f tmp = new Vector3f();

    public Skeleton(VMesh mesh) {
        this.mesh = mesh;
        n = mesh.bones.length;
        local = new Quaternionf[n]; worldRot = new Matrix3f[n]; worldPos = new Vector3f[n];
        for (int i = 0; i < n; i++) {
            local[i] = new Quaternionf(); worldRot[i] = new Matrix3f(); worldPos[i] = new Vector3f();
        }
        m = new float[n * 12];
        pos = new float[mesh.vertexCount * 3];
        nrm = new float[mesh.vertexCount * 3];
    }

    public void reset() {
        for (Quaternionf q : local) q.identity();
        rootOffset.zero();
    }

    /** Local rotation of a bone, as XYZ euler angles in degrees. */
    public Quaternionf rot(int bone, float xDeg, float yDeg, float zDeg) {
        return local[bone].rotationXYZ(xDeg * DEG, yDeg * DEG, zDeg * DEG);
    }

    private static final float DEG = (float) (Math.PI / 180.0);

    public void solve() {
        for (int i = 0; i < n; i++) {
            VMesh.Bone b = mesh.bones[i];
            if (b.parent() < 0) {
                worldRot[i].set(local[i]);
                worldPos[i].set(b.hx(), b.hy(), b.hz()).add(rootOffset);
            } else {
                VMesh.Bone p = mesh.bones[b.parent()];
                worldRot[i].set(worldRot[b.parent()]).rotate(local[i]);
                worldRot[b.parent()].transform(tmp.set(b.hx() - p.hx(), b.hy() - p.hy(), b.hz() - p.hz()));
                worldPos[i].set(worldPos[b.parent()]).add(tmp);
            }
            Matrix3f r = worldRot[i];
            r.transform(tmp.set(b.hx(), b.hy(), b.hz()));
            int o = i * 12;
            m[o] = r.m00(); m[o + 1] = r.m10(); m[o + 2] = r.m20();
            m[o + 3] = r.m01(); m[o + 4] = r.m11(); m[o + 5] = r.m21();
            m[o + 6] = r.m02(); m[o + 7] = r.m12(); m[o + 8] = r.m22();
            m[o + 9] = worldPos[i].x - tmp.x; m[o + 10] = worldPos[i].y - tmp.y; m[o + 11] = worldPos[i].z - tmp.z;
        }
    }

    /** Where a rest-pose point rigidly attached to {@code bone} ends up in the current pose. */
    public Vector3f point(int bone, float x, float y, float z, Vector3f dest) {
        int o = bone * 12;
        return dest.set(m[o] * x + m[o + 1] * y + m[o + 2] * z + m[o + 9],
                m[o + 3] * x + m[o + 4] * y + m[o + 5] * z + m[o + 10],
                m[o + 6] * x + m[o + 7] * y + m[o + 8] * z + m[o + 11]);
    }

    public Vector3f head(int bone, Vector3f dest) {
        return dest.set(worldPos[bone]);
    }

    public void skin() {
        float[] sp = mesh.pos, sn = mesh.nrm, w = mesh.boneW;
        byte[] bi = mesh.boneIdx;
        for (int v = 0, p = 0, k = 0; v < mesh.vertexCount; v++, p += 3, k += 4) {
            float x = sp[p], y = sp[p + 1], z = sp[p + 2];
            float nx = sn[p], ny = sn[p + 1], nz = sn[p + 2];
            float ox = 0, oy = 0, oz = 0, onx = 0, ony = 0, onz = 0;
            for (int j = 0; j < 4; j++) {
                float wt = w[k + j];
                if (wt <= 0f) continue;
                int o = (bi[k + j] & 0xFF) * 12;
                ox += wt * (m[o] * x + m[o + 1] * y + m[o + 2] * z + m[o + 9]);
                oy += wt * (m[o + 3] * x + m[o + 4] * y + m[o + 5] * z + m[o + 10]);
                oz += wt * (m[o + 6] * x + m[o + 7] * y + m[o + 8] * z + m[o + 11]);
                onx += wt * (m[o] * nx + m[o + 1] * ny + m[o + 2] * nz);
                ony += wt * (m[o + 3] * nx + m[o + 4] * ny + m[o + 5] * nz);
                onz += wt * (m[o + 6] * nx + m[o + 7] * ny + m[o + 8] * nz);
            }
            pos[p] = ox; pos[p + 1] = oy; pos[p + 2] = oz;
            nrm[p] = onx; nrm[p + 1] = ony; nrm[p + 2] = onz;
        }
    }
}

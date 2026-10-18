package dev.verity.client.mesh;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/** Pushes a {@link VMesh} into a TRIANGLES vertex consumer using the standard entity vertex format. */
public final class MeshEmitter {
    private static float[] tp = new float[0], tn = new float[0];

    /**
     * @param pos   positions to draw (the mesh's own, or a {@link Skeleton}'s skinned copy)
     * @param argb  tint multiplied with the per-vertex colour
     * @param jitter random per-vertex displacement in model units (glitch effect), 0 for none
     */
    public static void emit(VMesh mesh, float[] pos, float[] nrm, PoseStack.Pose pose, VertexConsumer out,
                            int light, int overlay, int argb, float jitter, int jitterSeed) {
        int vc = mesh.vertexCount;
        if (tp.length < vc * 3) {
            tp = new float[vc * 3]; tn = new float[vc * 3];
        }
        Matrix4f pm = pose.pose();
        Matrix3f nm = pose.normal();
        float m00 = pm.m00(), m10 = pm.m10(), m20 = pm.m20(), m30 = pm.m30();
        float m01 = pm.m01(), m11 = pm.m11(), m21 = pm.m21(), m31 = pm.m31();
        float m02 = pm.m02(), m12 = pm.m12(), m22 = pm.m22(), m32 = pm.m32();
        float n00 = nm.m00(), n10 = nm.m10(), n20 = nm.m20();
        float n01 = nm.m01(), n11 = nm.m11(), n21 = nm.m21();
        float n02 = nm.m02(), n12 = nm.m12(), n22 = nm.m22();
        int h = jitterSeed * 0x9E3779B9;
        for (int i = 0; i < vc * 3; i += 3) {
            float x = pos[i], y = pos[i + 1], z = pos[i + 2];
            if (jitter > 0f) {
                h = h * 1664525 + 1013904223;
                // displace whole horizontal slices so the body tears like a corrupted video frame
                int band = (int) Math.floor(y * 14f) * 0x45D9F3B + jitterSeed;
                band ^= band >>> 15; band *= 0x2C1B3C6D; band ^= band >>> 12;
                if ((band & 7) == 0) x += ((band >>> 8 & 0xFF) / 255f - 0.5f) * jitter * 2.2f;
                x += ((h >>> 9 & 0xFF) / 255f - 0.5f) * jitter * 0.25f;
            }
            tp[i] = m00 * x + m10 * y + m20 * z + m30;
            tp[i + 1] = m01 * x + m11 * y + m21 * z + m31;
            tp[i + 2] = m02 * x + m12 * y + m22 * z + m32;
            float nx = nrm[i], ny = nrm[i + 1], nz = nrm[i + 2];
            float tx = n00 * nx + n10 * ny + n20 * nz, ty = n01 * nx + n11 * ny + n21 * nz, tz = n02 * nx + n12 * ny + n22 * nz;
            float inv = 1f / (float) Math.sqrt(Math.max(tx * tx + ty * ty + tz * tz, 1e-12f));
            tn[i] = tx * inv; tn[i + 1] = ty * inv; tn[i + 2] = tz * inv;
        }
        int ta = argb >>> 24, tr = argb >> 16 & 0xFF, tg = argb >> 8 & 0xFF, tb = argb & 0xFF;
        float[] uv = mesh.uv;
        byte[] col = mesh.rgba;
        for (int v : mesh.idx) {
            int p = v * 3, c = v * 4;
            int r = (col[c] & 0xFF) * tr / 255, g = (col[c + 1] & 0xFF) * tg / 255, b = (col[c + 2] & 0xFF) * tb / 255, a = (col[c + 3] & 0xFF) * ta / 255;
            out.addVertex(tp[p], tp[p + 1], tp[p + 2], a << 24 | r << 16 | g << 8 | b, uv[v * 2], uv[v * 2 + 1], overlay, light,
                    tn[p], tn[p + 1], tn[p + 2]);
        }
    }

    private MeshEmitter() {}
}

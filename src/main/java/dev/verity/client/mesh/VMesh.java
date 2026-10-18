package dev.verity.client.mesh;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

/** A triangle mesh in the mod's VMSH format (see pipeline/vmesh.py). Model space: +Y up, facing +Z. */
public final class VMesh {
    public record Bone(String name, int parent, float hx, float hy, float hz) {}

    public final int vertexCount, triCount;
    public final float[] pos, nrm, uv;
    public final byte[] rgba;
    /** Null for rigid meshes. */
    public final byte[] boneIdx;
    public final float[] boneW;
    public final int[] idx;
    public final Bone[] bones;

    private VMesh(ByteBuffer b) throws IOException {
        if (b.get() != 'V' || b.get() != 'M' || b.get() != 'S' || b.get() != 'H') throw new IOException("not a VMSH file");
        int version = b.getInt();
        if (version != 1) throw new IOException("unsupported VMSH version " + version);
        vertexCount = b.getInt(); triCount = b.getInt();
        int boneCount = b.getInt(); boolean skinned = (b.getInt() & 1) != 0;
        pos = floats(b, vertexCount * 3); nrm = floats(b, vertexCount * 3); uv = floats(b, vertexCount * 2);
        rgba = new byte[vertexCount * 4]; b.get(rgba);
        if (skinned) {
            boneIdx = new byte[vertexCount * 4]; b.get(boneIdx);
            boneW = floats(b, vertexCount * 4);
        } else {
            boneIdx = null; boneW = null;
        }
        idx = new int[triCount * 3];
        b.asIntBuffer().get(idx); b.position(b.position() + idx.length * 4);
        bones = new Bone[boneCount];
        for (int i = 0; i < boneCount; i++) {
            byte[] name = new byte[b.get() & 0xFF]; b.get(name);
            bones[i] = new Bone(new String(name, StandardCharsets.UTF_8), b.getInt(), b.getFloat(), b.getFloat(), b.getFloat());
        }
    }

    private static float[] floats(ByteBuffer b, int n) {
        float[] a = new float[n];
        b.asFloatBuffer().get(a); b.position(b.position() + n * 4);
        return a;
    }

    public static VMesh load(ResourceManager resources, ResourceLocation location) {
        try (InputStream in = resources.open(location)) {
            return new VMesh(ByteBuffer.wrap(in.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load mesh " + location, e);
        }
    }

    public int bone(String name) {
        for (int i = 0; i < bones.length; i++) {
            if (bones[i].name().equals(name)) return i;
        }
        throw new IllegalArgumentException("No bone " + name);
    }
}

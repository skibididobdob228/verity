package dev.verity.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.verity.VerityMod;
import dev.verity.client.mesh.MeshEmitter;
import dev.verity.client.mesh.Skeleton;
import dev.verity.client.mesh.VMesh;
import dev.verity.entity.VerityMonster;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class MonsterRenderer extends EntityRenderer<VerityMonster> {
    private static final ResourceLocation TEXTURE = VerityMod.id("textures/entity/monster.png");

    private static final ResourceLocation GLINT = VerityMod.id("dynamic/glint");
    private static boolean glintReady;

    private final Skeleton skeleton;
    private final int[] eyes;
    private final Vector3f tmp = new Vector3f();

    public MonsterRenderer(EntityRendererProvider.Context context) {
        super(context);
        VMesh mesh = VMesh.load(context.getResourceManager(), VerityMod.id("meshes/monster.vmesh"));
        skeleton = new Skeleton(mesh);
        eyes = new int[] {mesh.bone("l_eye"), mesh.bone("r_eye")};
        shadowRadius = 0.9f;
    }

    @Override
    public void render(VerityMonster monster, float entityYaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        float bodyYaw = Mth.rotLerp(partialTick, monster.yBodyRotO, monster.yBodyRot);
        float headYaw = Mth.wrapDegrees(Mth.rotLerp(partialTick, monster.yHeadRotO, monster.yHeadRot) - bodyYaw);
        float headPitch = Mth.lerp(partialTick, monster.xRotO, monster.getXRot());

        MonsterAnim anim = MonsterAnim.of(monster);
        // model yaw is mirrored relative to Minecraft's, hence the sign flips
        anim.apply(monster, skeleton, partialTick, -headYaw, headPitch);
        skeleton.skin();

        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(-bodyYaw));
        float s = MonsterAnim.SCALE * anim.scale;
        pose.scale(s, s, s);
        pose.translate(0f, 0.95f, 0f);
        MeshEmitter.emit(skeleton.mesh, skeleton.pos, skeleton.nrm, pose.last(), buffers.getBuffer(VerityRenderTypes.mesh(TEXTURE)),
                light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF, anim.jitter, anim.seed);
        // no lights in the sockets: the eyes are just holes, and in the dark there is nothing to see of him at all
        pose.popPose();
        super.render(monster, entityYaw, partialTick, pose, buffers, light);
    }

    /** A pinprick of light at the bottom of each empty eye socket: the only part of him you see in the dark. */
    private void drawGlints(PoseStack.Pose pose, VertexConsumer out, float strength) {
        int a = (int) (Mth.clamp(strength, 0f, 1f) * 255);
        float r = 0.0085f;
        for (int eye : eyes) {
            VMesh.Bone b = skeleton.mesh.bones[eye];
            float z = b.hz() + 0.006f;
            glintVertex(pose, out, eye, b.hx() - r, b.hy() - r, z, 0f, 1f, a);
            glintVertex(pose, out, eye, b.hx() + r, b.hy() - r, z, 1f, 1f, a);
            glintVertex(pose, out, eye, b.hx() + r, b.hy() + r, z, 1f, 0f, a);
            glintVertex(pose, out, eye, b.hx() - r, b.hy() + r, z, 0f, 0f, a);
        }
    }

    /**
     * The same two eyes once more, but drawn without fog, facing the camera and a little larger the farther away he is:
     * at night the fog hides his body a dozen blocks out, and all that is left of him is a pair of lights far too high up.
     */
    private void drawBeacons(PoseStack.Pose pose, VertexConsumer out, float strength, float distance) {
        float far = Mth.clamp((distance - 4f) / 5f, 0f, 1f);
        if (far <= 0f) return;
        Quaternionf face = entityRenderDispatcher.cameraOrientation();
        float size = 0.022f + 0.0065f * distance;
        int alpha = (int) (Mth.clamp(strength + 0.2f, 0f, 1f) * far * 235);
        for (int eye : eyes) {
            VMesh.Bone b = skeleton.mesh.bones[eye];
            skeleton.point(eye, b.hx(), b.hy(), b.hz() + 0.012f, tmp);
            Vector3f c = pose.pose().transformPosition(tmp.x, tmp.y, tmp.z, new Vector3f());
            beacon(out, c, face, size, alpha);
            beacon(out, c, face, size * 3.2f, alpha / 5);
        }
    }

    private static void beacon(VertexConsumer out, Vector3f c, Quaternionf face, float r, int alpha) {
        for (int i = 0; i < 4; i++) {
            Vector3f v = new Vector3f(i == 0 || i == 3 ? -r : r, i < 2 ? -r : r, 0f).rotate(face).add(c);
            out.addVertex(v.x, v.y, v.z).setColor(255, 236, 190, alpha);
        }
    }

    private void glintVertex(PoseStack.Pose pose, VertexConsumer out, int bone, float x, float y, float z, float u, float v, int alpha) {
        skeleton.point(bone, x, y, z, tmp);
        out.addVertex(pose, tmp.x, tmp.y, tmp.z).setColor(255, 240, 205, alpha).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0f, 0f, 1f);
    }

    static ResourceLocation glintTexture() {
        if (!glintReady) {
            glintReady = true;
            NativeImage img = new NativeImage(48, 48, true);
            for (int y = 0; y < 48; y++) {
                for (int x = 0; x < 48; x++) {
                    float d = (float) Math.hypot(x - 23.5, y - 23.5) / 23.5f;
                    int v = (int) (Mth.clamp(1f - d, 0f, 1f) * Mth.clamp(1f - d, 0f, 1f) * 255);
                    img.setPixelRGBA(x, y, 0xFF000000 | v << 16 | v << 8 | v);
                }
            }
            DynamicTexture t = new DynamicTexture(img);
            t.setFilter(true, false);
            Minecraft.getInstance().getTextureManager().register(GLINT, t);
        }
        return GLINT;
    }

    @Override
    public ResourceLocation getTextureLocation(VerityMonster monster) {
        return TEXTURE;
    }
}

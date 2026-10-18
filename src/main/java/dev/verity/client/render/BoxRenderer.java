package dev.verity.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.verity.VerityMod;
import dev.verity.entity.VerityBox;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/** The parcel: a cardboard cube whose two lid flaps burst open. Texture: left half = side, right half = lid. */
public class BoxRenderer extends EntityRenderer<VerityBox> {
    private static final ResourceLocation TEXTURE = VerityMod.id("textures/entity/box.png");
    private static final float H = 0.4f;

    public BoxRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0.5f;
    }

    @Override
    public void render(VerityBox box, float entityYaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        float t = box.isOpening() ? box.getOpenTicks() + partialTick : -1f;
        float age = box.tickCount + partialTick;
        float lift = 0f, shake, flap = 0f, scale = 1f;
        if (t < 0f) {
            // every few seconds whatever is inside knocks
            float cycle = age % 70f;
            shake = cycle < 12f ? Mth.sin(cycle * 2.6f) * (12f - cycle) * 0.5f : 0f;
        } else {
            float rise = Mth.clamp(t / 40f, 0f, 1f);
            lift = 0.7f * rise * rise * (3f - 2f * rise);
            shake = t < 46f ? Mth.sin(t * 2.9f) * (2f + t * 0.22f) : 0f;
            flap = easeOutBack(Mth.clamp((t - 44f) / 9f, 0f, 1f)) * 128f;
            float end = Mth.clamp((t - 62f) / 26f, 0f, 1f);
            lift *= 1f - end * end;
            scale = 1f - end * end * end;
        }
        if (scale <= 0.01f) return;

        pose.pushPose();
        pose.translate(0f, lift, 0f);
        pose.mulPose(Axis.YP.rotationDegrees(-Mth.lerp(partialTick, box.yRotO, box.getYRot())));
        pose.mulPose(Axis.ZP.rotationDegrees(shake));
        pose.mulPose(Axis.XP.rotationDegrees(shake * 0.6f));
        pose.scale(scale, scale, scale);
        pose.translate(0f, H, 0f);
        VertexConsumer out = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        PoseStack.Pose p = pose.last();
        // four sides
        for (int i = 0; i < 4; i++) {
            pose.pushPose();
            pose.mulPose(Axis.YP.rotationDegrees(90f * i));
            PoseStack.Pose q = pose.last();
            quad(q, out, light, -H, -H, H, H, -H, H, H, H, H, -H, H, H, 0f, 0.5f, 0f, 0f, 1f);
            pose.popPose();
        }
        // bottom and the dark inside (visible once the flaps are open)
        quad(p, out, light, -H, -H, -H, H, -H, -H, H, -H, H, -H, -H, H, 0f, 0.5f, 0f, -1f, 0f);
        if (flap > 1f) {
            int dark = 0xFF18120C;
            out.addVertex(p, -H, -H + 0.02f, H).setColor(dark).setUv(0.02f, 0.98f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0, 1, 0);
            out.addVertex(p, H, -H + 0.02f, H).setColor(dark).setUv(0.04f, 0.98f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0, 1, 0);
            out.addVertex(p, H, -H + 0.02f, -H).setColor(dark).setUv(0.04f, 0.96f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0, 1, 0);
            out.addVertex(p, -H, -H + 0.02f, -H).setColor(dark).setUv(0.02f, 0.96f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0, 1, 0);
        }
        // two lid flaps hinged on the left and right edges
        for (int side = -1; side <= 1; side += 2) {
            pose.pushPose();
            pose.translate(side * H, H, 0f);
            pose.mulPose(Axis.ZP.rotationDegrees(-side * flap));
            PoseStack.Pose q = pose.last();
            float u0 = side < 0 ? 0.5f : 0.75f;
            float x0 = side < 0 ? 0f : -H, x1 = side < 0 ? H : 0f;
            quad(q, out, light, x0, 0f, H, x1, 0f, H, x1, 0f, -H, x0, 0f, -H, u0, u0 + 0.25f, 0f, 1f, 0f);
            pose.popPose();
        }
        pose.popPose();
        super.render(box, entityYaw, partialTick, pose, buffers, light);
    }

    private static float easeOutBack(float x) {
        float c1 = 1.70158f, c3 = c1 + 1f;
        return 1f + c3 * (float) Math.pow(x - 1f, 3) + c1 * (float) Math.pow(x - 1f, 2);
    }

    /** Corners in order bottom-left, bottom-right, top-right, top-left of the textured face. */
    private static void quad(PoseStack.Pose p, VertexConsumer out, int light, float x0, float y0, float z0, float x1, float y1, float z1,
                             float x2, float y2, float z2, float x3, float y3, float z3, float u0, float u1, float nx, float ny, float nz) {
        out.addVertex(p, x0, y0, z0).setColor(0xFFFFFFFF).setUv(u0, 1f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, nx, ny, nz);
        out.addVertex(p, x1, y1, z1).setColor(0xFFFFFFFF).setUv(u1, 1f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, nx, ny, nz);
        out.addVertex(p, x2, y2, z2).setColor(0xFFFFFFFF).setUv(u1, 0f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, nx, ny, nz);
        out.addVertex(p, x3, y3, z3).setColor(0xFFFFFFFF).setUv(u0, 0f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, nx, ny, nz);
    }

    @Override
    public ResourceLocation getTextureLocation(VerityBox box) {
        return TEXTURE;
    }
}

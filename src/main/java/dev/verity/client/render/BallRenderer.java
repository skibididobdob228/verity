package dev.verity.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.verity.VerityMod;
import dev.verity.client.mesh.MeshEmitter;
import dev.verity.client.mesh.VMesh;
import dev.verity.entity.VerityBall;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

public class BallRenderer extends EntityRenderer<VerityBall> {
    public static final float RADIUS = 0.25f;
    static final ResourceLocation TEXTURE = VerityMod.id("textures/entity/ball.png");
    private static final int GRID = 18;

    private final VMesh mesh;

    public BallRenderer(EntityRendererProvider.Context context) {
        super(context);
        mesh = VMesh.load(context.getResourceManager(), VerityMod.id("meshes/ball.vmesh"));
        shadowRadius = 0.22f;
        shadowStrength = 0.6f;
    }

    @Override
    public void render(VerityBall ball, float entityYaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        BallAnim anim = BallAnim.of(ball);
        anim.update(ball, partialTick);

        pose.pushPose();
        pose.translate(0f, RADIUS + anim.bob, 0f);
        pose.mulPose(Axis.YP.rotationDegrees(-Mth.rotLerp(partialTick, ball.yHeadRotO, ball.yHeadRot)));
        pose.mulPose(Axis.XP.rotationDegrees(Mth.lerp(partialTick, ball.xRotO, ball.getXRot())));
        pose.mulPose(Axis.ZP.rotationDegrees(anim.roll));
        pose.scale(RADIUS * anim.scaleXZ, RADIUS * anim.scaleY, RADIUS * anim.scaleXZ);

        MeshEmitter.emit(mesh, mesh.pos, mesh.nrm, pose.last(), buffers.getBuffer(VerityRenderTypes.mesh(TEXTURE)),
                light, OverlayTexture.NO_OVERLAY, anim.tint, anim.jitter, anim.seed);
        drawFace(pose.last(), buffers.getBuffer(RenderType.entityTranslucent(anim.faceTexture.id)));
        pose.popPose();
        super.render(ball, entityYaw, partialTick, pose, buffers, light);
    }

    /** A patch of sphere just above the surface, carrying the animated face texture. */
    static void drawFace(PoseStack.Pose pose, VertexConsumer out) {
        // the ball is an exact unit sphere made of flat triangles, so anything from 1.0 up clears it; 1.012 keeps the face from looking sunk in or floating
        float half = FaceTexture.HALF_ANGLE_DEG * Mth.DEG_TO_RAD, r = 1.012f;
        for (int iy = 0; iy < GRID; iy++) {
            for (int ix = 0; ix < GRID; ix++) {
                faceVertex(pose, out, ix, iy + 1, half, r);
                faceVertex(pose, out, ix + 1, iy + 1, half, r);
                faceVertex(pose, out, ix + 1, iy, half, r);
                faceVertex(pose, out, ix, iy, half, r);
            }
        }
    }

    private static void faceVertex(PoseStack.Pose pose, VertexConsumer out, int ix, int iy, float half, float r) {
        float u = ix / (float) GRID, v = iy / (float) GRID;
        float yaw = (u * 2f - 1f) * half, pitch = (1f - v * 2f) * half;
        float x = Mth.sin(yaw) * Mth.cos(pitch), y = Mth.sin(pitch), z = Mth.cos(yaw) * Mth.cos(pitch);
        out.addVertex(pose, x * r, y * r, z * r).setColor(0xFFFFFFFF).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT).setNormal(pose, x, y, z);
    }

    @Override
    public ResourceLocation getTextureLocation(VerityBall ball) {
        return TEXTURE;
    }
}

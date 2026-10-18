package dev.verity.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.verity.VerityMod;
import dev.verity.client.fx.ScreenFx;
import dev.verity.client.mesh.MeshEmitter;
import dev.verity.client.mesh.VMesh;
import dev.verity.client.voice.Voice;
import dev.verity.entity.Emotion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Draws Verity-the-item with the same mesh and living face as the ball: in your hand, in a slot, in somebody else's hand. */
public class VerityItemRenderer extends BlockEntityWithoutLevelRenderer {
    private VMesh mesh;

    public VerityItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    private static boolean hovered() {
        if (!(Minecraft.getInstance().screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> s)) return false;
        net.minecraft.world.inventory.Slot slot = s.getSlotUnderMouse();
        return slot != null && slot.getItem().getItem() instanceof dev.verity.item.VerityItem;
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (mesh == null) mesh = VMesh.load(Minecraft.getInstance().getResourceManager(), VerityMod.id("meshes/ball.vmesh"));
        BallAnim anim = BallAnim.item();
        // whoever carries him is where his voice comes from, so their id keys the lip-sync
        Emotion face = Emotion.byId(ScreenFx.emotion);
        // episode 1: in the inventory he looks plain, until the cursor is on him; then the smile is not the same smile
        if (context == ItemDisplayContext.GUI && ScreenFx.stage >= 3 && ScreenFx.stage != 9 && hovered()) face = Emotion.TEETH;
        anim.update(face, ScreenFx.corruption, Voice.level(ScreenFx.carrierId), 0, 0, false, false);
        pose.pushPose();
        pose.translate(0.5f, 0.5f, 0.5f);
        float r = 0.42f;
        pose.scale(r * anim.scaleXZ, r * anim.scaleY, r * anim.scaleXZ);
        MeshEmitter.emit(mesh, mesh.pos, mesh.nrm, pose.last(), buffers.getBuffer(VerityRenderTypes.mesh(BallRenderer.TEXTURE)),
                light, overlay, anim.tint, anim.jitter, anim.seed);
        BallRenderer.drawFace(pose.last(), buffers.getBuffer(RenderType.entityTranslucent(anim.faceTexture.id)));
        pose.popPose();
    }
}

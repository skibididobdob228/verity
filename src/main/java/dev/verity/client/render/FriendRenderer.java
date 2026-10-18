package dev.verity.client.render;

import dev.verity.entity.VerityFriend;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

/** Twixxel looks like any other player (the plain default skin). */
public class FriendRenderer extends HumanoidMobRenderer<VerityFriend, HumanoidModel<VerityFriend>> {
    private static final ResourceLocation SKIN = ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png");

    public FriendRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5f);
    }

    @Override
    public ResourceLocation getTextureLocation(VerityFriend friend) {
        return SKIN;
    }
}

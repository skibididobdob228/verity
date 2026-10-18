package dev.verity.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.function.Function;
import net.minecraft.Util;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

public final class VerityRenderTypes {
    /** Vanilla's entity_cutout_no_cull, but fed with triangles instead of quads. */
    private static final Function<ResourceLocation, RenderType> MESH = Util.memoize(texture -> RenderType.create(
            "verity_mesh", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.TRIANGLES, 1 << 21, true, false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                    .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setLightmapState(RenderStateShard.LIGHTMAP)
                    .setOverlayState(RenderStateShard.OVERLAY)
                    .createCompositeState(true)));

    /** Plain additive colour with no fog: for the two points of light that show where the dark has swallowed the rest of him. */
    private static final RenderType GLOW = RenderType.create("verity_glow", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 256, false, true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));

    public static RenderType glow() {
        return GLOW;
    }

    public static RenderType mesh(ResourceLocation texture) {
        return MESH.apply(texture);
    }

    private VerityRenderTypes() {}
}

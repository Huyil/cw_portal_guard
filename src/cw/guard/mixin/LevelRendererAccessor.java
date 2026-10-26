package cw.guard.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** LevelRenderer.chunkRenderDispatcher (SRG: f_109436_) 读取器。 */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {

    @Accessor("f_109436_")
    ChunkRenderDispatcher cw$dispatcher();
}

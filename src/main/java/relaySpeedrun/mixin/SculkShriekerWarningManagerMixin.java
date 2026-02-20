package relaySpeedrun.mixin;

import net.minecraft.block.entity.SculkShriekerWarningManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SculkShriekerWarningManager.class)
public interface SculkShriekerWarningManagerMixin {
    
    @Invoker("copy")
    void relay_speedrun$copy(SculkShriekerWarningManager other);
    
}

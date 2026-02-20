package relaySpeedrun.mixin;

import net.minecraft.entity.LazyEntityReference;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.TntEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TntEntity.class)
public interface TntEntityMixin {
    
    @Accessor("causingEntity")
    void setCausingEntity(LazyEntityReference<LivingEntity> entity);
    
}

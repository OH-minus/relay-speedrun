package relaySpeedrun.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LazyEntityReference;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.UUID;

@Mixin(ItemEntity.class)
public interface ItemEntityMixin {
    
    @Accessor("thrower")
    void setThrower(LazyEntityReference<Entity> thrower);
    
    @Accessor("owner")
    UUID getOwnerUuid();
    
    @Accessor("owner")
    void setOwnerUuid(UUID owner);
    
}

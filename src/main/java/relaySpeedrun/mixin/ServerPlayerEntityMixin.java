package relaySpeedrun.mixin;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerPlayerEntity.class)
public interface ServerPlayerEntityMixin {
    
    @Invoker("setLeftShoulderNbt")
    void relay_speedrun$setLeftShoulderNbt(NbtCompound leftShoulderNbt);
    
    @Invoker("setRightShoulderNbt")
    void relay_speedrun$setRightShoulderNbt(NbtCompound rightShoulderNbt);
    
}

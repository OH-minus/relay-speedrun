package relaySpeedrun.mixin;

import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerPlayNetworkHandler.class)
public interface ServerPlayNetworkHandlerMixin {
    
    @Accessor("lastTickX")
    double getLastTickX();
    
    @Accessor("lastTickY")
    double getLastTickY();
    
    @Accessor("lastTickZ")
    double getLastTickZ();
    
}

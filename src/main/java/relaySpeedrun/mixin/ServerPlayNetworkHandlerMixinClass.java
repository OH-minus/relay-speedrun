package relaySpeedrun.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerPlayNetworkHandler.class)
public class ServerPlayNetworkHandlerMixinClass {
    
    @ModifyReturnValue(method = "shouldCheckMovement", at = @At("RETURN"))
    private boolean shouldCheckMovement(boolean elytra) { return false; }
    
}

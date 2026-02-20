package relaySpeedrun.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.VaultBlockEntity;
import net.minecraft.block.vault.VaultConfig;
import net.minecraft.block.vault.VaultServerData;
import net.minecraft.block.vault.VaultSharedData;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;

@Mixin(VaultBlockEntity.Server.class)
public class VaultBlockEntityServerMixin {
    
    @Inject(method = "tryUnlock",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/block/vault/VaultServerData;markPlayerAsRewarded(Lnet/minecraft/entity/player/PlayerEntity;)V"))
    private static void markAllPlayersAsRewarded(ServerWorld world, BlockPos pos, BlockState state, VaultConfig config,
                                                 VaultServerData serverData, VaultSharedData sharedData,
                                                 PlayerEntity player, ItemStack stack, CallbackInfo ci) {
        for (ServerPlayerEntity p : Objects.requireNonNull(world.getServer()).getPlayerManager().getPlayerList())
            serverData.markPlayerAsRewarded(p);
    }
    
}
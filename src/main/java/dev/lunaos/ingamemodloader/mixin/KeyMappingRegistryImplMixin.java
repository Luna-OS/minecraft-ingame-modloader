package dev.lunaos.ingamemodloader.mixin;

import net.fabricmc.fabric.impl.client.keymapping.KeyMappingRegistryImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import dev.lunaos.ingamemodloader.loader.RuntimeModLoader;

/**
 * Fabric API only accepts key mappings before the options are created. Mods loaded at runtime are
 * allowed to register them anyway, the loader adds them to the options afterwards.
 */
@Mixin(KeyMappingRegistryImpl.class)
public class KeyMappingRegistryImplMixin {
	@Redirect(method = "registerKeyMapping", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;options:Lnet/minecraft/client/Options;"), require = 0)
	private static Options ingameModloader$allowLateRegistration(Minecraft minecraft) {
		return RuntimeModLoader.isLoading() ? null : minecraft.options;
	}
}

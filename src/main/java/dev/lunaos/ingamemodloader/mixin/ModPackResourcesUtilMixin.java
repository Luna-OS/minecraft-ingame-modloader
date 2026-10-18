package dev.lunaos.ingamemodloader.mixin;

import java.util.Collection;

import net.fabricmc.fabric.impl.resource.pack.ModPackResourcesUtil;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import dev.lunaos.ingamemodloader.loader.RuntimeModUnloader;

/** Leaves the assets and data of mods that were switched off at runtime out of the next resource reload. */
@Mixin(ModPackResourcesUtil.class)
public class ModPackResourcesUtilMixin {
	@Redirect(method = "getModResourcePacks", at = @At(value = "INVOKE", target = "Lnet/fabricmc/loader/api/FabricLoader;getAllMods()Ljava/util/Collection;"), require = 0)
	private static Collection<ModContainer> ingameModloader$skipDisabledMods(FabricLoader loader) {
		return loader.getAllMods().stream().filter(mod -> !RuntimeModUnloader.isDisabled(mod.getMetadata().getId())).toList();
	}
}

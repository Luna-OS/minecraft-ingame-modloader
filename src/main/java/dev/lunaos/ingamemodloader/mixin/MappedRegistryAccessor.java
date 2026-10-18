package dev.lunaos.ingamemodloader.mixin;

import java.util.Map;

import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.component.DataComponentLookup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Gives the runtime loader access to the freeze state of built-in registries,
 * so that late-loaded mods can still register their content.
 */
@Mixin(MappedRegistry.class)
public interface MappedRegistryAccessor<T> {
	@Accessor("frozen")
	boolean ingameModloader$isFrozen();

	@Accessor("frozen")
	void ingameModloader$setFrozen(boolean frozen);

	@Accessor("unregisteredIntrusiveHolders")
	Map<T, Holder.Reference<T>> ingameModloader$getUnregisteredIntrusiveHolders();

	@Accessor("unregisteredIntrusiveHolders")
	void ingameModloader$setUnregisteredIntrusiveHolders(Map<T, Holder.Reference<T>> holders);

	@Accessor("byValue")
	Map<T, Holder.Reference<T>> ingameModloader$getByValue();

	@Accessor("componentLookup")
	void ingameModloader$setComponentLookup(DataComponentLookup<T> lookup);

	@Invoker("refreshTagsInHolders")
	void ingameModloader$refreshTagsInHolders();
}

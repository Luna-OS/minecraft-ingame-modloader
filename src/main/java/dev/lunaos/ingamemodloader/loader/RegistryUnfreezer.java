package dev.lunaos.ingamemodloader.loader;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import dev.lunaos.ingamemodloader.IngameModLoader;
import dev.lunaos.ingamemodloader.mixin.HolderReferenceAccessor;
import dev.lunaos.ingamemodloader.mixin.MappedRegistryAccessor;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

/**
 * Built-in registries are frozen once the game has bootstrapped. To let a late-loaded mod register
 * blocks, items etc. they are temporarily unfrozen and afterwards frozen again, redoing the work
 * {@link MappedRegistry#freeze()} does for the entries that were added in between.
 */
final class RegistryUnfreezer {
	private final List<MappedRegistry<?>> unfrozen = new ArrayList<>();
	private int blockCount;
	private int fluidCount;

	void unfreeze() {
		blockCount = BuiltInRegistries.BLOCK.size();
		fluidCount = BuiltInRegistries.FLUID.size();
		unfreeze(BuiltInRegistries.REGISTRY);

		for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
			unfreeze(registry);
		}
	}

	private void unfreeze(Registry<?> registry) {
		if (!(registry instanceof MappedRegistry<?> mapped)) return;

		MappedRegistryAccessor<?> accessor = (MappedRegistryAccessor<?>) mapped;
		if (!accessor.ingameModloader$isFrozen()) return;

		accessor.ingameModloader$setFrozen(false);

		if (hasIntrusiveHolders(mapped)) {
			accessor.ingameModloader$setUnregisteredIntrusiveHolders(new IdentityHashMap<>());
		}

		unfrozen.add(mapped);
	}

	void refreeze() {
		for (MappedRegistry<?> registry : unfrozen) {
			refreeze(registry);
		}

		unfrozen.clear();

		// Registries created by the new mods have never been frozen, the normal freeze works for them.
		for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
			if (registry instanceof MappedRegistry<?> mapped && !((MappedRegistryAccessor<?>) mapped).ingameModloader$isFrozen()) {
				try {
					mapped.freeze();
				} catch (RuntimeException e) {
					IngameModLoader.LOGGER.error("Failed to freeze new registry {}", mapped.key(), e);
				}
			}
		}

		initNewStates();
	}

	@SuppressWarnings("unchecked")
	private static <T> void refreeze(MappedRegistry<T> registry) {
		MappedRegistryAccessor<T> accessor = (MappedRegistryAccessor<T>) registry;
		accessor.ingameModloader$setFrozen(true);
		accessor.ingameModloader$getByValue().forEach((value, holder) -> ((HolderReferenceAccessor<T>) (Object) holder).ingameModloader$bindValue(value));

		Map<T, Holder.Reference<T>> intrusive = accessor.ingameModloader$getUnregisteredIntrusiveHolders();

		if (intrusive != null) {
			if (!intrusive.isEmpty()) {
				// Vanilla would crash here. These objects were created but never registered, so they are unusable anyway.
				IngameModLoader.LOGGER.warn("Some intrusive holders were not registered in {}: {}", registry.key(), intrusive.values());
			}

			accessor.ingameModloader$setUnregisteredIntrusiveHolders(null);
		}

		accessor.ingameModloader$setComponentLookup(new DataComponentLookup<>(registry.listElements().toList()));
		accessor.ingameModloader$refreshTagsInHolders();
	}

	/** Does what the static initializers of {@code Blocks} and {@code Fluids} do for vanilla content. */
	private void initNewStates() {
		for (int id = blockCount; id < BuiltInRegistries.BLOCK.size(); id++) {
			Block block = BuiltInRegistries.BLOCK.byId(id);
			if (block == null) continue;

			for (BlockState state : block.getStateDefinition().getPossibleStates()) {
				if (Block.BLOCK_STATE_REGISTRY.getId(state) == -1) Block.BLOCK_STATE_REGISTRY.add(state);
				state.initCache();
			}
		}

		for (int id = fluidCount; id < BuiltInRegistries.FLUID.size(); id++) {
			Fluid fluid = BuiltInRegistries.FLUID.byId(id);
			if (fluid == null) continue;

			for (FluidState state : fluid.getStateDefinition().getPossibleStates()) {
				if (Fluid.FLUID_STATE_REGISTRY.getId(state) == -1) Fluid.FLUID_STATE_REGISTRY.add(state);
			}
		}
	}

	/** Registries like blocks and items create their holders in the object's constructor. */
	private static boolean hasIntrusiveHolders(MappedRegistry<?> registry) {
		if (registry.size() == 0) return false;

		try {
			Holder.Reference<?> holder = registry.get(0).orElseThrow();
			Field type = Holder.Reference.class.getDeclaredField("type");
			type.setAccessible(true);
			return ((Enum<?>) type.get(holder)).name().equals("INTRUSIVE");
		} catch (ReflectiveOperationException | RuntimeException e) {
			IngameModLoader.LOGGER.warn("Could not determine holder type of {}", registry.key(), e);
			return false;
		}
	}
}

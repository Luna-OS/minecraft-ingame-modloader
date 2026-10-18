package dev.lunaos.ingamemodloader.loader;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.impl.client.keymapping.KeyMappingRegistryImpl;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import dev.lunaos.ingamemodloader.IngameModLoader;
import dev.lunaos.ingamemodloader.mixin.OptionsAccessor;

/**
 * Switches a running mod off without a restart, as far as that is possible. Classes cannot be
 * unloaded from the JVM, so the mod is cut off from the game instead: its Fabric API event
 * listeners and key mappings are removed and its resources are left out of the next resource
 * reload. Mixins it applied to game classes keep working until the next start.
 */
public final class RuntimeModUnloader {
	private static final Map<String, Disabled> DISABLED = new HashMap<>();

	private record Disabled(List<EventListeners.Removed> listeners, List<KeyMapping> keyMappings, boolean partial) {
	}

	private RuntimeModUnloader() {
	}

	public static boolean isDisabled(String modId) {
		return DISABLED.containsKey(modId);
	}

	/** Whether the mod was switched off, but still has mixins in game classes. */
	public static boolean isPartiallyDisabled(String modId) {
		Disabled disabled = DISABLED.get(modId);
		return disabled != null && disabled.partial();
	}

	/**
	 * Returns why the mod cannot be switched off without a restart, or null if it can.
	 *
	 * @param alsoDisabled mods that are switched off together with this one
	 */
	public static @Nullable Component blocker(ModContainer mod, Collection<String> alsoDisabled) {
		String id = mod.getMetadata().getId();
		if (id.equals(IngameModLoader.MOD_ID)) return Component.translatable("ingame-modloader.blocker.self");

		List<String> neededBy = new ArrayList<>();

		for (ModContainer other : FabricLoader.getInstance().getAllMods()) {
			String otherId = other.getMetadata().getId();
			if (other == mod || isDisabled(otherId) || alsoDisabled.contains(otherId)) continue;

			for (ModDependency dependency : other.getMetadata().getDependencies()) {
				if (dependency.getKind() != ModDependency.Kind.DEPENDS) continue;

				if (dependency.getModId().equals(id) || mod.getMetadata().getProvides().contains(dependency.getModId())) {
					neededBy.add(other.getMetadata().getName());
					break;
				}
			}
		}

		return neededBy.isEmpty() ? null : Component.translatable("ingame-modloader.blocker.needed_by", String.join(", ", neededBy));
	}

	/** Cuts the mod off from the game. The caller reloads the resources afterwards. */
	public static void disable(ModContainer mod) throws ReflectiveOperationException {
		String id = mod.getMetadata().getId();
		if (isDisabled(id)) return;

		List<EventListeners.Removed> listeners = EventListeners.remove(listener -> belongsTo(mod, listener.getClass()));
		List<KeyMapping> keyMappings = removeKeyMappings(id);
		boolean partial = hasGameMixins(mod);
		DISABLED.put(id, new Disabled(listeners, keyMappings, partial));
		IngameModLoader.LOGGER.info("Switched {} off at runtime ({} listeners, {} key mappings{})", id, listeners.size(), keyMappings.size(),
				partial ? ", its mixins stay active until the next start" : "");
	}

	/** Undoes {@link #disable}. The caller reloads the resources afterwards. */
	public static void enable(String modId) throws ReflectiveOperationException {
		Disabled disabled = DISABLED.remove(modId);
		if (disabled == null) return;

		EventListeners.restore(disabled.listeners());
		moddedKeyMappings().addAll(disabled.keyMappings());
		Minecraft minecraft = Minecraft.getInstance();
		((OptionsAccessor) minecraft.options).ingameModloader$setKeyMappings(KeyMappingRegistryImpl.process(minecraft.options.keyMappings));
		KeyMapping.resetMapping();
		IngameModLoader.LOGGER.info("Switched {} back on", modId);
	}

	/** A listener belongs to the mod if its class, or the class that declared the lambda, comes from the mod's jar. */
	private static boolean belongsTo(ModContainer mod, Class<?> listenerClass) {
		String name = listenerClass.getName();
		int lambda = name.indexOf("$$Lambda");
		if (lambda >= 0) name = name.substring(0, lambda);

		return mod.findPath(name.replace('.', '/') + ".class").isPresent();
	}

	/** Fabric API does not record which mod registered a key mapping, so they are matched by the usual naming. */
	private static List<KeyMapping> removeKeyMappings(String modId) throws ReflectiveOperationException {
		List<KeyMapping> modded = moddedKeyMappings();
		List<KeyMapping> removed = modded.stream()
				.filter(key -> key.getName().startsWith("key." + modId + ".") || key.getCategory().id().getNamespace().equals(modId))
				.toList();
		if (removed.isEmpty()) return removed;

		modded.removeAll(removed);
		Minecraft minecraft = Minecraft.getInstance();
		KeyMapping[] remaining = Arrays.stream(minecraft.options.keyMappings).filter(key -> !removed.contains(key)).toArray(KeyMapping[]::new);
		((OptionsAccessor) minecraft.options).ingameModloader$setKeyMappings(remaining);
		removed.forEach(key -> key.setDown(false));
		KeyMapping.resetMapping();
		return removed;
	}

	@SuppressWarnings("unchecked")
	private static List<KeyMapping> moddedKeyMappings() throws ReflectiveOperationException {
		Field field = KeyMappingRegistryImpl.class.getDeclaredField("MODDED_KEY_BINDINGS");
		field.setAccessible(true);
		return (List<KeyMapping>) field.get(null);
	}

	/** Whether the mod has mixins into classes that are not its own, which cannot be taken out again. */
	private static boolean hasGameMixins(ModContainer mod) {
		if (!(mod.getMetadata() instanceof LoaderModMetadata metadata)) return false;

		try {
			Set<String> targets = LateTransformers.mixinTargets(file -> mod.findPath(file).orElse(null), metadata.getMixinConfigs(EnvType.CLIENT));
			return targets.stream().anyMatch(target -> mod.findPath(target.replace('.', '/') + ".class").isEmpty());
		} catch (Exception e) {
			IngameModLoader.LOGGER.warn("Failed to read the mixins of {}", mod.getMetadata().getId(), e);
			return true;
		}
	}
}

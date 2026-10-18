package dev.lunaos.ingamemodloader.loader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.impl.client.keymapping.KeyMappingRegistryImpl;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import dev.lunaos.ingamemodloader.IngameModLoader;
import dev.lunaos.ingamemodloader.loader.ModJar.Status;
import dev.lunaos.ingamemodloader.mixin.CreativeModeTabsAccessor;
import dev.lunaos.ingamemodloader.mixin.OptionsAccessor;

/**
 * Loads mods into the running game. Mods cannot be unloaded again: once a jar is on the class path
 * and its entrypoints ran, the only way back is a restart.
 */
public final class RuntimeModLoader {
	private static final Set<Path> LOADED_THIS_SESSION = new HashSet<>();
	/** Failed jars are partially registered with the loader and would otherwise show up as loaded. */
	private static final Map<Path, Component> FAILED_THIS_SESSION = new HashMap<>();
	private static boolean loading;

	private RuntimeModLoader() {
	}

	public static boolean wasLoadedThisSession(Path path) {
		return LOADED_THIS_SESSION.contains(path);
	}

	public static @Nullable Component failureThisSession(Path path) {
		return FAILED_THIS_SESSION.get(path);
	}

	/** Whether mod code of late-loaded mods is currently running. */
	public static boolean isLoading() {
		return loading;
	}

	/**
	 * Loads the selected jars together with the ready jars they depend on. Has to run on the render
	 * thread while no world is loaded. Updates the status of every jar it touched. The caller has to
	 * {@link #refreshGame() refresh the game} afterwards.
	 *
	 * @return the number of jars that were loaded successfully
	 */
	public static int load(Collection<ModJar> selection, List<ModJar> scanned) {
		List<ModJar> order = loadOrder(selection, scanned);
		if (order.isEmpty()) return 0;

		// Every container that gets created belongs to exactly one top level jar, which fails as a whole.
		Map<ModContainerImpl, ModJar> owners = new LinkedHashMap<>();
		RegistryUnfreezer registries = new RegistryUnfreezer();
		registries.unfreeze();
		loading = true;

		try {
			for (ModJar jar : order) {
				List<ModContainerImpl> containers = new ArrayList<>();

				try {
					prepare(jar, containers);
				} catch (Throwable t) {
					fail(jar, t);
				}

				// Mods that were registered stay registered even if a later step failed, they cannot be removed again.
				containers.forEach(container -> owners.put(container, jar));
			}

			runEntrypoints(owners, "preLaunch", PreLaunchEntrypoint.class, PreLaunchEntrypoint::onPreLaunch);
			runEntrypoints(owners, "main", ModInitializer.class, ModInitializer::onInitialize);
			runEntrypoints(owners, "client", ClientModInitializer.class, ClientModInitializer::onInitializeClient);
		} finally {
			loading = false;
			registries.refreeze();
		}

		int loaded = 0;

		for (ModJar jar : order) {
			if (jar.status() == Status.FAILED) continue;

			jar.setStatus(Status.LOADED, Component.translatable("ingame-modloader.status.loaded"));
			LOADED_THIS_SESSION.add(jar.path());
			loaded++;
			IngameModLoader.LOGGER.info("Loaded {} {} at runtime", jar.id(), jar.version());
		}

		return loaded;
	}

	/** Orders the selection so that ready dependencies from the scan come before the mods needing them. */
	private static List<ModJar> loadOrder(Collection<ModJar> selection, List<ModJar> scanned) {
		Map<String, ModJar> providers = new HashMap<>();

		for (ModJar jar : scanned) {
			if (!jar.isLoadable() || jar.metadata() == null) continue;

			providers.putIfAbsent(jar.metadata().getId(), jar);
			jar.metadata().getProvides().forEach(alias -> providers.putIfAbsent(alias, jar));
		}

		Set<ModJar> order = new LinkedHashSet<>();
		Set<ModJar> visiting = new HashSet<>();

		for (ModJar jar : selection) {
			if (jar.isLoadable()) visit(jar, providers, order, visiting);
		}

		return new ArrayList<>(order);
	}

	private static void visit(ModJar jar, Map<String, ModJar> providers, Set<ModJar> order, Set<ModJar> visiting) {
		if (order.contains(jar) || !visiting.add(jar)) return;

		if (jar.metadata() != null) {
			for (ModDependency dependency : jar.metadata().getDependencies()) {
				if (dependency.getKind() != ModDependency.Kind.DEPENDS || FabricLoader.getInstance().isModLoaded(dependency.getModId())) continue;

				ModJar provider = providers.get(dependency.getModId());
				if (provider != null && provider != jar) visit(provider, providers, order, visiting);
			}
		}

		order.add(jar);
	}

	/** Puts a jar and its nested jars on the class path and registers them with Fabric Loader, without running any mod code. */
	private static void prepare(ModJar jar, List<ModContainerImpl> containers) throws Exception {
		for (ModJar nested : jar.nested()) {
			prepare(nested, containers);
		}

		// Two jars can bundle the same library, the first one wins like it does at startup.
		if (jar.metadata() != null && FabricLoader.getInstance().isModLoaded(jar.metadata().getId())) return;

		LoaderInternals.addToClassPath(jar.path());
		if (jar.metadata() == null) return;

		ModContainerImpl container = LoaderInternals.createContainer(jar.path(), jar.metadata());
		LoaderInternals.registerMod(container);
		containers.add(container);
		LoaderInternals.registerLanguageAdapters(container);

		String classTweaker = jar.metadata().getClassTweaker();

		if (classTweaker != null) {
			LateTransformers.applyClassTweaker(container.findPath(classTweaker).orElseThrow());
		}

		LateTransformers.applyMixins(container, jar.mixinConfigs());
		LoaderInternals.registerEntrypoints(container);
	}

	private static <T> void runEntrypoints(Map<ModContainerImpl, ModJar> owners, String key, Class<T> type, Consumer<? super T> invoker) {
		List<EntrypointContainer<T>> entrypoints;

		try {
			entrypoints = FabricLoader.getInstance().getEntrypointContainers(key, type);
		} catch (Throwable t) {
			IngameModLoader.LOGGER.error("Failed to collect {} entrypoints", key, t);
			return;
		}

		for (EntrypointContainer<T> entrypoint : entrypoints) {
			ModJar owner = owners.get(entrypoint.getProvider());
			if (owner == null || owner.status() == Status.FAILED) continue;

			try {
				invoker.accept(entrypoint.getEntrypoint());
			} catch (Throwable t) {
				fail(owner, t);
			}
		}
	}

	private static void fail(ModJar jar, Throwable t) {
		IngameModLoader.LOGGER.error("Failed to load {} at runtime", jar.fileName(), t);
		Throwable cause = t;

		// Entrypoint and reflection failures are wrapped, the root cause is what the user needs to see.
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}

		String message = cause.getClass().getSimpleName() + (cause.getMessage() != null ? ": " + cause.getMessage() : "");
		jar.setStatus(Status.FAILED, Component.translatable("ingame-modloader.status.failed", message));
		FAILED_THIS_SESSION.put(jar.path(), jar.detail());
	}

	/** Lets the game pick up what mods (un)registered: key mappings, creative tab contents, assets and translations. */
	public static void refreshGame() {
		Minecraft minecraft = Minecraft.getInstance();

		try {
			KeyMapping[] keyMappings = KeyMappingRegistryImpl.process(minecraft.options.keyMappings);
			((OptionsAccessor) minecraft.options).ingameModloader$setKeyMappings(keyMappings);
			KeyMapping.resetMapping();
		} catch (Throwable t) {
			IngameModLoader.LOGGER.warn("Failed to update key mappings", t);
		}

		CreativeModeTabsAccessor.ingameModloader$setCachedParameters(null);
		minecraft.reloadResourcePacks();
	}
}

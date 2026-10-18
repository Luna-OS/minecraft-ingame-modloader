package dev.lunaos.ingamemodloader.loader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.discovery.ModCandidateImpl;
import net.fabricmc.loader.impl.entrypoint.EntrypointStorage;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.metadata.EntrypointMetadata;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;

/**
 * Everything that touches Fabric Loader internals lives here. Fabric Loader has no API for adding
 * mods after startup, so this mirrors what {@code FabricLoaderImpl} does during its own setup.
 */
final class LoaderInternals {
	private LoaderInternals() {
	}

	static ModContainerImpl createContainer(Path jar, LoaderModMetadata metadata) throws ReflectiveOperationException {
		Method createPlain = ModCandidateImpl.class.getDeclaredMethod("createPlain", List.class, LoaderModMetadata.class, boolean.class, Collection.class);
		createPlain.setAccessible(true);
		ModCandidateImpl candidate = (ModCandidateImpl) createPlain.invoke(null, List.of(jar), metadata, false, List.of());
		return new ModContainerImpl(candidate);
	}

	static void addToClassPath(Path jar) {
		FabricLauncherBase.getLauncher().addToClassPath(jar);
	}

	static boolean isClassLoaded(String binaryName) {
		return FabricLauncherBase.getLauncher().isClassLoaded(binaryName);
	}

	static String runtimeNamespace() {
		return FabricLauncherBase.getLauncher().getMappingConfiguration().getRuntimeNamespace();
	}

	@SuppressWarnings("unchecked")
	static void registerMod(ModContainerImpl container) throws ReflectiveOperationException {
		FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;
		List<ModContainerImpl> mods = (List<ModContainerImpl>) field("mods").get(loader);
		Map<String, ModContainerImpl> modMap = (Map<String, ModContainerImpl>) field("modMap").get(loader);
		mods.add(container);
		modMap.put(container.getMetadata().getId(), container);

		for (String provides : container.getMetadata().getProvides()) {
			modMap.put(provides, container);
		}
	}

	static void registerLanguageAdapters(ModContainerImpl container) throws ReflectiveOperationException {
		Map<String, LanguageAdapter> adapters = adapterMap();

		for (Map.Entry<String, String> entry : container.getInfo().getLanguageAdapterDefinitions().entrySet()) {
			if (adapters.containsKey(entry.getKey())) continue;

			Class<?> adapterClass = Class.forName(entry.getValue(), true, FabricLauncherBase.getLauncher().getTargetClassLoader());
			adapters.put(entry.getKey(), (LanguageAdapter) adapterClass.getDeclaredConstructor().newInstance());
		}
	}

	@SuppressWarnings("deprecation") // old style initializers are still supported by the loader
	static void registerEntrypoints(ModContainerImpl container) throws Exception {
		EntrypointStorage storage = (EntrypointStorage) field("entrypointStorage").get(FabricLoaderImpl.INSTANCE);
		Map<String, LanguageAdapter> adapters = adapterMap();
		LoaderModMetadata info = container.getInfo();

		for (String initializer : info.getOldInitializers()) {
			storage.addDeprecated(container, info.getOldStyleLanguageAdapter(), initializer);
		}

		for (String key : info.getEntrypointKeys()) {
			for (EntrypointMetadata entrypoint : info.getEntrypoints(key)) {
				storage.add(container, key, entrypoint, adapters);
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, LanguageAdapter> adapterMap() throws ReflectiveOperationException {
		return (Map<String, LanguageAdapter>) field("adapterMap").get(FabricLoaderImpl.INSTANCE);
	}

	private static Field field(String name) throws NoSuchFieldException {
		Field field = FabricLoaderImpl.class.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}
}

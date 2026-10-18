package dev.lunaos.ingamemodloader.loader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.metadata.ModMetadataParser;
import net.fabricmc.loader.impl.metadata.NestedJarEntry;
import net.fabricmc.loader.impl.metadata.VersionOverrides;
import net.minecraft.network.chat.Component;

import dev.lunaos.ingamemodloader.IngameModLoader;
import dev.lunaos.ingamemodloader.loader.ModJar.Status;

/**
 * Looks at the jars in the mods folder and decides for each of them whether it can be loaded
 * into the running game.
 */
public final class ModScanner {
	private static final int MAX_LISTED_CLASSES = 3;

	private ModScanner() {
	}

	@SuppressWarnings("deprecation")
	public static Path modsDirectory() {
		return FabricLoaderImpl.INSTANCE.getModsDirectory().toPath().toAbsolutePath().normalize();
	}

	private static Path nestedJarDirectory() {
		return FabricLoader.getInstance().getGameDir().resolve(".fabric").resolve(IngameModLoader.MOD_ID).resolve("nested");
	}

	public static List<ModJar> scan() {
		Path modsDir = modsDirectory();
		List<ModJar> result = new ArrayList<>();
		if (!Files.isDirectory(modsDir)) return result;

		List<Path> jars;

		try (Stream<Path> files = Files.list(modsDir)) {
			jars = files.filter(file -> isJar(file) || ModFiles.isDisabledJar(file)).sorted().toList();
		} catch (IOException e) {
			IngameModLoader.LOGGER.error("Failed to list {}", modsDir, e);
			return result;
		}

		Set<Path> loadedOrigins = loadedOrigins();

		for (Path file : jars) {
			Path jar = file.toAbsolutePath().normalize();
			boolean disabled = ModFiles.isDisabledJar(jar) || ModFiles.isPendingDisable(jar);
			result.add(disabled ? inspectDisabled(jar, loadedOrigins) : inspect(jar, true, loadedOrigins));
		}

		resolveDependencies(result);
		return result;
	}

	public static boolean isJar(Path path) {
		String name = path.getFileName().toString();
		return name.endsWith(".jar") && !name.startsWith(".") && Files.isRegularFile(path);
	}

	private static ModJar inspect(Path path, boolean topLevel, Set<Path> loadedOrigins) {
		try (FileSystem fs = FileSystems.newFileSystem(path)) {
			Path root = fs.getRootDirectories().iterator().next();
			Path modJson = root.resolve("fabric.mod.json");

			if (!Files.isRegularFile(modJson)) {
				// Jar-in-jar libraries are plain jars. A plain jar in the mods folder is not a Fabric mod, though.
				return topLevel
						? new ModJar(path, null, Status.INVALID, Component.translatable("ingame-modloader.status.not_fabric"))
						: new ModJar(path, null, Status.READY, Component.translatable("ingame-modloader.status.library"));
			}

			LoaderModMetadata metadata;

			try {
				metadata = readMetadata(modJson, path);
			} catch (Exception e) {
				return new ModJar(path, null, Status.INVALID, Component.translatable("ingame-modloader.status.invalid_metadata", e.getMessage()));
			}

			ModJar jar = new ModJar(path, metadata, Status.READY, Component.translatable("ingame-modloader.status.ready"));
			Optional<ModContainer> existing = FabricLoader.getInstance().getModContainer(metadata.getId());

			Component failure = RuntimeModLoader.failureThisSession(path);

			if (failure != null) {
				jar.setStatus(Status.FAILED, failure);
				return jar;
			} else if (RuntimeModLoader.wasLoadedThisSession(path)) {
				jar.setStatus(Status.LOADED, Component.translatable("ingame-modloader.status.loaded"));
				return jar;
			} else if (loadedOrigins.contains(path)) {
				jar.setStatus(Status.ALREADY_LOADED, Component.translatable("ingame-modloader.status.already_loaded"));
				return jar;
			} else if (existing.isPresent()) {
				Version loadedVersion = existing.get().getMetadata().getVersion();

				if (loadedVersion.equals(metadata.getVersion())) {
					jar.setStatus(Status.ALREADY_LOADED, Component.translatable("ingame-modloader.status.already_loaded"));
				} else {
					jar.setStatus(Status.NEEDS_RESTART, Component.translatable("ingame-modloader.status.other_version", loadedVersion.getFriendlyString()));
				}

				return jar;
			} else if (!metadata.loadsInEnvironment(EnvType.CLIENT)) {
				jar.setStatus(Status.INVALID, Component.translatable("ingame-modloader.status.server_only"));
				return jar;
			}

			for (NestedJarEntry entry : metadata.getJars()) {
				Path extracted = extractNested(path, root.resolve(entry.getFile()));
				ModJar nested = inspect(extracted, false, loadedOrigins);

				switch (nested.status()) {
					case READY -> jar.addNested(nested);
					case ALREADY_LOADED, LOADED -> { }
					case NEEDS_RESTART -> {
						jar.setStatus(Status.NEEDS_RESTART, Component.translatable("ingame-modloader.status.nested", nested.displayName(), nested.detail()));
						return jar;
					}
					default -> {
						jar.setStatus(Status.INVALID, Component.translatable("ingame-modloader.status.nested", nested.displayName(), nested.detail()));
						return jar;
					}
				}
			}

			Collection<String> mixinConfigs = metadata.getMixinConfigs(EnvType.CLIENT);
			Set<String> loadedTargets = LateTransformers.loadedMixinTargets(root, mixinConfigs);

			if (!loadedTargets.isEmpty()) {
				jar.setStatus(Status.NEEDS_RESTART, Component.translatable("ingame-modloader.status.mixin_targets_loaded", describeClasses(loadedTargets)));
				return jar;
			}

			jar.mixinConfigs().addAll(mixinConfigs);

			if (metadata.getClassTweaker() != null) {
				Path classTweaker = root.resolve(metadata.getClassTweaker());
				Set<String> tweakedLoaded = LateTransformers.loadedClassTweakerTargets(classTweaker);

				if (!tweakedLoaded.isEmpty()) {
					jar.setStatus(Status.NEEDS_RESTART, Component.translatable("ingame-modloader.status.access_widener_loaded", describeClasses(tweakedLoaded)));
					return jar;
				}
			}

			return jar;
		} catch (Exception e) {
			IngameModLoader.LOGGER.warn("Failed to inspect {}", path, e);
			return new ModJar(path, null, Status.INVALID, Component.translatable("ingame-modloader.status.unreadable", String.valueOf(e.getMessage())));
		}
	}

	/** Switched off jars are only read for their name and icon, whether they could be loaded is checked once they are switched on. */
	private static ModJar inspectDisabled(Path path, Set<Path> loadedOrigins) {
		LoaderModMetadata metadata = null;

		try (FileSystem fs = FileSystems.newFileSystem(path)) {
			Path modJson = fs.getRootDirectories().iterator().next().resolve("fabric.mod.json");
			if (Files.isRegularFile(modJson)) metadata = readMetadata(modJson, path);
		} catch (Exception e) {
			IngameModLoader.LOGGER.warn("Failed to read {}", path, e);
		}

		Path enabledPath = ModFiles.enabledPath(path);

		if (loadedOrigins.contains(enabledPath) || RuntimeModLoader.wasLoadedThisSession(enabledPath)) {
			return new ModJar(path, false, metadata, Status.DISABLED_PENDING, Component.translatable("ingame-modloader.status.disabled_pending"));
		}

		Component detail = metadata != null && !metadata.getDescription().isEmpty()
				? Component.literal(metadata.getDescription())
				: Component.translatable("ingame-modloader.status.disabled");
		return new ModJar(path, false, metadata, Status.DISABLED, detail);
	}

	private static LoaderModMetadata readMetadata(Path modJson, Path jar) throws Exception {
		try (InputStream in = Files.newInputStream(modJson)) {
			return ModMetadataParser.parseMetadata(in, jar.toString(), List.of(), new VersionOverrides(),
					new DependencyOverrides(FabricLoader.getInstance().getConfigDir()), FabricLoader.getInstance().isDevelopmentEnvironment());
		}
	}

	/** Copies a jar-in-jar file to disk, because the class loader needs a real file to load classes from. */
	private static Path extractNested(Path outerJar, Path innerJar) throws IOException {
		String outerKey = Integer.toHexString((outerJar.toString() + Files.size(outerJar) + Files.getLastModifiedTime(outerJar)).hashCode());
		Path target = nestedJarDirectory().resolve(outerKey).resolve(innerJar.getFileName().toString());

		// An extracted jar may already be on the class path and locked, so it is never overwritten.
		if (!Files.exists(target)) {
			Files.createDirectories(target.getParent());
			Path temp = Files.createTempFile(target.getParent(), "nested", ".tmp");
			Files.copy(innerJar, temp, StandardCopyOption.REPLACE_EXISTING);
			Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
		}

		return target.toAbsolutePath().normalize();
	}

	private static String describeClasses(Set<String> classes) {
		String listed = classes.stream()
				.limit(MAX_LISTED_CLASSES)
				.map(name -> name.substring(name.lastIndexOf('.') + 1))
				.collect(Collectors.joining(", "));
		return classes.size() > MAX_LISTED_CLASSES ? listed + ", …" : listed;
	}

	private static Set<Path> loadedOrigins() {
		Set<Path> origins = new HashSet<>();

		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			if (mod.getOrigin().getKind() != ModOrigin.Kind.PATH) continue;

			for (Path path : mod.getOrigin().getPaths()) {
				origins.add(path.toAbsolutePath().normalize());
			}
		}

		return origins;
	}

	/**
	 * Marks every ready jar whose dependencies are neither loaded nor provided by another ready jar.
	 * Repeats until nothing changes, because a jar that becomes unloadable can break others.
	 */
	private static void resolveDependencies(List<ModJar> jars) {
		boolean changed = true;

		while (changed) {
			changed = false;
			Map<String, Version> available = new HashMap<>();

			for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
				provide(available, mod.getMetadata().getId(), mod.getMetadata().getProvides(), mod.getMetadata().getVersion());
			}

			for (ModJar jar : jars) {
				if (jar.isLoadable()) provideAll(available, jar);
			}

			for (ModJar jar : jars) {
				if (!jar.isLoadable()) continue;

				List<String> problems = new ArrayList<>();
				collectDependencyProblems(jar, available, problems);

				if (!problems.isEmpty()) {
					jar.setStatus(Status.MISSING_DEPENDENCIES, Component.translatable("ingame-modloader.status.missing_dependencies", String.join(", ", problems)));
					changed = true;
				}
			}
		}
	}

	private static void provideAll(Map<String, Version> available, ModJar jar) {
		if (jar.metadata() != null) {
			provide(available, jar.metadata().getId(), jar.metadata().getProvides(), jar.metadata().getVersion());
		}

		for (ModJar nested : jar.nested()) {
			provideAll(available, nested);
		}
	}

	private static void provide(Map<String, Version> available, String id, Collection<String> provides, Version version) {
		available.putIfAbsent(id, version);

		for (String alias : provides) {
			available.putIfAbsent(alias, version);
		}
	}

	private static void collectDependencyProblems(ModJar jar, Map<String, Version> available, List<String> problems) {
		if (jar.metadata() != null) {
			for (ModDependency dependency : jar.metadata().getDependencies()) {
				Version version = available.get(dependency.getModId());

				switch (dependency.getKind()) {
					case DEPENDS -> {
						if (version == null || !dependency.matches(version)) problems.add(describe(dependency, version));
					}
					case BREAKS -> {
						if (version != null && dependency.matches(version)) problems.add("!" + dependency.getModId() + " " + version.getFriendlyString());
					}
					default -> { }
				}
			}
		}

		for (ModJar nested : jar.nested()) {
			collectDependencyProblems(nested, available, problems);
		}
	}

	private static String describe(ModDependency dependency, Version found) {
		String requirement = dependency.getVersionRequirements().stream().map(Object::toString).collect(Collectors.joining(" || "));
		String text = requirement.isEmpty() || requirement.equals("*") ? dependency.getModId() : dependency.getModId() + " " + requirement;
		return found == null ? text : text + " (" + found.getFriendlyString() + ")";
	}
}

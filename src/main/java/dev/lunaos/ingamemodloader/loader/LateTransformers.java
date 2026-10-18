package dev.lunaos.ingamemodloader.loader;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.lib.classtweaker.api.ClassTweaker;
import net.fabricmc.loader.impl.lib.classtweaker.api.ClassTweakerReader;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.spongepowered.asm.mixin.FabricUtil;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.transformer.Config;

/**
 * Mixins and class tweakers (access wideners) are applied while a class is being loaded. For a mod
 * loaded at runtime they can therefore only work if none of their target classes have been loaded
 * yet. This class finds out whether that is the case and, if so, hooks them into the running game.
 */
final class LateTransformers {
	private static final String MIXIN_ANNOTATION = "Lorg/spongepowered/asm/mixin/Mixin;";

	private LateTransformers() {
	}

	/** Returns the target classes of the given mixin configs that are already loaded. */
	static Set<String> loadedMixinTargets(Path jarRoot, Collection<String> configs) throws IOException {
		Set<String> loaded = new LinkedHashSet<>();

		for (String target : mixinTargets(file -> Files.isRegularFile(jarRoot.resolve(file)) ? jarRoot.resolve(file) : null, configs)) {
			if (LoaderInternals.isClassLoaded(target)) loaded.add(target);
		}

		return loaded;
	}

	/**
	 * Returns the classes the mixins of the given configs target.
	 *
	 * @param files finds a file of the mod by its path inside the jar, or returns null if it does not exist
	 */
	static Set<String> mixinTargets(Function<String, @Nullable Path> files, Collection<String> configs) throws IOException {
		Set<String> targets = new LinkedHashSet<>();

		for (String configName : configs) {
			Path configFile = files.apply(configName);
			if (configFile == null) throw new IOException("Missing mixin config " + configName);

			JsonObject config;

			try (BufferedReader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
				config = JsonParser.parseReader(reader).getAsJsonObject();
			}

			String pkg = config.has("package") ? config.get("package").getAsString() : "";

			for (String mixin : mixinClasses(config)) {
				String className = pkg.isEmpty() ? mixin : pkg + "." + mixin;
				Path classFile = files.apply(className.replace('.', '/') + ".class");
				if (classFile == null) throw new IOException("Missing mixin class " + className);

				targets.addAll(readMixinTargets(Files.readAllBytes(classFile)));
			}
		}

		return targets;
	}

	private static List<String> mixinClasses(JsonObject config) {
		List<String> classes = new ArrayList<>();

		// Only "mixins" and "client" apply on the client, "server" mixins are never selected here.
		for (String key : List.of("mixins", "client")) {
			JsonElement element = config.get(key);
			if (element == null || !element.isJsonArray()) continue;

			for (JsonElement mixin : (JsonArray) element) {
				classes.add(mixin.getAsString());
			}
		}

		return classes;
	}

	private static List<String> readMixinTargets(byte[] classBytes) {
		List<String> targets = new ArrayList<>();

		new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
				if (!MIXIN_ANNOTATION.equals(descriptor)) return null;

				return new AnnotationVisitor(Opcodes.ASM9) {
					@Override
					public AnnotationVisitor visitArray(String name) {
						return new AnnotationVisitor(Opcodes.ASM9) {
							@Override
							public void visit(String ignored, Object value) {
								if (value instanceof Type type) {
									targets.add(type.getClassName());
								} else if (value instanceof String target) {
									targets.add(target.replace('/', '.'));
								}
							}
						};
					}
				};
			}
		}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

		return targets;
	}

	/** Registers the mod's mixin configs and makes Mixin pick them up immediately. */
	static void applyMixins(ModContainerImpl container, Collection<String> configs) throws ReflectiveOperationException {
		if (configs.isEmpty()) return;

		for (String config : configs) {
			Mixins.addConfiguration(config);
		}

		for (Config config : Mixins.getConfigs()) {
			if (!configs.contains(config.getName())) continue;

			IMixinConfig mixinConfig = config.getConfig();
			mixinConfig.decorate(FabricUtil.KEY_MOD_ID, container.getMetadata().getId());
			mixinConfig.decorate(FabricUtil.KEY_COMPATIBILITY, FabricUtil.COMPATIBILITY_LATEST);
		}

		// Mixin only looks for new configs before it transformed the first class of a phase, so the
		// selection has to be triggered by hand. The processor synchronizes on itself while transforming.
		MixinEnvironment environment = MixinEnvironment.getCurrentEnvironment();
		Object transformer = environment.getActiveTransformer();
		Field processorField = transformer.getClass().getDeclaredField("processor");
		processorField.setAccessible(true);
		Object processor = processorField.get(transformer);
		Method select = processor.getClass().getDeclaredMethod("select", MixinEnvironment.class);
		select.setAccessible(true);

		synchronized (processor) {
			select.invoke(processor, environment);
		}
	}

	/** Returns the already loaded classes the class tweaker of a mod would modify. */
	static Set<String> loadedClassTweakerTargets(Path classTweakerFile) throws IOException {
		ClassTweaker tweaker = ClassTweaker.newInstance();
		readClassTweaker(tweaker, classTweakerFile);
		Set<String> loaded = new LinkedHashSet<>();

		for (String target : tweaker.getTargets()) {
			String binaryName = target.replace('/', '.');
			if (LoaderInternals.isClassLoaded(binaryName)) loaded.add(binaryName);
		}

		return loaded;
	}

	static void applyClassTweaker(Path classTweakerFile) throws IOException {
		readClassTweaker(FabricLoaderImpl.INSTANCE.getClassTweaker(), classTweakerFile);
	}

	private static void readClassTweaker(ClassTweaker tweaker, Path file) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(file)) {
			ClassTweakerReader.create(tweaker).read(reader, LoaderInternals.runtimeNamespace());
		}
	}
}

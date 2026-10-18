package dev.lunaos.ingamemodloader.loader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.fabricmc.loader.api.FabricLoader;

import dev.lunaos.ingamemodloader.IngameModLoader;

/**
 * Turns mods on and off the same way other launchers do: a disabled mod is a jar renamed to
 * {@code .jar.disabled}, which Fabric Loader ignores.
 *
 * <p>The jar of a loaded mod is kept open by the class loader. On Windows it can then not be
 * renamed while the game runs, so such renames are remembered and done by {@link RenameHelper}
 * once the game has exited.
 */
public final class ModFiles {
	private static final String DISABLED_SUFFIX = ".disabled";
	private static final Set<Path> PENDING_DISABLE = new LinkedHashSet<>();
	private static boolean shutdownHookAdded;

	private ModFiles() {
	}

	public static boolean isDisabledJar(Path path) {
		String name = path.getFileName().toString();
		return name.endsWith(".jar" + DISABLED_SUFFIX) && !name.startsWith(".") && Files.isRegularFile(path);
	}

	/** The path the jar has (or would have) while it is enabled. */
	public static Path enabledPath(Path path) {
		String name = path.getFileName().toString();
		return name.endsWith(DISABLED_SUFFIX) ? path.resolveSibling(name.substring(0, name.length() - DISABLED_SUFFIX.length())) : path;
	}

	/** Whether the jar is switched off, but could not be renamed yet because the game is using it. */
	public static boolean isPendingDisable(Path path) {
		return PENDING_DISABLE.contains(path);
	}

	public static void setEnabled(ModJar jar, boolean enabled) throws IOException {
		Path path = jar.path();

		if (enabled) {
			if (PENDING_DISABLE.remove(path)) {
				savePending();
				return;
			}

			move(path, enabledPath(path));
			return;
		}

		try {
			move(path, path.resolveSibling(path.getFileName() + DISABLED_SUFFIX));
		} catch (IOException e) {
			if (Files.exists(path.resolveSibling(path.getFileName() + DISABLED_SUFFIX))) throw e;

			IngameModLoader.LOGGER.info("{} is in use, it will be disabled after the game was closed", path.getFileName());
			PENDING_DISABLE.add(path);
			savePending();
			addShutdownHook();
		}
	}

	private static void move(Path from, Path to) throws IOException {
		if (Files.exists(to)) throw new IOException(to.getFileName() + " already exists");

		Files.move(from, to);
	}

	/** Renames jars that could not be renamed during the last session, in case the helper did not run. */
	public static void applyPendingFromLastSession() {
		Path file = pendingFile();
		if (!Files.isRegularFile(file)) return;

		try {
			for (String line : Files.readAllLines(file)) {
				if (line.isBlank()) continue;

				Path path = Path.of(line);

				try {
					if (Files.exists(path)) move(path, path.resolveSibling(path.getFileName() + DISABLED_SUFFIX));
				} catch (IOException e) {
					// The jar is loaded again in this session, so it stays pending.
					PENDING_DISABLE.add(path);
				}
			}

			savePending();
			if (!PENDING_DISABLE.isEmpty()) addShutdownHook();
		} catch (IOException e) {
			IngameModLoader.LOGGER.warn("Failed to read {}", file, e);
		}
	}

	private static Path pendingFile() {
		return FabricLoader.getInstance().getConfigDir().resolve(IngameModLoader.MOD_ID).resolve("pending-disable.txt");
	}

	private static void savePending() {
		Path file = pendingFile();

		try {
			if (PENDING_DISABLE.isEmpty()) {
				Files.deleteIfExists(file);
			} else {
				Files.createDirectories(file.getParent());
				Files.write(file, PENDING_DISABLE.stream().map(Path::toString).toList());
			}
		} catch (IOException e) {
			IngameModLoader.LOGGER.warn("Failed to write {}", file, e);
		}
	}

	private static synchronized void addShutdownHook() {
		if (shutdownHookAdded) return;

		shutdownHookAdded = true;
		Runtime.getRuntime().addShutdownHook(new Thread(ModFiles::startRenameHelper, "ingame-modloader rename helper"));
	}

	/** Starts a separate Java process that renames the pending jars as soon as this process has exited. */
	private static void startRenameHelper() {
		if (PENDING_DISABLE.isEmpty()) return;

		try {
			String java = ProcessHandle.current().info().command().orElse("java");
			List<String> command = new ArrayList<>(List.of(java, "-cp", helperClassPath(), RenameHelper.class.getName(),
					Long.toString(ProcessHandle.current().pid()), pendingFile().toString()));
			new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
		} catch (Exception e) {
			IngameModLoader.LOGGER.warn("Failed to start the rename helper, the mods are disabled on the next start instead", e);
		}
	}

	private static String helperClassPath() {
		List<Path> paths = FabricLoader.getInstance().getModContainer(IngameModLoader.MOD_ID).orElseThrow().getOrigin().getPaths();
		return String.join(File.pathSeparator, paths.stream().map(Path::toString).toList());
	}
}

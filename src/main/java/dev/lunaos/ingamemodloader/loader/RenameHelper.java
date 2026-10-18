package dev.lunaos.ingamemodloader.loader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs as its own process after the game exited and renames the jars listed in the pending file to
 * {@code .jar.disabled}. Uses nothing but the JDK, it is started with only this mod's jar on the class path.
 */
public final class RenameHelper {
	private static final int ATTEMPTS = 20;

	private RenameHelper() {
	}

	public static void main(String[] args) throws Exception {
		long gamePid = Long.parseLong(args[0]);
		Path pendingFile = Path.of(args[1]);
		ProcessHandle.of(gamePid).ifPresent(game -> game.onExit().join());

		List<String> remaining = new ArrayList<>();

		for (String line : Files.readAllLines(pendingFile)) {
			if (line.isBlank()) continue;

			Path jar = Path.of(line);
			if (!rename(jar, jar.resolveSibling(jar.getFileName() + ".disabled"))) remaining.add(line);
		}

		if (remaining.isEmpty()) {
			Files.deleteIfExists(pendingFile);
		} else {
			Files.write(pendingFile, remaining);
		}
	}

	private static boolean rename(Path from, Path to) throws InterruptedException {
		for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
			try {
				if (!Files.exists(from)) return true;

				Files.move(from, to);
				return true;
			} catch (IOException e) {
				// Windows releases file handles shortly after the process is gone.
				Thread.sleep(250);
			}
		}

		return false;
	}
}

package dev.lunaos.ingamemodloader.loader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * A jar file found in the mods folder (or nested inside another jar) together with
 * everything the scanner found out about it.
 */
public final class ModJar {
	public enum Status {
		/** Can be loaded right now. */
		READY,
		/** Was loaded by this mod during the current session. */
		LOADED,
		/** Was already loaded when the game started (or provided by another mod). */
		ALREADY_LOADED,
		/** Is a valid mod but can only be loaded by restarting the game. */
		NEEDS_RESTART,
		MISSING_DEPENDENCIES,
		INVALID,
		/** Loading was attempted but failed. */
		FAILED,
		/** Switched off and not running. */
		DISABLED,
		/** Switched off, but loaded in this session, so it only stops running after a restart. */
		DISABLED_PENDING
	}

	private final Path path;
	private final boolean enabled;
	private final @Nullable LoaderModMetadata metadata;
	private final List<ModJar> nested = new ArrayList<>();
	private final List<String> mixinConfigs = new ArrayList<>();
	private Status status;
	private Component detail;

	ModJar(Path path, @Nullable LoaderModMetadata metadata, Status status, Component detail) {
		this(path, true, metadata, status, detail);
	}

	ModJar(Path path, boolean enabled, @Nullable LoaderModMetadata metadata, Status status, Component detail) {
		this.path = path;
		this.enabled = enabled;
		this.metadata = metadata;
		this.status = status;
		this.detail = detail;
	}

	public Path path() {
		return path;
	}

	/** Whether the jar is switched on, i.e. in the mods folder as a {@code .jar}. */
	public boolean enabled() {
		return enabled;
	}

	public @Nullable LoaderModMetadata metadata() {
		return metadata;
	}

	/** Library jars nested via jar-in-jar have no fabric.mod.json and are only added to the class path. */
	public boolean isLibrary() {
		return metadata == null && status == Status.READY;
	}

	public String id() {
		return metadata != null ? metadata.getId() : path.getFileName().toString();
	}

	public String displayName() {
		return metadata != null ? metadata.getName() : path.getFileName().toString();
	}

	public String version() {
		return metadata != null ? metadata.getVersion().getFriendlyString() : "";
	}

	public String description() {
		return metadata != null ? metadata.getDescription() : "";
	}

	public String fileName() {
		return path.getFileName().toString();
	}

	/** Nested jars that are not loaded yet and have to be loaded together with this one. */
	public List<ModJar> nested() {
		return Collections.unmodifiableList(nested);
	}

	void addNested(ModJar jar) {
		nested.add(jar);
	}

	List<String> mixinConfigs() {
		return mixinConfigs;
	}

	public Status status() {
		return status;
	}

	public Component detail() {
		return detail;
	}

	public boolean isLoadable() {
		return status == Status.READY;
	}

	void setStatus(Status status, Component detail) {
		this.status = status;
		this.detail = detail;
	}
}

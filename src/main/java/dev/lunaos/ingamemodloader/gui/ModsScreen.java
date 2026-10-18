package dev.lunaos.ingamemodloader.gui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.hash.Hashing;
import com.mojang.blaze3d.Blaze3D;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.SelectableEntry;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import dev.lunaos.ingamemodloader.IngameModLoader;
import dev.lunaos.ingamemodloader.loader.ModFiles;
import dev.lunaos.ingamemodloader.loader.ModJar;
import dev.lunaos.ingamemodloader.loader.ModJar.Status;
import dev.lunaos.ingamemodloader.loader.ModScanner;
import dev.lunaos.ingamemodloader.loader.RuntimeModLoader;

/**
 * Lists the mods in the mods folder like the resource pack screen does: switched off mods on the
 * left, switched on ones on the right. Mods that were switched on are loaded into the running game
 * when the screen is closed, if that is possible without a restart.
 */
public class ModsScreen extends Screen {
	private static final Component TITLE = Component.translatable("ingame-modloader.screen.title");
	private static final Component DISABLED_TITLE = Component.translatable("ingame-modloader.list.disabled");
	private static final Component ENABLED_TITLE = Component.translatable("ingame-modloader.list.enabled");
	private static final Component DRAG_AND_DROP = Component.translatable("ingame-modloader.screen.hint").withStyle(ChatFormatting.GRAY);
	private static final Component SEARCH = Component.translatable("ingame-modloader.screen.search").withStyle(EditBox.SEARCH_HINT_STYLE);
	private static final Component OPEN_FOLDER = Component.translatable("ingame-modloader.button.open_folder");
	private static final Component OPEN_FOLDER_TOOLTIP = Component.translatable("ingame-modloader.button.open_folder.tooltip");
	private static final Identifier DEFAULT_ICON = Identifier.withDefaultNamespace("textures/misc/unknown_pack.png");
	private static final Identifier SELECT_HIGHLIGHTED_SPRITE = Identifier.withDefaultNamespace("transferable_list/select_highlighted");
	private static final Identifier SELECT_SPRITE = Identifier.withDefaultNamespace("transferable_list/select");
	private static final Identifier UNSELECT_HIGHLIGHTED_SPRITE = Identifier.withDefaultNamespace("transferable_list/unselect_highlighted");
	private static final Identifier UNSELECT_SPRITE = Identifier.withDefaultNamespace("transferable_list/unselect");
	private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId();
	private static final int LIST_WIDTH = 200;
	private static final int RESCAN_INTERVAL_TICKS = 20;
	private static final int ERROR_BACKGROUND = 0xFF770000;

	private final Screen parent;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
	private final Map<Path, Identifier> icons = new HashMap<>();
	private @Nullable ModList disabledList;
	private @Nullable ModList enabledList;
	private @Nullable EditBox search;
	private List<ModJar> jars = List.of();
	private String folderFingerprint = "";
	private boolean changed;
	private int ticks;

	public ModsScreen(Screen parent) {
		super(TITLE);
		this.parent = parent;
	}

	@Override
	protected void init() {
		layout.setHeaderHeight(4 + 9 + 4 + 9 + 4 + 15 + 4);
		LinearLayout header = layout.addToHeader(LinearLayout.vertical().spacing(4));
		header.defaultCellSetting().alignHorizontallyCenter();
		header.addChild(new StringWidget(title, font));
		header.addChild(new StringWidget(DRAG_AND_DROP, font));
		search = header.addChild(new EditBox(font, 0, 0, LIST_WIDTH, 15, Component.empty()));
		search.setHint(SEARCH);
		search.setResponder(value -> populateLists());

		disabledList = layout.addToContents(new ModList(minecraft, height - 66, DISABLED_TITLE));
		enabledList = layout.addToContents(new ModList(minecraft, height - 66, ENABLED_TITLE));

		LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
		footer.addChild(Button.builder(OPEN_FOLDER, button -> openModsFolder()).tooltip(Tooltip.create(OPEN_FOLDER_TOOLTIP)).build());
		footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).build());

		layout.visitWidgets(this::addRenderableWidget);
		repositionElements();
		rescan();
	}

	@Override
	protected void setInitialFocus() {
		if (search != null) {
			setInitialFocus(search);
		} else {
			super.setInitialFocus();
		}
	}

	@Override
	protected void repositionElements() {
		layout.arrangeElements();
		if (disabledList != null) disabledList.updateSizeAndPosition(LIST_WIDTH, layout.getContentHeight(), width / 2 - 15 - LIST_WIDTH, layout.getHeaderHeight());
		if (enabledList != null) enabledList.updateSizeAndPosition(LIST_WIDTH, layout.getContentHeight(), width / 2 + 15, layout.getHeaderHeight());
	}

	@Override
	public void tick() {
		// Picks up jars that were added, removed or renamed with a file manager.
		if (++ticks % RESCAN_INTERVAL_TICKS == 0 && !folderFingerprint().equals(folderFingerprint)) {
			rescan();
		}
	}

	/** Switched on mods are loaded when leaving the screen, like resource packs are applied when leaving theirs. */
	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);

		List<ModJar> current = ModScanner.scan();
		List<ModJar> ready = current.stream().filter(ModJar::isLoadable).toList();
		int loaded = ready.isEmpty() ? 0 : RuntimeModLoader.load(ready, current);
		long failed = ready.stream().filter(jar -> jar.status() == Status.FAILED).count();
		long needRestart = current.stream().filter(jar -> jar.status() == Status.NEEDS_RESTART || jar.status() == Status.DISABLED_PENDING).count();

		if (!ready.isEmpty()) {
			SystemToast.add(minecraft.gui.toastManager(), TOAST, Component.translatable("ingame-modloader.toast.loaded", loaded),
					failed > 0 ? Component.translatable("ingame-modloader.toast.failed", failed) : null);
		} else if (changed && needRestart > 0) {
			SystemToast.add(minecraft.gui.toastManager(), TOAST, Component.translatable("ingame-modloader.toast.restart", needRestart), null);
		}
	}

	@Override
	public void removed() {
		icons.values().stream().filter(icon -> icon != DEFAULT_ICON).forEach(minecraft.getTextureManager()::release);
		icons.clear();
	}

	@Override
	public void onFilesDrop(List<Path> files) {
		Path modsDir = ModScanner.modsDirectory();
		int copied = 0;
		int skipped = 0;

		for (Path file : files) {
			Path target = modsDir.resolve(file.getFileName().toString());

			// Existing jars may be loaded and locked, so they are never overwritten.
			if (!ModScanner.isJar(file) || Files.exists(target) || Files.exists(target.resolveSibling(target.getFileName() + ".disabled"))) {
				skipped++;
				continue;
			}

			try {
				Files.createDirectories(modsDir);
				Files.copy(file, target);
				copied++;
			} catch (IOException e) {
				IngameModLoader.LOGGER.warn("Failed to copy {} to {}", file, target, e);
				skipped++;
			}
		}

		if (copied > 0) changed = true;
		SystemToast.add(minecraft.gui.toastManager(), TOAST, Component.translatable("ingame-modloader.toast.copied", copied),
				skipped > 0 ? Component.translatable("ingame-modloader.toast.skipped", skipped) : null);
		rescan();
	}

	private void toggle(ModJar jar) {
		try {
			ModFiles.setEnabled(jar, !jar.enabled());
			changed = true;
		} catch (IOException e) {
			IngameModLoader.LOGGER.warn("Failed to switch {}", jar.fileName(), e);
			SystemToast.add(minecraft.gui.toastManager(), TOAST, Component.translatable("ingame-modloader.toast.toggle_failed", jar.displayName()),
					Component.literal(String.valueOf(e.getMessage())));
		}

		rescan();
	}

	private void rescan() {
		folderFingerprint = folderFingerprint();
		jars = ModScanner.scan().stream().sorted(Comparator.comparing(ModJar::displayName, String.CASE_INSENSITIVE_ORDER)).toList();
		populateLists();
	}

	private void populateLists() {
		String filter = search != null ? search.getValue().toLowerCase(Locale.ROOT) : "";
		List<ModJar> shown = jars.stream().filter(jar -> matches(jar, filter)).toList();
		if (disabledList != null) disabledList.setJars(shown.stream().filter(jar -> !jar.enabled()).toList());
		if (enabledList != null) enabledList.setJars(shown.stream().filter(ModJar::enabled).toList());
	}

	private static boolean matches(ModJar jar, String filter) {
		return filter.isBlank()
				|| jar.displayName().toLowerCase(Locale.ROOT).contains(filter)
				|| jar.id().toLowerCase(Locale.ROOT).contains(filter)
				|| jar.description().toLowerCase(Locale.ROOT).contains(filter)
				|| jar.fileName().toLowerCase(Locale.ROOT).contains(filter);
	}

	private void openModsFolder() {
		try {
			Files.createDirectories(ModScanner.modsDirectory());
		} catch (IOException e) {
			IngameModLoader.LOGGER.warn("Failed to create mods folder", e);
		}

		Blaze3D.openPath(ModScanner.modsDirectory());
	}

	private static String folderFingerprint() {
		try (Stream<Path> files = Files.list(ModScanner.modsDirectory())) {
			return files.map(file -> {
				try {
					return file.getFileName() + ":" + Files.size(file) + ":" + Files.getLastModifiedTime(file).toMillis();
				} catch (IOException e) {
					return file.getFileName().toString();
				}
			}).sorted().collect(Collectors.joining("|"));
		} catch (IOException e) {
			return "";
		}
	}

	/** Reads the icon declared in the fabric.mod.json, falling back to the unknown pack icon. */
	private Identifier icon(ModJar jar) {
		return icons.computeIfAbsent(jar.path(), path -> {
			Optional<String> iconPath = jar.metadata() != null ? jar.metadata().getIconPath(64) : Optional.empty();
			if (iconPath.isEmpty()) return DEFAULT_ICON;

			try (FileSystem fs = FileSystems.newFileSystem(path)) {
				Path file = fs.getRootDirectories().iterator().next().resolve(iconPath.get());

				try (InputStream in = Files.newInputStream(file)) {
					Identifier id = Identifier.fromNamespaceAndPath(IngameModLoader.MOD_ID, "mod_icon/" + Hashing.sha1().hashUnencodedChars(path.toString()));
					minecraft.getTextureManager().register(id, new DynamicTexture(id::toString, NativeImage.read(in)));
					return id;
				}
			} catch (Exception e) {
				IngameModLoader.LOGGER.debug("Failed to load the icon of {}", path, e);
				return DEFAULT_ICON;
			}
		});
	}

	/** The second line of an entry: what happens with the mod, or its description if it is simply running. */
	private static Component statusLine(ModJar jar) {
		return switch (jar.status()) {
			case READY -> Component.translatable("ingame-modloader.status.ready").withColor(0xFF55FF55);
			case LOADED -> Component.translatable("ingame-modloader.status.loaded").withColor(0xFF55FF55);
			case ALREADY_LOADED -> jar.description().isEmpty()
					? Component.translatable("ingame-modloader.status.already_loaded").withColor(0xFF808080)
					: Component.literal(jar.description()).withColor(0xFF808080);
			case NEEDS_RESTART, DISABLED_PENDING -> jar.detail().copy().withColor(0xFFFFAA00);
			case MISSING_DEPENDENCIES, INVALID, FAILED -> jar.detail().copy().withColor(0xFFFFAAAA);
			case DISABLED -> jar.detail().copy().withColor(0xFF808080);
		};
	}

	private static boolean isError(ModJar jar) {
		return jar.status() == Status.MISSING_DEPENDENCIES || jar.status() == Status.INVALID || jar.status() == Status.FAILED;
	}

	private class ModList extends ObjectSelectionList<ModList.Entry> {
		private final Component title;

		ModList(Minecraft minecraft, int height, Component title) {
			super(minecraft, LIST_WIDTH, height, 33, 36);
			this.title = title;
			this.centerListVertically = false;
		}

		void setJars(List<ModJar> jars) {
			clearEntries();
			addEntry(new HeaderEntry(Component.empty().append(title).withStyle(ChatFormatting.UNDERLINE, ChatFormatting.BOLD)), (int) (9.0F * 1.5F));
			setSelected(null);
			jars.forEach(jar -> addEntry(new ModEntry(jar)));
			refreshScrollAmount();
		}

		@Override
		public int getRowWidth() {
			return width - 4;
		}

		@Override
		protected int scrollBarX() {
			return getRight() - scrollbarWidth();
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			return getSelected() != null ? getSelected().keyPressed(event) : super.keyPressed(event);
		}

		abstract class Entry extends ObjectSelectionList.Entry<Entry> {
			@Override
			public int getWidth() {
				return super.getWidth() - (scrollable() ? scrollbarWidth() : 0);
			}
		}

		class HeaderEntry extends Entry {
			private final Component text;

			HeaderEntry(Component text) {
				this.text = text;
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
				graphics.centeredText(font, text, getX() + getWidth() / 2, getContentYMiddle() - font.lineHeight / 2, -1);
			}

			@Override
			public Component getNarration() {
				return text;
			}
		}

		class ModEntry extends Entry implements SelectableEntry {
			private static final int ICON_SIZE = 32;
			private static final int TEXT_WIDTH = 157;
			private final ModJar jar;
			private final StringWidget nameWidget;
			private final MultiLineTextWidget statusWidget;

			ModEntry(ModJar jar) {
				this.jar = jar;
				MutableComponent name = Component.literal(jar.displayName());
				if (!jar.version().isEmpty()) name.append(Component.literal(" " + jar.version()).withColor(0xFF808080));
				nameWidget = new StringWidget(name, font);
				statusWidget = new MultiLineTextWidget(statusLine(jar), font);
				statusWidget.setMaxRows(2);
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
				if (isError(jar)) {
					graphics.fill(getContentX() - 1, getContentY() - 1, getContentRight() + 1, getContentBottom() + 1, ERROR_BACKGROUND);
				}

				graphics.blit(RenderPipelines.GUI_TEXTURED, icon(jar), getContentX(), getContentY(), 0.0F, 0.0F, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);

				if (hovered || getSelected() == this && ModList.this.isFocused()) {
					graphics.fill(getContentX(), getContentY(), getContentX() + ICON_SIZE, getContentY() + ICON_SIZE, 0xA0909090);
					int relX = mouseX - getContentX();
					int relY = mouseY - getContentY();

					if (jar.enabled()) {
						boolean highlighted = mouseOverLeftHalf(relX, relY, ICON_SIZE);
						graphics.blitSprite(RenderPipelines.GUI_TEXTURED, highlighted ? UNSELECT_HIGHLIGHTED_SPRITE : UNSELECT_SPRITE, getContentX(), getContentY(), ICON_SIZE, ICON_SIZE);
						if (highlighted) handleCursor(graphics);
					} else {
						boolean highlighted = mouseOverIcon(relX, relY, ICON_SIZE);
						graphics.blitSprite(RenderPipelines.GUI_TEXTURED, highlighted ? SELECT_HIGHLIGHTED_SPRITE : SELECT_SPRITE, getContentX(), getContentY(), ICON_SIZE, ICON_SIZE);
						if (highlighted) handleCursor(graphics);
					}

					// The status can be longer than two lines, the tooltip shows all of it.
					if (relX > ICON_SIZE) {
						List<FormattedCharSequence> tooltip = new ArrayList<>(font.split(statusLine(jar), 250));
						tooltip.add(Component.literal(jar.fileName()).withColor(0xFF606060).getVisualOrderText());
						graphics.setTooltipForNextFrame(tooltip, mouseX, mouseY);
					}
				}

				int textWidth = TEXT_WIDTH - (scrollable() ? 6 : 0);
				nameWidget.setMaxWidth(textWidth);
				nameWidget.setPosition(getContentX() + ICON_SIZE + 2, getContentY() + 1);
				nameWidget.extractRenderState(graphics, mouseX, mouseY, a);
				statusWidget.setMaxWidth(textWidth);
				statusWidget.setPosition(getContentX() + ICON_SIZE + 2, getContentY() + 12);
				statusWidget.extractRenderState(graphics, mouseX, mouseY, a);
			}

			@Override
			public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
				int relX = (int) event.x() - getContentX();
				int relY = (int) event.y() - getContentY();
				boolean onArrow = jar.enabled() ? mouseOverLeftHalf(relX, relY, ICON_SIZE) : mouseOverIcon(relX, relY, ICON_SIZE);

				if (onArrow || doubleClick) {
					toggle(jar);
					return true;
				}

				return super.mouseClicked(event, doubleClick);
			}

			@Override
			public boolean keyPressed(KeyEvent event) {
				if (event.isConfirmation()) {
					toggle(jar);
					return true;
				}

				return super.keyPressed(event);
			}

			@Override
			public Component getNarration() {
				return Component.translatable("narrator.select", jar.displayName());
			}
		}
	}
}

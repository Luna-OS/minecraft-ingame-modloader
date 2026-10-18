package dev.lunaos.ingamemodloader.gui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.mojang.blaze3d.Blaze3D;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import dev.lunaos.ingamemodloader.IngameModLoader;
import dev.lunaos.ingamemodloader.loader.ModJar;
import dev.lunaos.ingamemodloader.loader.ModScanner;
import dev.lunaos.ingamemodloader.loader.RuntimeModLoader;

/**
 * Lists the jars in the mods folder and loads the selected ones into the running game.
 * Jars can be added by dropping them onto the window or by putting them into the mods folder.
 */
public class ModLoaderScreen extends Screen {
	private static final Component TITLE = Component.translatable("ingame-modloader.screen.title");
	private static final Component HINT = Component.translatable("ingame-modloader.screen.hint");
	private static final Component EMPTY = Component.translatable("ingame-modloader.screen.empty");
	private static final Component LOAD_SELECTED = Component.translatable("ingame-modloader.button.load_selected");
	private static final Component LOAD_ALL = Component.translatable("ingame-modloader.button.load_all");
	private static final Component OPEN_FOLDER = Component.translatable("ingame-modloader.button.open_folder");
	private static final Component REFRESH = Component.translatable("ingame-modloader.button.refresh");
	private static final int RESCAN_INTERVAL_TICKS = 40;
	private static final int HINT_COLOR = 0xFFA0A0A0;
	private static final SystemToast.SystemToastId COPY_TOAST = new SystemToast.SystemToastId();
	private static final SystemToast.SystemToastId LOAD_TOAST = new SystemToast.SystemToastId();

	private final Screen parent;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 40, 60);
	private @Nullable ModList list;
	private @Nullable Button loadSelectedButton;
	private @Nullable Button loadAllButton;
	private List<ModJar> jars = List.of();
	private String folderFingerprint = "";
	private int ticks;

	public ModLoaderScreen(Screen parent) {
		super(TITLE);
		this.parent = parent;
	}

	@Override
	protected void init() {
		LinearLayout header = layout.addToHeader(LinearLayout.vertical().spacing(4));
		header.defaultCellSetting().alignHorizontallyCenter();
		header.addChild(new StringWidget(title, font));
		header.addChild(new StringWidget(HINT.copy().withColor(HINT_COLOR), font));

		list = layout.addToContents(new ModList(minecraft));

		LinearLayout footer = layout.addToFooter(LinearLayout.vertical().spacing(4));
		footer.defaultCellSetting().alignHorizontallyCenter();
		LinearLayout loadButtons = footer.addChild(LinearLayout.horizontal().spacing(8));
		loadSelectedButton = loadButtons.addChild(Button.builder(LOAD_SELECTED, button -> loadSelected()).width(151).build());
		loadAllButton = loadButtons.addChild(Button.builder(LOAD_ALL, button -> load(jars.stream().filter(ModJar::isLoadable).toList())).width(151).build());
		LinearLayout otherButtons = footer.addChild(LinearLayout.horizontal().spacing(8));
		otherButtons.addChild(Button.builder(OPEN_FOLDER, button -> openModsFolder()).width(98).build());
		otherButtons.addChild(Button.builder(REFRESH, button -> rescan()).width(98).build());
		otherButtons.addChild(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).width(98).build());

		layout.visitWidgets(this::addRenderableWidget);
		repositionElements();
		rescan();
	}

	@Override
	protected void repositionElements() {
		layout.arrangeElements();
		if (list != null) list.updateSize(width, layout);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void tick() {
		// Picks up jars that were put into the mods folder with a file manager.
		if (++ticks % RESCAN_INTERVAL_TICKS == 0 && !folderFingerprint().equals(folderFingerprint)) {
			rescan();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);

		if (jars.isEmpty() && list != null) {
			graphics.centeredText(font, EMPTY, width / 2, list.getY() + list.getHeight() / 2 - font.lineHeight / 2, HINT_COLOR);
		}
	}

	@Override
	public void onFilesDrop(List<Path> files) {
		Path modsDir = ModScanner.modsDirectory();
		int copied = 0;
		int skipped = 0;

		for (Path file : files) {
			Path target = modsDir.resolve(file.getFileName().toString());

			// Existing jars may be loaded and locked, so they are never overwritten.
			if (!ModScanner.isJar(file) || Files.exists(target)) {
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

		SystemToast.add(minecraft.gui.toastManager(), COPY_TOAST,
				Component.translatable("ingame-modloader.toast.copied", copied),
				skipped > 0 ? Component.translatable("ingame-modloader.toast.skipped", skipped) : null);
		rescan();
	}

	private void rescan() {
		String selectedPath = list != null && list.getSelected() != null ? list.getSelected().jar.path().toString() : null;
		folderFingerprint = folderFingerprint();
		jars = ModScanner.scan().stream().sorted(Comparator.comparingInt(ModLoaderScreen::sortOrder).thenComparing(ModJar::displayName, String.CASE_INSENSITIVE_ORDER)).toList();

		if (list != null) list.setJars(jars, selectedPath);
		updateButtons();
	}

	private void loadSelected() {
		if (list != null && list.getSelected() != null) load(List.of(list.getSelected().jar));
	}

	private void load(List<ModJar> selection) {
		if (selection.isEmpty()) return;

		int loaded = RuntimeModLoader.load(selection, jars);
		int failed = (int) jars.stream().filter(jar -> jar.status() == ModJar.Status.FAILED).count();

		SystemToast.add(minecraft.gui.toastManager(), LOAD_TOAST,
				Component.translatable("ingame-modloader.toast.loaded", loaded),
				failed > 0 ? Component.translatable("ingame-modloader.toast.failed", failed) : null);
		updateButtons();
	}

	private void openModsFolder() {
		try {
			Files.createDirectories(ModScanner.modsDirectory());
		} catch (IOException e) {
			IngameModLoader.LOGGER.warn("Failed to create mods folder", e);
		}

		Blaze3D.openPath(ModScanner.modsDirectory());
	}

	private void updateButtons() {
		if (loadSelectedButton != null) loadSelectedButton.active = list != null && list.getSelected() != null && list.getSelected().jar.isLoadable();
		if (loadAllButton != null) loadAllButton.active = jars.stream().anyMatch(ModJar::isLoadable);
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

	private static int sortOrder(ModJar jar) {
		return switch (jar.status()) {
			case READY -> 0;
			case FAILED, MISSING_DEPENDENCIES, NEEDS_RESTART -> 1;
			case LOADED -> 2;
			case INVALID -> 3;
			case ALREADY_LOADED -> 4;
		};
	}

	private static int statusColor(ModJar.Status status) {
		return switch (status) {
			case READY, LOADED -> 0xFF55FF55;
			case NEEDS_RESTART -> 0xFFFFAA00;
			case MISSING_DEPENDENCIES, INVALID, FAILED -> 0xFFFF5555;
			case ALREADY_LOADED -> 0xFF808080;
		};
	}

	private class ModList extends ObjectSelectionList<ModList.Entry> {
		private static final int ITEM_HEIGHT = 36;

		ModList(Minecraft minecraft) {
			super(minecraft, ModLoaderScreen.this.width, layout.getContentHeight(), layout.getHeaderHeight(), ITEM_HEIGHT);
		}

		void setJars(List<ModJar> jars, @Nullable String selectedPath) {
			replaceEntries(jars.stream().map(Entry::new).toList());
			setSelected(children().stream().filter(entry -> entry.jar.path().toString().equals(selectedPath)).findFirst().orElse(null));
			refreshScrollAmount();
		}

		@Override
		public void setSelected(@Nullable Entry selected) {
			super.setSelected(selected);
			updateButtons();
		}

		@Override
		public int getRowWidth() {
			return Math.min(width - 40, 360);
		}

		class Entry extends ObjectSelectionList.Entry<Entry> {
			final ModJar jar;

			Entry(ModJar jar) {
				this.jar = jar;
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
				int x = getContentX();
				int y = getContentY();
				int maxWidth = getContentWidth();

				String name = font.plainSubstrByWidth(jar.displayName(), maxWidth);
				graphics.text(font, name, x, y + 1, 0xFFFFFFFF);

				if (!jar.version().isEmpty()) {
					int versionX = x + font.width(name) + 4;
					graphics.text(font, font.plainSubstrByWidth(jar.version(), Math.max(0, x + maxWidth - versionX)), versionX, y + 1, 0xFF808080);
				}

				String detail = jar.detail().getString();
				String shownDetail = font.plainSubstrByWidth(detail, maxWidth);
				graphics.text(font, shownDetail, x, y + 12, statusColor(jar.status()));
				graphics.text(font, font.plainSubstrByWidth(jar.fileName(), maxWidth), x, y + 23, 0xFF606060);

				if (hovered && shownDetail.length() < detail.length()) {
					graphics.setTooltipForNextFrame(font.split(jar.detail(), 300), mouseX, mouseY);
				}
			}

			@Override
			public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
				ModList.this.setSelected(this);
				if (doubleClick && jar.isLoadable()) load(List.of(jar));
				return true;
			}

			@Override
			public Component getNarration() {
				return Component.translatable("narrator.select", jar.displayName());
			}
		}
	}
}

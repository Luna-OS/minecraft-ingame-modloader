package dev.lunaos.ingamemodloader;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import dev.lunaos.ingamemodloader.gui.ModLoaderScreen;

public class IngameModLoader implements ClientModInitializer {
	public static final String MOD_ID = "ingame-modloader";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final Component BUTTON_LABEL = Component.literal("+");
	private static final Component BUTTON_TOOLTIP = Component.translatable("ingame-modloader.button.tooltip");

	@Override
	public void onInitializeClient() {
		ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
			if (screen instanceof TitleScreen) addTitleScreenButton(minecraft, screen);
		});
	}

	private static void addTitleScreenButton(Minecraft minecraft, Screen screen) {
		List<AbstractWidget> widgets = Screens.getWidgets(screen);

		// Left of the options button is free in the vanilla layout, otherwise fall back to the top left corner.
		AbstractWidget options = widgets.stream().filter(widget -> isTranslation(widget.getMessage(), "menu.options")).findFirst().orElse(null);
		int x = options != null ? options.getX() - 24 : 4;
		int y = options != null ? options.getY() : 4;

		widgets.add(Button.builder(BUTTON_LABEL, button -> minecraft.gui.setScreen(new ModLoaderScreen(screen)))
				.bounds(x, y, 20, 20)
				.tooltip(Tooltip.create(BUTTON_TOOLTIP))
				.build());
	}

	private static boolean isTranslation(Component component, String key) {
		return component.getContents() instanceof TranslatableContents translatable && translatable.getKey().equals(key);
	}
}

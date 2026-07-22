package dev.santos.litedots;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.santos.litedots.chat.Messages;
import dev.santos.litedots.command.DotCommands;
import dev.santos.litedots.dot.DotStore;
import dev.santos.litedots.render.DotRenderer;

public class LiteDotsClient implements ClientModInitializer {
	public static final String MOD_ID = "litedots";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		Messages.init(LOGGER);
		Messages.logBridgeSelfTest(LOGGER);
		DotStore.load();
		DotCommands.register();
		DotRenderer.init();
		LOGGER.info("LiteDots initialized");
	}
}

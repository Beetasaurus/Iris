package net.coderbot.iris;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import net.coderbot.iris.config.IrisConfig;
import net.coderbot.iris.gui.ShaderPackScreen;
import net.coderbot.iris.pipeline.ShaderPipeline;
import net.coderbot.iris.postprocess.CompositeRenderer;
import net.coderbot.iris.shaderpack.ShaderPack;
import net.coderbot.iris.shaderpack.ShaderPackRef;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

import net.minecraft.client.options.KeyBinding;
import net.minecraft.client.util.InputUtil;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;

@Environment(EnvType.CLIENT)
public class Iris implements ClientModInitializer {
	public static final String MODID = "iris";
	public static final Logger logger = LogManager.getLogger(MODID);

	private static final Path shaderpacksDirectory = FabricLoader.getInstance().getGameDir().resolve("shaderpacks");

	private static ShaderPack currentPack;
	private static ShaderPipeline pipeline;
	private static CompositeRenderer compositeRenderer;
	private static IrisConfig irisConfig;
	public static KeyBinding reloadKeybind;

	@Override
	public void onInitializeClient() {
		try {
			Files.createDirectories(shaderpacksDirectory);
		} catch (IOException e) {
			Iris.logger.warn("Failed to create shaderpacks directory!");
			Iris.logger.catching(Level.WARN, e);
		}

		irisConfig = new IrisConfig();

		try {
			irisConfig.initialize();
		} catch (IOException e) {
			logger.error("Failed to initialize Iris configuration, default values will be used instead");
			logger.catching(Level.ERROR, e);
		}

		tryLoadShaderpack();

		reloadKeybind = KeyBindingHelper.registerKeyBinding(new KeyBinding("iris.keybind.reload", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "iris.keybinds"));
	}

	public static Path getShaderPackDir() {
		return shaderpacksDirectory;
	}

	private static void loadExternalShaderpack(String name) {
		Path shaderPackRoot = shaderpacksDirectory.resolve(name);
		Path shaderPackPath = shaderPackRoot.resolve("shaders");

		if (!Files.exists(shaderPackPath)) {
			logger.warn("The shaderpack " + name + " does not have a shaders directory, falling back to internal shaders");
			return;
		}

		try {
			currentPack = new ShaderPack(shaderPackPath);
		} catch (IOException e) {
			logger.error(String.format("Failed to load shaderpack \"%s\"! Falling back to internal shaders", irisConfig.getShaderPackName()));
			logger.catching(Level.ERROR, e);

			return;
		}

		logger.info("Using shaderpack: " + name);
	}

	private static void loadInternalShaderpack() {
		Path root = FabricLoader.getInstance().getModContainer("iris")
			.orElseThrow(() -> new RuntimeException("Failed to get the mod container for Iris!")).getRootPath();

		try {
			currentPack = new ShaderPack(root.resolve("shaders"));
		} catch (IOException e) {
			logger.error("Failed to load internal shaderpack!");
			throw new RuntimeException("Failed to load internal shaderpack!", e);
		}

		logger.info("Using internal shaders");
	}

	public static void tryLoadShaderpack() {
		currentPack = null;

		// Attempt to load an external shaderpack if it is available
		if(!irisConfig.isInternal()) {
			loadExternalShaderpack(irisConfig.getShaderPackName());
		}

		// If there is no external shaderpack or it failed to load for some reason, load the internal shaders
		if (currentPack == null) {
			loadInternalShaderpack();
		}
	}

	public static void reload() {
		tryLoadShaderpack();
		pipeline = null;
		compositeRenderer = null;
	}


	public static ShaderPipeline getPipeline() {
		if (pipeline == null) {
			pipeline = new ShaderPipeline(Objects.requireNonNull(currentPack));
		}

		return pipeline;
	}

	public static ShaderPack getCurrentPack() {
		return currentPack;
	}

	public static CompositeRenderer getCompositeRenderer() {
		if (compositeRenderer == null) {
			compositeRenderer = new CompositeRenderer(Objects.requireNonNull(currentPack));
		}

		return compositeRenderer;
	}

	public static IrisConfig getIrisConfig() {
		return irisConfig;
	}
}

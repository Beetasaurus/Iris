package net.coderbot.iris.pipeline;

import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Matrix4f;
import com.mojang.math.Vector3f;
import net.coderbot.batchedentityrendering.impl.BatchingDebugMessageHelper;
import net.coderbot.batchedentityrendering.impl.DrawCallTrackingRenderBuffers;
import net.coderbot.batchedentityrendering.impl.RenderBuffersExt;
import net.coderbot.iris.Iris;
import net.coderbot.iris.gl.IrisRenderSystem;
import net.coderbot.iris.gl.blending.BlendModeOverride;
import net.coderbot.iris.gl.framebuffer.GlFramebuffer;
import net.coderbot.iris.gl.texture.InternalTextureFormat;
import net.coderbot.iris.gui.option.IrisVideoSettings;
import net.coderbot.iris.layer.GbufferProgram;
import net.coderbot.iris.mixin.LevelRendererAccessor;
import net.coderbot.iris.pipeline.newshader.CoreWorldRenderingPipeline;
import net.coderbot.iris.rendertarget.RenderTargets;
import net.coderbot.iris.shaderpack.OptionalBoolean;
import net.coderbot.iris.shaderpack.PackDirectives;
import net.coderbot.iris.shaderpack.PackShadowDirectives;
import net.coderbot.iris.shaderpack.ProgramSource;
import net.coderbot.iris.shadow.ShadowMatrices;
import net.coderbot.iris.shadows.CullingDataCache;
import net.coderbot.iris.shadows.Matrix4fAccess;
import net.coderbot.iris.shadows.ShadowMapRenderer;
import net.coderbot.iris.shadows.ShadowRenderTargets;
import net.coderbot.iris.shadows.frustum.BoxCuller;
import net.coderbot.iris.shadows.frustum.CullEverythingFrustum;
import net.coderbot.iris.shadows.frustum.advanced.AdvancedShadowCullingFrustum;
import net.coderbot.iris.shadows.frustum.fallback.BoxCullingFrustum;
import net.coderbot.iris.shadows.frustum.fallback.NonCullingFrustum;
import net.coderbot.iris.uniforms.CameraUniforms;
import net.coderbot.iris.uniforms.CapturedRenderingState;
import net.coderbot.iris.uniforms.CelestialUniforms;
import net.coderbot.iris.vendored.joml.Vector3d;
import net.coderbot.iris.vendored.joml.Vector4f;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class ShadowRenderer implements ShadowMapRenderer {
	public static Matrix4f MODELVIEW;
	public static boolean ACTIVE = false;
	public static List<BlockEntity> visibleBlockEntities;
	private final float halfPlaneLength;
	private final float renderDistanceMultiplier;
	private final int resolution;
	private final float intervalSize;
	private final WorldRenderingPipeline pipeline;
	private final ShadowRenderTargets targets;
	private final OptionalBoolean packCullingState;
	public boolean packHasVoxelization;
	private final boolean packHasIndirectSunBounceGi;
	private final float sunPathRotation;
	private final RenderBuffers buffers;
	private final RenderBuffersExt RenderBuffersExt;
	private final List<MipmapPass> mipmapPasses = new ArrayList<>();
	private final String debugStringOverall;
	private String debugStringShadowDistance = "(unavailable)";
	private String debugStringShadowCulling = "(unavailable)";
	private String debugStringTerrain = "(unavailable)";
	private int renderedShadowEntities = 0;
	private int renderedShadowBlockEntities = 0;

	public ShadowRenderer(CoreWorldRenderingPipeline pipeline, ProgramSource shadow, PackDirectives directives, RenderTargets gbufferRenderTargets) {
		this.pipeline = pipeline;

		final PackShadowDirectives shadowDirectives = directives.getShadowDirectives();

		this.halfPlaneLength = shadowDirectives.getDistance();
		this.renderDistanceMultiplier = shadowDirectives.getDistanceRenderMul();
		this.resolution = shadowDirectives.getResolution();
		this.intervalSize = shadowDirectives.getIntervalSize();

		debugStringOverall = "half plane = " + halfPlaneLength + " meters @ " + resolution + "x" + resolution;

		if (shadowDirectives.getFov() != null) {
			// TODO: Support FOV in the shadow map for legacy shaders
			Iris.logger.warn("The shaderpack specifies a shadow FOV of " + shadowDirectives.getFov() + ", but Iris does not currently support perspective projections in the shadow pass.");
		}

		// TODO: Support more than two shadowcolor render targets
		this.targets = new ShadowRenderTargets(resolution, new InternalTextureFormat[]{
				// TODO: Custom shadowcolor format support
				InternalTextureFormat.RGBA,
				InternalTextureFormat.RGBA
		});

		// Note: Ensure that blending is properly overridden during the shadow pass. By default, blending is disabled
		//       in the shadow pass. Shader packs expect this for colored shadows from stained glass and nether portals
		//       to work properly.
		//
		// Note: Enabling blending in the shadow pass results in weird results since translucency sorting happens
		//       relative to the player camera, not the shadow camera, so we can't rely on chunks being properly
		//       sorted in the shadow pass.
		//
		//	On 1.17, this is handled for us by ExtendedShader.
		// - https://github.com/IrisShaders/Iris/issues/483
		// - https://github.com/IrisShaders/Iris/issues/987

		if (shadow != null) {
			// Assume that the shader pack is doing voxelization if a geometry shader is detected.
			// Also assume voxelization if image load / store is detected (set elsewhere)
			this.packHasVoxelization = shadow.getGeometrySource().isPresent();
			this.packCullingState = shadow.getParent().getPackDirectives().getCullingState();
		} else {
			this.packHasVoxelization = false;
			this.packCullingState = OptionalBoolean.DEFAULT;
		}

		ProgramSource[] composite = shadow.getParent().getComposite();

		if (composite.length > 0) {
			String fsh = composite[0].getFragmentSource().orElse("");

			// Detect the sun-bounce GI in SEUS Renewed and SEUS v11.
			// TODO: This is very hacky, we need a better way to detect sun-bounce GI.
			this.packHasIndirectSunBounceGi = fsh.contains("GI_QUALITY") && fsh.contains("GI_RENDER_RESOLUTION")
					&& fsh.contains("GI_RADIUS")
					&& fsh.contains("#define GI\t// Indirect lighting from sunlight.")
					&& !fsh.contains("//#define GI\t// Indirect lighting from sunlight.")
					&& !fsh.contains("// #define GI\t// Indirect lighting from sunlight.");
		} else {
			this.packHasIndirectSunBounceGi = false;
		}

		this.sunPathRotation = directives.getSunPathRotation();

		this.buffers = new RenderBuffers();

		if (this.buffers instanceof RenderBuffersExt) {
			this.RenderBuffersExt = (RenderBuffersExt) buffers;
		} else {
			this.RenderBuffersExt = null;
		}

		configureSamplingSettings(shadowDirectives);
	}

	public static PoseStack createShadowModelView(float sunPathRotation, float intervalSize) {
		// Determine the camera position
		Vector3d cameraPos = CameraUniforms.getUnshiftedCameraPosition();

		double cameraX = cameraPos.x;
		double cameraY = cameraPos.y;
		double cameraZ = cameraPos.z;

		// Set up our modelview matrix stack
		PoseStack modelView = new PoseStack();
		ShadowMatrices.createModelViewMatrix(modelView.last().pose(), getShadowAngle(), intervalSize, sunPathRotation, cameraX, cameraY, cameraZ);

		return modelView;
	}

	private static ClientLevel getLevel() {
		return Objects.requireNonNull(Minecraft.getInstance().level);
	}

	private static float getSkyAngle() {
		return getLevel().getTimeOfDay(CapturedRenderingState.INSTANCE.getTickDelta());
	}

	private static float getSunAngle() {
		float skyAngle = getSkyAngle();

		if (skyAngle < 0.75F) {
			return skyAngle + 0.25F;
		} else {
			return skyAngle - 0.75F;
		}
	}

	private static float getShadowAngle() {
		float shadowAngle = getSunAngle();

		if (!CelestialUniforms.isDay()) {
			shadowAngle -= 0.5F;
		}

		return shadowAngle;
	}

	private void configureSamplingSettings(PackShadowDirectives shadowDirectives) {
		final ImmutableList<PackShadowDirectives.DepthSamplingSettings> depthSamplingSettings =
				shadowDirectives.getDepthSamplingSettings();

		final ImmutableList<PackShadowDirectives.SamplingSettings> colorSamplingSettings =
				shadowDirectives.getColorSamplingSettings();

		RenderSystem.activeTexture(GL20C.GL_TEXTURE4);

		RenderSystem.bindTexture(getDepthTextureId());
		configureDepthSampler(getDepthTextureId(), depthSamplingSettings.get(0));

		RenderSystem.bindTexture(getDepthTextureNoTranslucentsId());
		configureDepthSampler(getDepthTextureNoTranslucentsId(), depthSamplingSettings.get(1));

		for (int i = 0; i < colorSamplingSettings.size(); i++) {
			int glTextureId = targets.getColorTextureId(i);

			RenderSystem.bindTexture(glTextureId);
			configureSampler(glTextureId, colorSamplingSettings.get(i));
		}

		RenderSystem.bindTexture(0);
		RenderSystem.activeTexture(GL20C.GL_TEXTURE0);
	}

	private void configureDepthSampler(int glTextureId, PackShadowDirectives.DepthSamplingSettings settings) {
		if (settings.getHardwareFiltering()) {
			// We have to do this or else shadow hardware filtering breaks entirely!
			RenderSystem.texParameter(GL20C.GL_TEXTURE_2D, GL20C.GL_TEXTURE_COMPARE_MODE, GL30C.GL_COMPARE_REF_TO_TEXTURE);
		}

		configureSampler(glTextureId, settings);
	}

	private void configureSampler(int glTextureId, PackShadowDirectives.SamplingSettings settings) {
		if (settings.getMipmap()) {
			int filteringMode = settings.getNearest() ? GL20C.GL_NEAREST_MIPMAP_NEAREST : GL20C.GL_LINEAR_MIPMAP_LINEAR;
			mipmapPasses.add(new MipmapPass(glTextureId, filteringMode));
		}

		if (!settings.getNearest()) {
			// Make sure that things are smoothed
			RenderSystem.texParameter(GL20C.GL_TEXTURE_2D, GL20C.GL_TEXTURE_MIN_FILTER, GL20C.GL_LINEAR);
			RenderSystem.texParameter(GL20C.GL_TEXTURE_2D, GL20C.GL_TEXTURE_MAG_FILTER, GL20C.GL_LINEAR);
		} else {
			RenderSystem.texParameter(GL20C.GL_TEXTURE_2D, GL20C.GL_TEXTURE_MIN_FILTER, GL20C.GL_NEAREST);
			RenderSystem.texParameter(GL20C.GL_TEXTURE_2D, GL20C.GL_TEXTURE_MAG_FILTER, GL20C.GL_NEAREST);
		}
	}

	private void generateMipmaps() {
		RenderSystem.activeTexture(GL20C.GL_TEXTURE4);

		for (MipmapPass mipmapPass : mipmapPasses) {
			RenderSystem.bindTexture(mipmapPass.getTexture());
			setupMipmappingForBoundTexture(mipmapPass.getTargetFilteringMode());
		}

		RenderSystem.bindTexture(0);
		RenderSystem.activeTexture(GL20C.GL_TEXTURE0);
	}

	private void setupMipmappingForBoundTexture(int filteringMode) {
		IrisRenderSystem.generateMipmaps(GL20C.GL_TEXTURE_2D);
		RenderSystem.texParameter(GL20C.GL_TEXTURE_2D, GL20C.GL_TEXTURE_MIN_FILTER, filteringMode);
	}

	private Frustum createShadowFrustum() {
		// TODO: Cull entities / block entities with Advanced Frustum Culling even if voxelization is detected.
		if ((packCullingState == OptionalBoolean.FALSE || packHasVoxelization || packHasIndirectSunBounceGi) && packCullingState != OptionalBoolean.TRUE) {
			double distance = halfPlaneLength * renderDistanceMultiplier;

			String reason;

			if (packCullingState == OptionalBoolean.FALSE) {
				reason = "(set by shader pack)";
			} else if (packHasVoxelization) {
				reason = "(voxelization detected)";
			} else {
				reason = "(indirect sunlight GI detected)";
			}

			if (distance <= 0 || distance > Minecraft.getInstance().options.getEffectiveRenderDistance() * 16) {
				debugStringShadowDistance = "render distance = " + Minecraft.getInstance().options.getEffectiveRenderDistance() * 16
						+ " blocks ";
				debugStringShadowDistance += Minecraft.getInstance().isLocalServer() ? "(capped by normal render distance)" : "(capped by normal/server render distance)";
				debugStringShadowCulling = "disabled " + reason;
				return new NonCullingFrustum();
			} else {
				debugStringShadowDistance = "render distance = " + distance + " blocks (set by shader pack)";
				debugStringShadowCulling = "distance only " + reason;
				BoxCuller boxCuller = new BoxCuller(distance);
				return new BoxCullingFrustum(boxCuller);
			}
		} else {
			BoxCuller boxCuller;

			double distance = halfPlaneLength * renderDistanceMultiplier;
			String setter = "(set by shader pack)";

			if (renderDistanceMultiplier < 0) {
				distance = IrisVideoSettings.shadowDistance * 16;
				setter = "(set by user)";
			}

			if (distance >= Minecraft.getInstance().options.getEffectiveRenderDistance() * 16) {
				debugStringShadowDistance = "render distance = " + Minecraft.getInstance().options.getEffectiveRenderDistance() * 16
						+ " blocks ";
				debugStringShadowDistance += Minecraft.getInstance().isLocalServer() ? "(capped by normal render distance)" : "(capped by normal/server render distance)";
				boxCuller = null;
			} else {
				debugStringShadowDistance = "render distance = " + distance + " blocks " + setter;

				if (distance == 0.0) {
					debugStringShadowCulling = "no shadows rendered";
					return new CullEverythingFrustum();
				}

				boxCuller = new BoxCuller(distance);
			}

			debugStringShadowCulling = "Advanced Frustum Culling enabled";

			Vector4f shadowLightPosition = new CelestialUniforms(sunPathRotation).getShadowLightPositionInWorldSpace();

			Vector3f shadowLightVectorFromOrigin =
					new Vector3f(shadowLightPosition.x(), shadowLightPosition.y(), shadowLightPosition.z());

			shadowLightVectorFromOrigin.normalize();

			return new AdvancedShadowCullingFrustum(CapturedRenderingState.INSTANCE.getGbufferModelView(),
					CapturedRenderingState.INSTANCE.getGbufferProjection(), shadowLightVectorFromOrigin, boxCuller);
		}
	}

	public void setupShadowViewport() {
		// Set up the shadow viewport
		RenderSystem.viewport(0, 0, resolution, resolution);

		// Set up and clear our framebuffer
		targets.getFramebuffer().bind();

		// TODO: Support shadow clear color directives & disable buffer clearing
		// Ensure that the color and depth values are cleared appropriately
		RenderSystem.clearColor(1.0f, 1.0f, 1.0f, 1.0f);
		RenderSystem.clearDepth(1.0f);
		RenderSystem.clear(GL11C.GL_DEPTH_BUFFER_BIT | GL11C.GL_COLOR_BUFFER_BIT, false);

		// Set up the viewport
		RenderSystem.viewport(0, 0, resolution, resolution);
	}

	@Override
	public void renderShadows(LevelRendererAccessor levelRenderer, Camera playerCamera) {
		Minecraft client = Minecraft.getInstance();

		levelRenderer.getLevel().getProfiler().popPush("shadows");
		ACTIVE = true;
		visibleBlockEntities = new ArrayList<>();

		// Create our camera
		PoseStack modelView = createShadowModelView(this.sunPathRotation, this.intervalSize);
		MODELVIEW = modelView.last().pose().copy();

		levelRenderer.getLevel().getProfiler().push("terrain_setup");

		if (levelRenderer instanceof CullingDataCache) {
			((CullingDataCache) levelRenderer).saveState();
		}

		levelRenderer.getLevel().getProfiler().push("initialize frustum");

		Frustum frustum = createShadowFrustum();

		// Determine the player camera position
		Vector3d cameraPos = CameraUniforms.getUnshiftedCameraPosition();

		double cameraX = cameraPos.x();
		double cameraY = cameraPos.y();
		double cameraZ = cameraPos.z();

		// Center the frustum on the player camera position
		frustum.prepare(cameraX, cameraY, cameraZ);

		levelRenderer.getLevel().getProfiler().pop();

		// Disable chunk occlusion culling - it's a bit complex to get this properly working with shadow rendering
		// as-is, however in the future it will be good to work on restoring it for a nice performance boost.
		//
		// TODO: Get chunk occlusion working with shadows
		boolean wasChunkCullingEnabled = client.smartCull;
		client.smartCull = false;

		// Always schedule a terrain update
		// TODO: Only schedule a terrain update if the sun / moon is moving, or the shadow map camera moved.
		((LevelRenderer) levelRenderer).needsUpdate();

		// Execute the vanilla terrain setup / culling routines using our shadow frustum.
		levelRenderer.invokeSetupRender(playerCamera, frustum, false, false);

		// Don't forget to increment the frame counter! This variable is arbitrary and only used in terrain setup,
		// and if it's not incremented, the vanilla culling code will get confused and think that it's already seen
		// chunks during traversal, and break rendering in concerning ways.
		//worldRenderer.setFrameId(worldRenderer.getFrameId() + 1);

		client.smartCull = wasChunkCullingEnabled;

		levelRenderer.getLevel().getProfiler().popPush("terrain");

		pipeline.pushProgram(GbufferProgram.NONE);
		pipeline.beginShadowRender();

		setupShadowViewport();

		// Set up our orthographic projection matrix and load it into RenderSystem
		float[] orthoMatrix = ShadowMatrices.createOrthoMatrix(halfPlaneLength);

		Matrix4f shadowProjection = new Matrix4f();
		((Matrix4fAccess) (Object) shadowProjection).copyFromArray(orthoMatrix);

		IrisRenderSystem.setShadowProjection(shadowProjection);

		// Disable backface culling
		// This partially works around an issue where if the front face of a mountain isn't visible, it casts no
		// shadow.
		//
		// However, it only partially resolves issues of light leaking into caves.
		//
		// TODO: Better way of preventing light from leaking into places where it shouldn't
		RenderSystem.disableCull();

		// Render all opaque terrain
		levelRenderer.invokeRenderChunkLayer(RenderType.solid(), modelView, cameraX, cameraY, cameraZ, shadowProjection);
		levelRenderer.invokeRenderChunkLayer(RenderType.cutout(), modelView, cameraX, cameraY, cameraZ, shadowProjection);
		levelRenderer.invokeRenderChunkLayer(RenderType.cutoutMipped(), modelView, cameraX, cameraY, cameraZ, shadowProjection);

		// TODO: Restore entity & block entity rendering

		// Reset our viewport in case Sodium overrode it
		RenderSystem.viewport(0, 0, resolution, resolution);

		levelRenderer.getLevel().getProfiler().popPush("entities");

		// Get the current tick delta. Normally this is the same as client.getTickDelta(), but when the game is paused,
		// it is set to a fixed value.
		final float tickDelta = CapturedRenderingState.INSTANCE.getTickDelta();

		// Create a constrained shadow frustum for entities to avoid rendering faraway entities in the shadow pass
		// TODO: Make this configurable and disable-able
		final Frustum entityShadowFrustum = frustum; // createEntityShadowFrustum(modelView);
		// entityShadowFrustum.setPosition(cameraX, cameraY, cameraZ);

		// Render nearby entities
		//
		// Note: We must use a separate BuilderBufferStorage object here, or else very weird things will happen during
		// rendering.
		if (RenderBuffersExt != null) {
			RenderBuffersExt.beginLevelRendering();
		}

		if (buffers instanceof DrawCallTrackingRenderBuffers) {
			((DrawCallTrackingRenderBuffers) buffers).resetDrawCounts();
		}

		MultiBufferSource.BufferSource bufferSource = buffers.bufferSource();
		EntityRenderDispatcher dispatcher = levelRenderer.getEntityRenderDispatcher();

		renderedShadowEntities = renderEntities(levelRenderer, dispatcher, bufferSource, modelView, tickDelta, entityShadowFrustum, cameraX, cameraY, cameraZ);

		levelRenderer.getLevel().getProfiler().popPush("build blockentities");

		renderedShadowBlockEntities = renderBlockEntities(modelView, tickDelta, cameraX, cameraY, cameraZ, bufferSource);

		levelRenderer.getLevel().getProfiler().popPush("draw entities");

		// NB: Don't try to draw the translucent parts of entities afterwards. It'll cause problems since some
		// shader packs assume that everything drawn afterwards is actually translucent and should cast a colored
		// shadow...
		bufferSource.endBatch();

		copyPreTranslucentDepth(levelRenderer);

		levelRenderer.getLevel().getProfiler().popPush("translucent terrain");

		// TODO: Prevent these calls from scheduling translucent sorting...
		// It doesn't matter a ton, since this just means that they won't be sorted in the normal rendering pass.
		// Just something to watch out for, however...
		levelRenderer.invokeRenderChunkLayer(RenderType.translucent(), modelView, cameraX, cameraY, cameraZ, shadowProjection);
		// Note: Apparently tripwire isn't rendered in the shadow pass.
		// levelRenderer.invokeRenderLayer(RenderLayer.getTripwire(), modelView, cameraX, cameraY, cameraZ, shadowProjection);

		if (RenderBuffersExt != null) {
			RenderBuffersExt.endLevelRendering();
		}

		IrisRenderSystem.restorePlayerProjection();

		debugStringTerrain = ((LevelRenderer) levelRenderer).getChunkStatistics();

		levelRenderer.getLevel().getProfiler().popPush("generate mipmaps");

		generateMipmaps();

		levelRenderer.getLevel().getProfiler().pop();

		// Restore backface culling
		RenderSystem.enableCull();

		pipeline.endShadowRender();
		Minecraft.getInstance().getMainRenderTarget().bindWrite(false);

		// Restore the old viewport
		RenderSystem.viewport(0, 0, client.getWindow().getWidth(), client.getWindow().getHeight());

		if (levelRenderer instanceof CullingDataCache) {
			((CullingDataCache) levelRenderer).restoreState();
		}

		visibleBlockEntities = null;
		ACTIVE = false;
		levelRenderer.getLevel().getProfiler().popPush("updatechunks");

		BlendModeOverride.restore();
	}

	private int renderBlockEntities(PoseStack modelView, float tickDelta, double cameraX, double cameraY, double cameraZ, MultiBufferSource.BufferSource bufferSource) {
		int shadowBlockEntities = 0;

		for (BlockEntity entity : visibleBlockEntities) {
			modelView.pushPose();
			BlockPos pos = entity.getBlockPos();
			modelView.translate(pos.getX() - cameraX, pos.getY() - cameraY, pos.getZ() - cameraZ);
			Minecraft.getInstance().getBlockEntityRenderDispatcher().render(entity, tickDelta, modelView, bufferSource);
			modelView.popPose();

			shadowBlockEntities++;
		}

		return shadowBlockEntities;
	}

	private int renderEntities(LevelRendererAccessor levelRenderer, EntityRenderDispatcher dispatcher, MultiBufferSource.BufferSource bufferSource, PoseStack modelView, float tickDelta, Frustum frustum, double cameraX, double cameraY, double cameraZ) {
		levelRenderer.getLevel().getProfiler().push("cull");

		List<Entity> renderedEntities = new ArrayList<>(32);

		// TODO: I'm sure that this can be improved / optimized.
		for (Entity entity : getLevel().entitiesForRendering()) {
			if (!dispatcher.shouldRender(entity, frustum, cameraX, cameraY, cameraZ) || entity.isSpectator()) {
				continue;
			}

			renderedEntities.add(entity);
		}

		levelRenderer.getLevel().getProfiler().popPush("sort");

		// Sort the entities by type first in order to allow vanilla's entity batching system to work better.
		renderedEntities.sort(Comparator.comparingInt(entity -> entity.getType().hashCode()));

		levelRenderer.getLevel().getProfiler().popPush("build entity geometry");

		for (Entity entity : renderedEntities) {
			levelRenderer.invokeRenderEntity(entity, cameraX, cameraY, cameraZ, tickDelta, modelView, bufferSource);
		}

		return renderedEntities.size();
	}

	private void copyPreTranslucentDepth(LevelRendererAccessor levelRenderer) {
		levelRenderer.getLevel().getProfiler().popPush("translucent depth copy");

		// Copy the content of the depth texture before rendering translucent content.
		// This is needed for the shadowtex0 / shadowtex1 split.
		targets.getFramebuffer().bindAsReadBuffer();
		RenderSystem.activeTexture(GL20C.GL_TEXTURE0);
		RenderSystem.bindTexture(targets.getDepthTextureNoTranslucents().getTextureId());
		IrisRenderSystem.copyTexImage2D(GL20C.GL_TEXTURE_2D, 0, GL20C.GL_DEPTH_COMPONENT, 0, 0, resolution, resolution, 0);
		RenderSystem.bindTexture(0);
	}

	@Override
	public void addDebugText(List<String> messages) {
		messages.add("[Iris] Shadow Maps: " + debugStringOverall);
		messages.add("[Iris] Shadow Distance: " + debugStringShadowDistance);
		messages.add("[Iris] Shadow Culling: " + debugStringShadowCulling);
		messages.add("[Iris] Shadow Terrain: " + debugStringTerrain);
		messages.add("[Iris] Shadow Entities: " + getEntitiesDebugString());
		messages.add("[Iris] Shadow Block Entities: " + getBlockEntitiesDebugString());

		if (buffers instanceof DrawCallTrackingRenderBuffers) {
			DrawCallTrackingRenderBuffers drawCallTracker = (DrawCallTrackingRenderBuffers) buffers;
			messages.add("[Iris] Shadow Entity Batching: " + BatchingDebugMessageHelper.getDebugMessage(drawCallTracker));
		}
	}

	private String getEntitiesDebugString() {
		return renderedShadowEntities + "/" + Minecraft.getInstance().level.getEntityCount();
	}

	private String getBlockEntitiesDebugString() {
		return renderedShadowBlockEntities + ""; // TODO: + "/" + MinecraftClient.getInstance().world.blockEntities.size();
	}

	@Override
	public int getDepthTextureId() {
		return targets.getDepthTexture().getTextureId();
	}

	@Override
	public int getDepthTextureNoTranslucentsId() {
		return targets.getDepthTextureNoTranslucents().getTextureId();
	}

	// TODO: Support more shadow color textures as well as support there being no shadow color textures.
	@Override
	public int getColorTexture0Id() {
		return targets.getColorTextureId(0);
	}

	@Override
	public int getColorTexture1Id() {
		return targets.getColorTextureId(1);
	}

	@Override
	public ShadowRenderTargets getRenderTargets() {
		return targets;
	}

	@Override
	public void destroy() {
		this.targets.destroy();
	}

	public GlFramebuffer getFramebuffer() {
		return targets.getFramebuffer();
	}

	private static class MipmapPass {
		private final int texture;
		private final int targetFilteringMode;

		public MipmapPass(int texture, int targetFilteringMode) {
			this.texture = texture;
			this.targetFilteringMode = targetFilteringMode;
		}

		public int getTexture() {
			return texture;
		}

		public int getTargetFilteringMode() {
			return targetFilteringMode;
		}
	}
}

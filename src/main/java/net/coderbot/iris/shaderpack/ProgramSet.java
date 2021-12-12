package net.coderbot.iris.shaderpack;

import net.coderbot.iris.Iris;
import net.coderbot.iris.shaderpack.include.AbsolutePackPath;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public class ProgramSet {
	private final PackDirectives packDirectives;

	private final ProgramSource shadow;

	private final ProgramSource[] shadowcomp;
	private final ProgramSource[] prepare;

	private final ProgramSource gbuffersBasic;
	private final ProgramSource gbuffersBeaconBeam;
	private final ProgramSource gbuffersTextured;
	private final ProgramSource gbuffersTexturedLit;
	private final ProgramSource gbuffersTerrain;
	private final ProgramSource gbuffersDamagedBlock;
	private final ProgramSource gbuffersSkyBasic;
	private final ProgramSource gbuffersSkyTextured;
	private final ProgramSource gbuffersClouds;
	private final ProgramSource gbuffersWeather;
	private final ProgramSource gbuffersEntities;
	private final ProgramSource gbuffersEntitiesGlowing;
	private final ProgramSource gbuffersGlint;
	private final ProgramSource gbuffersEntityEyes;
	private final ProgramSource gbuffersBlock;
	private final ProgramSource gbuffersHand;

	private final ProgramSource[] deferred;

	private final ProgramSource gbuffersWater;
	private final ProgramSource gbuffersHandWater;

	private final ProgramSource[] composite;
	private final ProgramSource compositeFinal;

	private final ShaderPack pack;

	public ProgramSet(AbsolutePackPath directory, Function<AbsolutePackPath, String> sourceProvider,
					  ShaderProperties shaderProperties, ShaderPack pack) {
		this.packDirectives = new PackDirectives(PackRenderTargetDirectives.BASELINE_SUPPORTED_RENDER_TARGETS, shaderProperties);
		this.pack = pack;

		this.shadow = readProgramSource(directory, sourceProvider, "shadow", this, pack);

		this.shadowcomp = readProgramArray(directory, sourceProvider, "shadowcomp", pack);
		this.prepare = readProgramArray(directory, sourceProvider, "prepare", pack);

		this.gbuffersBasic = readProgramSource(directory, sourceProvider, "gbuffers_basic", this, pack);
		this.gbuffersBeaconBeam = readProgramSource(directory, sourceProvider, "gbuffers_beaconbeam", this, pack);
		this.gbuffersTextured = readProgramSource(directory, sourceProvider, "gbuffers_textured", this, pack);
		this.gbuffersTexturedLit = readProgramSource(directory, sourceProvider, "gbuffers_textured_lit", this, pack);
		this.gbuffersTerrain = readProgramSource(directory, sourceProvider, "gbuffers_terrain", this, pack);
		this.gbuffersDamagedBlock = readProgramSource(directory, sourceProvider, "gbuffers_damagedblock", this, pack);
		this.gbuffersSkyBasic = readProgramSource(directory, sourceProvider, "gbuffers_skybasic", this, pack);
		this.gbuffersSkyTextured = readProgramSource(directory, sourceProvider, "gbuffers_skytextured", this, pack);
		this.gbuffersClouds = readProgramSource(directory, sourceProvider, "gbuffers_clouds", this, pack);
		this.gbuffersWeather = readProgramSource(directory, sourceProvider, "gbuffers_weather", this, pack);
		this.gbuffersEntities = readProgramSource(directory, sourceProvider, "gbuffers_entities", this, pack);
		this.gbuffersEntitiesGlowing = readProgramSource(directory, sourceProvider, "gbuffers_entities_glowing", this, pack);
		this.gbuffersGlint = readProgramSource(directory, sourceProvider, "gbuffers_armor_glint", this, pack);
		this.gbuffersEntityEyes = readProgramSource(directory, sourceProvider, "gbuffers_spidereyes", this, pack);
		this.gbuffersBlock = readProgramSource(directory, sourceProvider, "gbuffers_block", this, pack);
		this.gbuffersHand = readProgramSource(directory, sourceProvider, "gbuffers_hand", this, pack);

		this.deferred = readProgramArray(directory, sourceProvider, "deferred", pack);

		this.gbuffersWater = readProgramSource(directory, sourceProvider, "gbuffers_water", this, pack);
		this.gbuffersHandWater = readProgramSource(directory, sourceProvider, "gbuffers_hand_water", this, pack);

		this.composite = readProgramArray(directory, sourceProvider, "composite", pack);
		this.compositeFinal = readProgramSource(directory, sourceProvider, "final", this, pack);

		locateDirectives();
	}

	private ProgramSource[] readProgramArray(AbsolutePackPath directory,
											 Function<AbsolutePackPath, String> sourceProvider, String name,
											 ShaderPack pack) {
		ProgramSource[] programs = new ProgramSource[16];

		for (int i = 0; i < programs.length; i++) {
			String suffix = i == 0 ? "" : Integer.toString(i);

			programs[i] = readProgramSource(directory, sourceProvider, name + suffix, this, pack);
		}

		return programs;
	}

	private void locateDirectives() {
		List<ProgramSource> programs = new ArrayList<>();

		programs.add(shadow);
		programs.addAll(Arrays.asList(shadowcomp));
		programs.addAll(Arrays.asList(prepare));

		programs.addAll (Arrays.asList(
				gbuffersBasic, gbuffersBeaconBeam, gbuffersTextured, gbuffersTexturedLit, gbuffersTerrain,
				gbuffersDamagedBlock, gbuffersSkyBasic, gbuffersSkyTextured, gbuffersClouds, gbuffersWeather,
				gbuffersEntities, gbuffersEntitiesGlowing, gbuffersGlint, gbuffersEntityEyes, gbuffersBlock,
				gbuffersHand
		));

		programs.addAll(Arrays.asList(deferred));
		programs.add(gbuffersWater);
		programs.add(gbuffersHandWater);
		programs.addAll(Arrays.asList(composite));
		programs.add(compositeFinal);

		DispatchingDirectiveHolder packDirectiveHolder = new DispatchingDirectiveHolder();

		packDirectives.acceptDirectivesFrom(packDirectiveHolder);

		for (ProgramSource source : programs) {
			if (source == null) {
				continue;
			}

			source.getFragmentSource().map(ConstDirectiveParser::findDirectives).ifPresent(directives -> {
				for (ConstDirectiveParser.ConstDirective directive : directives) {
					packDirectiveHolder.processDirective(directive);
				}
			});
		}

		packDirectives.getRenderTargetDirectives().getRenderTargetSettings().forEach((index, settings) -> {
			Iris.logger.debug("Render target settings for colortex" + index + ": " + settings);
		});
	}

	public Optional<ProgramSource> getShadow() {
		return shadow.requireValid();
	}

	public ProgramSource[] getShadowComposite() {
		return shadowcomp;
	}

	public ProgramSource[] getPrepare() {
		return prepare;
	}

	public Optional<ProgramSource> getGbuffersBasic() {
		return gbuffersBasic.requireValid();
	}

	public Optional<ProgramSource> getGbuffersBeaconBeam() {
		return gbuffersBeaconBeam.requireValid();
	}

	public Optional<ProgramSource> getGbuffersTextured() {
		return gbuffersTextured.requireValid();
	}

	public Optional<ProgramSource> getGbuffersTexturedLit() {
		return gbuffersTexturedLit.requireValid();
	}

	public Optional<ProgramSource> getGbuffersTerrain() {
		return gbuffersTerrain.requireValid();
	}

	public Optional<ProgramSource> getGbuffersDamagedBlock() {
		return gbuffersDamagedBlock.requireValid();
	}

	public Optional<ProgramSource> getGbuffersSkyBasic() {
		return gbuffersSkyBasic.requireValid();
	}

	public Optional<ProgramSource> getGbuffersSkyTextured() {
		return gbuffersSkyTextured.requireValid();
	}

	public Optional<ProgramSource> getGbuffersClouds() {
		return gbuffersClouds.requireValid();
	}

	public Optional<ProgramSource> getGbuffersWeather() {
		return gbuffersWeather.requireValid();
	}

	public Optional<ProgramSource> getGbuffersEntities() {
		return gbuffersEntities.requireValid();
	}

	public Optional<ProgramSource> getGbuffersEntitiesGlowing() {
		return gbuffersEntitiesGlowing.requireValid();
	}

	public Optional<ProgramSource> getGbuffersGlint() {
		return gbuffersGlint.requireValid();
	}

	public Optional<ProgramSource> getGbuffersEntityEyes() {
		return gbuffersEntityEyes.requireValid();
	}

	public Optional<ProgramSource> getGbuffersBlock() {
		return gbuffersBlock.requireValid();
	}

	public Optional<ProgramSource> getGbuffersHand() {
		return gbuffersHand.requireValid();
	}

	public ProgramSource[] getDeferred() {
		return deferred;
	}

	public Optional<ProgramSource> getGbuffersWater() {
		return gbuffersWater.requireValid();
	}

	public Optional<ProgramSource> getGbuffersHandWater() {
		return gbuffersHandWater.requireValid();
	}

	public ProgramSource[] getComposite() {
		return composite;
	}

	public Optional<ProgramSource> getCompositeFinal() {
		return compositeFinal.requireValid();
	}

	public PackDirectives getPackDirectives() {
		return packDirectives;
	}

	public ShaderPack getPack() {
		return pack;
	}

	private static ProgramSource readProgramSource(AbsolutePackPath directory,
												   Function<AbsolutePackPath, String> sourceProvider, String program,
												   ProgramSet programSet, ShaderPack pack) {
		AbsolutePackPath vertexPath = directory.resolve(program + ".vsh");
		String vertexSource = sourceProvider.apply(vertexPath);

		AbsolutePackPath geometryPath = directory.resolve(program + ".gsh");
		String geometrySource = sourceProvider.apply(geometryPath);

		AbsolutePackPath fragmentPath = directory.resolve(program + ".fsh");
		String fragmentSource = sourceProvider.apply(fragmentPath);

		return new ProgramSource(program, vertexSource, geometrySource, fragmentSource, programSet, pack.getShaderProperties());
	}
}

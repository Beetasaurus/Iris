package net.coderbot.iris.mixin.texunits;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.coderbot.iris.texunits.TextureUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Slice;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Mixin(DefaultVertexFormat.class)
@Environment(EnvType.CLIENT)
public class MixinDefaultVertexFormat {
	/*TODO(21w10a): Replace texunit hooks
	@ModifyConstant(method = "<clinit>", constant = @Constant(intValue = 1), require = 1, slice = @Slice(
		from = @At(value = "FIELD", target = "Lnet/minecraft/client/render/VertexFormats;TEXTURE_ELEMENT:Lnet/minecraft/client/render/VertexFormatElement;"),
		to = @At(value = "FIELD", target = "Lnet/minecraft/client/render/VertexFormats;OVERLAY_ELEMENT:Lnet/minecraft/client/render/VertexFormatElement;")
	))
	private static int iris$fixOverlayTextureUnit(int samplerId) {
		return TextureUnit.OVERLAY.getSamplerId();
	}

	@ModifyConstant(method = "<clinit>", constant = @Constant(intValue = 2, ordinal = 0), require = 1, allow = 1, slice = @Slice(
		from = @At(value = "FIELD", target = "Lnet/minecraft/client/render/VertexFormats;OVERLAY_ELEMENT:Lnet/minecraft/client/render/VertexFormatElement;"),
		to = @At(value = "FIELD", target = "Lnet/minecraft/client/render/VertexFormats;LIGHT_ELEMENT:Lnet/minecraft/client/render/VertexFormatElement;")
	))
	private static int iris$fixLightmapTextureUnit(int samplerId) {
		return TextureUnit.LIGHTMAP.getSamplerId();
	}*/
}

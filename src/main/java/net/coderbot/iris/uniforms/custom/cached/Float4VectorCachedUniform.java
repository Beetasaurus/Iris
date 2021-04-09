package net.coderbot.iris.uniforms.custom.cached;

import net.coderbot.iris.gl.uniform.UniformUpdateFrequency;
import net.coderbot.iris.parsing.VectorType;
import net.coderbot.iris.vendored.joml.Vector4f;
import org.lwjgl.opengl.GL21;

import java.util.function.Supplier;

public class Float4VectorCachedUniform extends VectorCachedUniform<Vector4f> {
	
	public Float4VectorCachedUniform(UniformUpdateFrequency updateFrequency, Supplier<Vector4f> supplier) {
		super(updateFrequency, new Vector4f(), supplier);
	}
	
	@Override
	protected void setFrom(Vector4f other) {
		this.cached.set(other);
	}
	
	@Override
	protected void push() {
		GL21.glUniform4f(this.getLocation(), this.cached.x, this.cached.y, this.cached.z, this.cached.w);
	}
	
	@Override
	public VectorType getType() {
		return VectorType.VEC4;
	}
}

package net.coderbot.iris.uniforms.custom.cached;

import kroppeb.stareval.function.FunctionReturn;
import kroppeb.stareval.function.Type;
import net.coderbot.iris.gl.uniform.FloatSupplier;
import net.coderbot.iris.gl.uniform.UniformUpdateFrequency;
import org.lwjgl.opengl.GL21;

public class FloatCachedUniform extends CachedUniform {
	
	final private FloatSupplier supplier;
	private float cached;
	
	public FloatCachedUniform(UniformUpdateFrequency updateFrequency, FloatSupplier supplier) {
		super(updateFrequency);
		this.supplier = supplier;
	}
	
	@Override
	protected boolean doUpdate(){
		float prev = this.cached;
		this.cached = this.supplier.getAsFloat();
		return prev != cached;
	}
	
	@Override
	protected void push(){
		GL21.glUniform1f(this.getLocation(), this.cached);
	}
	
	@Override
	public void writeTo(FunctionReturn functionReturn){
		functionReturn.floatReturn = this.cached;
	}
	
	@Override
	public Type getType() {
		return Type.Float;
	}
}

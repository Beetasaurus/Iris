package kroppeb.stareval.function;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import kroppeb.stareval.expression.Expression;

import java.util.Map;

public class BasicFunctionContext implements FunctionContext {
	final private Map<String, Expression> variables = new Object2ObjectOpenHashMap<>();
	
	public void setVariable(String name, Expression value){
		variables.put(name, value);
	}
	
	public void setIntVariable(String name, int value){
		variables.put(name, (c,r) -> r.intReturn = value);
	}
	
	public void setFloatVariable(String name, float value){
		variables.put(name, (c,r) -> r.floatReturn = value);
	}
	
	@Override
	public Expression getVariable(String name) {
		Expression expression = variables.get(name);
		if(expression == null)
			throw new RuntimeException("Variable hasn't been set: " + name);
		return expression;
	}
}

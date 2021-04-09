package kroppeb.stareval.token;

import kroppeb.stareval.parser.BinaryOp;

public class BinaryOperatorToken extends Token {
	public final BinaryOp op;
	
	public BinaryOperatorToken(BinaryOp op) {
		this.op = op;
	}
	
	@Override
	public String toString() {
		return "BinaryOp{" + op + ")}";
	}
}

package pl.gamedev.robocompo;

import java.util.Vector;

public class ScriptResult {
	Soldier soldier;
	Vector<FunctionCall> calls = new Vector<FunctionCall>();

	public ScriptResult(Soldier soldier) {
		this.soldier = soldier;
	}
}
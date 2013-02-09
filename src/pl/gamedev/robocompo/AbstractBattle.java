package pl.gamedev.robocompo;

public interface AbstractBattle {
	// ping
	public abstract long getBattleTime();

	public abstract boolean isFinished();

	public abstract Stats getScriptStats(String key);

	public abstract BattleStats getBattleStats();

	public abstract Exception registerScript(String key, String source, String label);

	public abstract Object registerTeam(String key, String scriptKey, int faction, int nsoldiers, float x, float y);

	public abstract Object pushInput(String teamKey, long soldierID, String value);

	public abstract boolean start();

	public abstract Object setPointer(String teamKey, long soldierID, int x, int y);
}

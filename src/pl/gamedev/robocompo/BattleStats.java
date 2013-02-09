package pl.gamedev.robocompo;

public class BattleStats {
	int[] stats = new int[] { 0, 0, 0 };
	int[] kills = new int[] { 0, 0, 0 };
	long duration = 0;
	int winner = -1;

	public Object execute(AbstractBattle battle) {
		return battle.getBattleStats();
	}
}

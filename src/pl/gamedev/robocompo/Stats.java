package pl.gamedev.robocompo;

import java.io.Serializable;

class Stats implements Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = 6673615826255409285L;
	public boolean winner = false;
	public int kills = 0;
	public int deaths = 0;
	public int hits = 0;
	public int shots = 0;
	public int timeouts = 0;
	public int errors = 0;
	public int executions = 0;
	public long total_time = 0;
	public int hits_taken = 0;
	public int damage = 0;
	public int damage_taken = 0;
	public int hp_regen = 0;
	public int faction = 1;
	public int friendly_hits = 0;
	public int friendly_damage = 0;
	public int friendly_kills = 0;
	
}
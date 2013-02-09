package pl.gamedev.robocompo;

import java.applet.Applet;
import java.awt.Color;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.geom.AffineTransform;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Scanner;
import java.util.Stack;
import java.util.TreeSet;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import sun.org.mozilla.javascript.internal.NativeArray;
import sun.org.mozilla.javascript.internal.NativeObject;
import sun.org.mozilla.javascript.internal.ScriptableObject;

class RowMap {
	final Vector<Soldier>[] rows = new Vector[200];

	Vector<Soldier> emptyVector = new Vector<Soldier>();

	public void mark(Soldier soldier, int row) {
		if (row < 0 || row >= rows.length)
			return;
		if (rows[row] == null)
			rows[row] = new Vector<Soldier>();
		rows[row].add(soldier);
	}

	public void clear() {
		for (Vector row : rows)
			if (row != null)
				row.clear();
	}

	public Vector<Soldier> query(int row) {
		if (row < 0 || row >= rows.length)
			return emptyVector;
		if (rows[row] == null)
			return emptyVector;
		return rows[row];
	}
}

class Event implements Serializable {
	public static AtomicInteger references = new AtomicInteger(0);

	long battleTime;
	float theta;
	float distance;
	String message;

	public Event(long battleTime, float theta, float distance, String message) {
		synchronized (references) {
			references.incrementAndGet();
		}
		this.battleTime = battleTime;
		this.theta = theta;
		this.distance = distance;
		this.message = message;
	}

	@Override
	protected void finalize() throws Throwable {
		synchronized (references) {
			references.decrementAndGet();
		}
		super.finalize();
	}
}

class Soldier {
	// stan w jaki znajduje się jednostka
	public final static int STATE_WAIT = 1 << 1;
	public final static int STATE_ROTATE_LEFT = 1 << 2;
	public final static int STATE_ROTATE_RIGHT = 1 << 3;
	public final static int STATE_ROTATE_FAST = 1 << 6;
	public final static int STATE_MOVE = 1 << 4;
	public final static int STATE_SHOOT = 1 << 5;

	// identyfikator frakcji
	public final static int FACTION_A = 1;
	public final static int FACTION_B = 2;
	public final static int FACTION_C = 3;

	// dodatkowe zmienne odpowiedzialne za mechanikę
	public final static float SHOOT_COOLDOWN = 1f; // seconds

	public Soldier(Soldier parent) {
		this.x = parent.x;
		this.y = parent.y;
		this.radius = parent.radius;
		this.dir = parent.dir;
	}

	public Soldier(String scriptKey, long id, long teamId, int faction, float x, float y, float radius, float dir, byte order) {
		this.scriptKey = scriptKey;
		this.id = id;
		this.teamId = teamId;
		this.faction = faction;
		this.vvx = this.x = x;
		this.vvy = this.y = y;
		this.radius = radius;
		this.dir = dir;
		this.minX = x - radius;
		this.minY = y - radius;
		this.maxX = x + radius;
		this.maxY = y + radius;
		this.future = new Soldier(this);
		this.order = order;

		ScriptableObject.putProperty(status, "id", id);
	}

	public boolean commit() {
		if (future == null)
			return false;
		boolean changed = false;
		changed |= x != future.x;
		if (x != future.x) {
			this.minX = future.x - radius;
			this.maxX = future.x + radius;
			this.vvx = future.x + vx;
		} else
			this.vvx = future.x;
		x = future.x;
		changed |= y != future.y;
		if (y != future.y) {
			this.minY = future.y - radius;
			this.maxY = future.y + radius;
			this.vvy = future.y + vy;
		} else
			this.vvy = future.y;
		y = future.y;
		fx = future.fx;
		fy = future.fy;
		changed |= dir != future.dir;
		dir = future.dir;
		changed |= hitPoints != future.hitPoints;
		ScriptableObject.putProperty(status, "hitPoints", hitPoints = future.hitPoints);
		ScriptableObject.putProperty(status, "state", state = future.state);
		ScriptableObject.putProperty(status, "cooldown", cooldown = future.cooldown);
		return changed;
	}

	public String scriptKey;

	float x, y, vvx, vvy, vx = 0, vy = 0, dir, radius;
	float minX, minY, maxX, maxY;
	float fx = 0, fy = 0; // jednostki się wzajemnie odpychają
	int hitPoints = 100;
	int state = STATE_WAIT;
	float cooldown = 0;
	int faction;
	long id, teamId;
	Vector events = new Vector() {
		@Override
		public synchronized boolean add(Object e) {
			if (hitPoints <= 0)
				return false;
			return super.add(e);
		}
	};
	Vector viewport = new Vector();
	NativeObject status = new NativeObject();

	Soldier future;
	public byte order;
	public int[] pointer;

	public void update(float dt, Battle battle) {
		// if ((state & STATE_WAIT) > 0)
		// return;
		if ((state & STATE_MOVE) > 0) {
			future.x += Math.cos(dir) * dt * 10;
			future.y += Math.sin(dir) * dt * 10;
		}

		if (Math.sqrt(fx * fx + fy * fy) > 0) {
			future.x += fx * dt;
			future.y += fy * dt;
			future.fx = fx * 0.9f;
			future.fy = fy * 0.9f;
		} else
			future.fx = future.fy = 0;

		vx = -(x - future.x) / dt;
		vy = -(y - future.y) / dt;

		if ((state & STATE_ROTATE_LEFT) > 0) {
			future.dir -= dt / ((state & STATE_ROTATE_FAST) > 0 ? 1 : 20);
		}

		if ((state & STATE_ROTATE_RIGHT) > 0) {
			future.dir += dt / ((state & STATE_ROTATE_FAST) > 0 ? 1 : 20);
		}

		if ((state & STATE_SHOOT) > 0 && cooldown <= 0) {
			future.cooldown = SHOOT_COOLDOWN;
			battle.addBullet(new Bullet(this, (float) (x + Math.cos(dir) * radius * 1.1), (float) (y + Math.sin(dir) * radius * 1.1), dir, battle.battleTime));
		}

		if (cooldown > 0)
			future.cooldown -= dt;
	}

	public void setState(int bits, boolean set) {
		if ((bits & STATE_WAIT) > 0 && set) {
			future.state = STATE_WAIT;
			return;
		} else if ((future.state & STATE_WAIT) > 0)
			future.state = future.state ^ STATE_WAIT;

		if ((bits & STATE_MOVE) > 0)
			future.state = set ? future.state | STATE_MOVE : (future.state & STATE_MOVE) > 0 ? future.state ^ STATE_MOVE : future.state;

		if ((bits & STATE_ROTATE_LEFT) > 0) {
			future.state = set ? future.state | STATE_ROTATE_LEFT : future.state ^ STATE_ROTATE_LEFT;
			if (set && (future.state & STATE_ROTATE_RIGHT) > 0)
				future.state = future.state ^ STATE_ROTATE_RIGHT;
		}

		if ((bits & STATE_ROTATE_RIGHT) > 0) {
			future.state = set ? future.state | STATE_ROTATE_RIGHT : future.state ^ STATE_ROTATE_RIGHT;
			if (set && (future.state & STATE_ROTATE_LEFT) > 0)
				future.state = future.state ^ STATE_ROTATE_LEFT;
		}

		if ((bits & STATE_ROTATE_FAST) > 0)
			future.state = set ? future.state | STATE_ROTATE_FAST : (future.state & STATE_ROTATE_FAST) > 0 ? future.state ^ STATE_ROTATE_FAST : future.state;

		if ((bits & STATE_SHOOT) > 0)
			future.state = set ? future.state | STATE_SHOOT : (future.state & STATE_SHOOT) > 0 ? future.state ^ STATE_SHOOT : future.state;

	};
}

class Obstacle {
	public Obstacle(int x, int y, int width, int height) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
	}

	int x, y; // left, top
	int width, height;
}

class Bullet {
	public Bullet(Bullet parent) {
		this.x = parent.x;
		this.y = parent.y;
		this.dir = parent.dir;
	}

	public Bullet(Soldier soldier, float x, float y, float dir, long makeTime) {
		this.soldier = soldier;
		this.x = x;
		this.y = y;
		this.dir = dir;
		this.makeTime = makeTime;

		this.future = new Bullet(this);
	}

	public void commit() {
		if (future == null)
			return;
		x = future.x;
		y = future.y;
		dir = future.dir;

	}

	float x, y, vx = 0, vy = 0, dir;
	long id, makeTime, deathTime;
	Soldier soldier;

	Bullet future;

	public void update(float dt, Battle battle) {
		future.x += Math.cos(dir) * dt * 100;
		future.y += Math.sin(dir) * dt * 100;
		vx = -(x - future.x) / dt;
		vy = -(y - future.y) / dt;
	}
}

public class Battle implements AbstractBattle {

	public static final int TYPE_TEAM = 1 << 1;
	public static final int TYPE_ALLY = 1 << 2;
	public static final int TYPE_ENEMY = 1 << 3;
	public static final int TYPE_CENTER = 1 << 4;
	public static final int TYPE_BULLET = 1 << 5;
	public static final int TYPE_OBSTACLE = 1 << 6;
	public static final int TYPE_DEAD = 1 << 7;
	public static final int TYPE_POINTER = 1 << 8;

	private ConcurrentLinkedQueue<Soldier> soldiers = new ConcurrentLinkedQueue<Soldier>() {
		@Override
		public synchronized boolean add(Soldier soldier) {
			soldiersMap.put(soldier.id, soldier);
			return super.add(soldier);
		}

		public boolean remove(Object o) {
			if (o instanceof Soldier)
				soldiersMap.remove(((Soldier) o).id);
			return super.remove(o);
		};
	};
	private HashMap<Long, Soldier> soldiersMap = new HashMap<Long, Soldier>();
	private Stack<Soldier> newSoldiers = new Stack<Soldier>();
	private ConcurrentLinkedQueue<Obstacle> obstacles = new ConcurrentLinkedQueue<Obstacle>();
	private ConcurrentLinkedQueue<Bullet> bullets = new ConcurrentLinkedQueue<Bullet>();

	private Stack<Byte>[] groupSlots = new Stack[3];
	{
		for (int i = 0; i < 3; i++) {
			groupSlots[i] = new Stack();
			TreeSet<Byte> set = new TreeSet(new Comparator() {
				@Override
				public int compare(Object s1, Object s2) {
					// TODO Auto-generated method stub
					return Math.random() < Math.random() ? -1 : Math.random() > Math.random() ? 1 : s1.equals(s2) ? 0 : -1;
				}
			});
			for (byte j = 0; j < 6; j++)
				set.add(new Byte(j));
			for (byte j : set)
				groupSlots[i].push(j);
		}
	}

	RowMap rowMap = new RowMap();

	long battleTime = 0;
	private float dt = 0.01f;

	ConcurrentHashMap<String, ScriptSandbox> scripts = new ConcurrentHashMap<String, ScriptSandbox>();
	ConcurrentHashMap<String, Stats> scriptsStats = new ConcurrentHashMap<String, Stats>();

	public Battle() {

	}

	private long bulletCounter = 0;
	private long soldierCounter = 0;
	private long teamCounter = 0;

	public void addBullet(Bullet bullet) {
		bullets.add(bullet);
		bullet.id = bulletCounter++;
	}

	ConcurrentHashMap<Integer, AtomicInteger> factionStats = new ConcurrentHashMap<Integer, AtomicInteger>();
	ConcurrentHashMap<Integer, AtomicInteger> factionKills = new ConcurrentHashMap<Integer, AtomicInteger>();
	{
		factionStats.put(1, new AtomicInteger(0));
		factionStats.put(2, new AtomicInteger(0));
		factionStats.put(3, new AtomicInteger(0));
		factionKills.put(1, new AtomicInteger(0));
		factionKills.put(2, new AtomicInteger(0));
		factionKills.put(3, new AtomicInteger(0));
	}

	Vector<String> teams = new Vector<String>();

	Stack<ScriptTask> executionStack = new Stack<ScriptTask>();

	int winner = -1;

	ExecutorService executor = Executors.newFixedThreadPool(6);

	public void update() {
		// cx = Context.enter();
		battleTime += 1000 * dt;

		while (!newSoldiers.isEmpty()) {
			Soldier soldier = newSoldiers.pop();
			changedSoldiers.put(soldier, true);
			soldiers.add(soldier);
		}
		//
		if (battleTime > 5000 && battleTime % 500 == 0) {
			if (soldiers.size() > 0) {
				int alive = 3;
				for (int i = 1; i <= 3; i++)
					if (factionStats.get(i).intValue() <= 0)
						alive--;
			}
			//
			for (Soldier soldier : soldiers) {
				if (soldier.hitPoints > 0) {
					// uruchom skrypt tylko dla
					// �ywych
					// jednostek
					if (soldier.future.hitPoints < 100)
						soldier.future.hitPoints += 1;

					double ax = Math.cos(soldier.dir);
					double ay = Math.sin(soldier.dir);
					double ndir = Math.atan2(ay, ax);
					double atan = soldier.dir - Math.atan2(1000 - soldier.y, 1000 - soldier.x);
					atan = Math.atan2(Math.sin(atan), Math.cos(atan));
					soldier.viewport.clear();
					soldier.viewport.add(new NativeArray(new Object[] { 0, atan, Math.sqrt(Math.pow(1000 - soldier.x, 2) + Math.pow(1000 - soldier.y, 2)), 0, TYPE_CENTER }));
					if (soldier.pointer != null) {
						atan = soldier.dir - Math.atan2(soldier.pointer[1] - soldier.y, soldier.pointer[0] - soldier.x);
						atan = Math.atan2(Math.sin(atan), Math.cos(atan));
						soldier.viewport.add(new NativeArray(new Object[] { -1, atan, Math.sqrt(Math.pow(soldier.pointer[0] - soldier.x, 2) + Math.pow(soldier.pointer[1] - soldier.y, 2)), 0,
								TYPE_POINTER }));
					}
					for (Soldier object : soldiers)
						if (object != soldier) {
							atan = Math.atan2(object.y - soldier.y, object.x - soldier.x);
							atan = ndir - atan;
							atan = Math.atan2(Math.sin(atan), Math.cos(atan));

							double vatan = Math.atan2(object.vvy - soldier.vvy, object.vvx - soldier.vvx);
							vatan = ndir - vatan;
							vatan = Math.atan2(Math.sin(vatan), Math.cos(vatan));
							vatan = atan - vatan;

							Object[] viewport = new Object[] { object.id, atan, Math.sqrt(Math.pow(object.x - soldier.x, 2) + Math.pow(object.y - soldier.y, 2)), vatan,
									object.hitPoints <= 0 ? TYPE_DEAD : (object.faction == soldier.faction ? TYPE_ALLY : TYPE_ENEMY), object.cooldown };
							soldier.viewport.add(new NativeArray(viewport.clone()));
							if (soldier.teamId == object.teamId && object.hitPoints > 0) {
								viewport[4] = TYPE_TEAM;
								soldier.viewport.add(new NativeArray(viewport));
							}
						}

					ScriptTask scriptTask = (ScriptTask) scripts.get(soldier.scriptKey).execute(battleTime, soldier, new NativeArray(soldier.viewport.toArray()),
							new NativeArray(soldier.events.toArray()), soldier.status);
					executionStack.add(scriptTask);
					executor.execute(scriptTask);
					soldier.events.clear();
				}
			}

			long t = System.currentTimeMillis();

			for (ScriptTask result : executionStack) {
				if (result == null)
					continue;
				try {
					ScriptResult scriptResult = null;
					scriptResult = result.get();
					applyScriptResult(scriptResult);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			executionStack.clear();
		}

		for (Soldier soldier : soldiers) {
			soldier.update(dt, this);
			if (soldier.y <= 8)
				soldier.future.fy += 1;
			if (soldier.x <= 8)
				soldier.future.fx += 1;
			if (soldier.y >= 1992)
				soldier.future.fy -= 1;
			if (soldier.x >= 1992)
				soldier.future.fx -= 1;
			int lx = (int) Math.floor(soldier.x / 10);
			rowMap.mark(soldier, lx - 1);
			rowMap.mark(soldier, lx);
			rowMap.mark(soldier, lx + 1);
		}

		for (Bullet bullet : bullets) {
			bullet.update(dt, this);
			if (bullet.x > 2000 || bullet.y > 2000 || bullet.x < 0 || bullet.y < 0) {
				deadBullets.add(bullet);
				bullet.deathTime = battleTime;
				bullet.soldier.events.add(new Event(battleTime, 0, 0, "miss"));
			} else {
				Vector<Soldier> rowQuery = rowMap.query((int) Math.floor(bullet.x / 10));
				for (Soldier querySoldier : rowQuery) {
					if (bullet.x < querySoldier.minX || bullet.x > querySoldier.maxX || bullet.y < querySoldier.minY || bullet.y > querySoldier.maxY)
						continue;
					rowQuery = new Vector<Soldier>(rowQuery);
					rowQuery.addAll(rowMap.query((int) Math.floor(bullet.x / 10) - 1));
					rowQuery.addAll(rowMap.query((int) Math.floor(bullet.x / 10) + 1));
					if (Math.sqrt(Math.pow(bullet.x - querySoldier.x, 2) + Math.pow(bullet.y - querySoldier.y, 2)) < querySoldier.radius) {
						for (Soldier soldier : rowQuery) {
							double distance = Math.sqrt(Math.pow(bullet.x - soldier.x, 2) + Math.pow(bullet.y - soldier.y, 2));

							if (distance > 16)
								continue;

							int hitPoints = soldier.future.hitPoints;
							double iFactor = Math.pow((16 - distance) / 16, 0.5);
							soldier.future.hitPoints -= iFactor * 5;
							float a = (float) Math.atan2(bullet.y - soldier.y, bullet.x - soldier.x);
							bullet.soldier.events.add(new Event(battleTime, 0, 0, "hit"));
							if (soldier.future.hitPoints <= 0 && hitPoints > 0) {
								bullet.soldier.events.add(new Event(battleTime, 0, 0, "kill"));
								soldier.future.state = Soldier.STATE_WAIT;
								soldier.events.clear();
								soldier.viewport.clear();
								factionStats.get(soldier.faction).decrementAndGet();

								if (soldier.faction != bullet.soldier.faction)
									factionKills.get(bullet.soldier.faction).incrementAndGet();
							}
							soldier.future.fx += -Math.cos(a) * iFactor * 20;
							soldier.future.fy += -Math.sin(a) * iFactor * 20;
						}

						deadBullets.add(bullet);
						bullet.deathTime = battleTime;
						break;
					}
				}
			}
		}

		for (Soldier soldier : soldiers) {
			for (Soldier object : rowMap.query((int) Math.floor(soldier.x / 10)))
				if (object != soldier) {
					if (soldier.maxX < object.minX || soldier.minX > object.maxX || soldier.maxY < object.minY || soldier.minY > object.maxY)
						continue;

					float dx = object.x - soldier.x;
					float dy = object.y - soldier.y;
					if (Math.sqrt(Math.pow(dx, 2) + Math.pow(dy, 2)) < soldier.radius * 2) {
						soldier.future.fx += -dx / 10;
						soldier.future.fy += -dy / 10;
					}
				}

			if (soldier.commit())
				changedSoldiers.put(soldier, true);
		}

		rowMap.clear();

		for (Bullet bullet : bullets)
			bullet.commit();

		for (Bullet bullet : deadBullets)
			bullets.remove(bullet);

	}

	private void applyScriptResult(ScriptResult scriptResult) {
		for (FunctionCall call : scriptResult.calls) {
			if (call.function.equalsIgnoreCase("setState") && call.args[0] instanceof Number && call.args[1] instanceof Boolean)
				scriptResult.soldier.setState(((Number) call.args[0]).intValue(), (Boolean) call.args[1]);
			else if (call.function.equalsIgnoreCase("debug")) {

			}
		}
	}

	private HashMap<Soldier, Boolean> changedSoldiers = new HashMap<Soldier, Boolean>();
	private Vector<Bullet> deadBullets = new Vector<Bullet>();

	// --
	public static void main(String[] args) throws IOException {
		final Frame frame = new Frame("Battle");

		final Battle battle = new Battle();

		final Applet applet = new Applet() {
			Image buffer;

			public void init() {
				super.init();
			};

			public void start() {
				super.start();

			};

			@Override
			public void update(Graphics g) {
				if (buffer == null)
					buffer = createImage(getWidth(), getHeight());

				Graphics2D g2d = (Graphics2D) buffer.getGraphics();
				g2d.setColor(Color.BLACK);
				g2d.fillRect(0, 0, this.getWidth(), this.getHeight());
				g2d.setTransform(AffineTransform.getScaleInstance(0.5, 0.5));
				for (Soldier soldier : battle.soldiers) {
					g2d.setColor(soldier.faction == Soldier.FACTION_A ? Color.WHITE : soldier.faction == Soldier.FACTION_B ? Color.GREEN : Color.BLUE);
					if (soldier.hitPoints > 0)
						g2d.fillArc((int) (soldier.x - soldier.radius), (int) (soldier.y - soldier.radius), (int) soldier.radius * 2, (int) soldier.radius * 2, 0, 365);
					else
						g2d.drawArc((int) (soldier.x - soldier.radius), (int) (soldier.y - soldier.radius), (int) soldier.radius * 2, (int) soldier.radius * 2, 0, 365);
					g2d.setColor(Color.RED);
					g2d.drawLine((int) soldier.x, (int) soldier.y, (int) (soldier.x + Math.cos(soldier.dir) * soldier.radius), (int) (soldier.y + Math.sin(soldier.dir) * soldier.radius));
				}
				for (Bullet bullet : battle.bullets) {
					g2d.fillArc((int) bullet.x - 2, (int) bullet.y - 2, 4, 4, 0, 365);
				}
				for (Obstacle obstacle : battle.obstacles) {
					g2d.fillRect(obstacle.x, obstacle.y, obstacle.width, obstacle.height);
				}
				g.drawImage(buffer, 0, 0, this);
			}
		};

		final long startTime = System.currentTimeMillis();

		Thread mainThread = new Thread(new Runnable() {
			@Override
			public void run() {

				while (true) {
					try {
						battle.update();
						applet.repaint();
						Thread.sleep(10);
						// applet.repaint();
					} catch (Exception e) {
						e.printStackTrace();
						break;
					}
				}
			}
		});
		mainThread.start();

		battle.registerScript("generic", new Scanner(new File("bots/battle-generic-ai.js")).useDelimiter("\\Z").next(), "Generic script");
		battle.registerScript("myscript", new Scanner(new File("bots/battle-my-script.js")).useDelimiter("\\Z").next(), "Generic script");
		battle.registerTeam("1", "generic", -1, 6, -1, -1);
		battle.registerTeam("1", "myscript", -1, 6, -1, -1);
		battle.registerTeam("1", "generic", -1, 6, -1, -1);

		applet.setSize(1024, 1024);
		applet.init();
		applet.start();
		applet.setVisible(true);

		frame.add(applet);
		frame.setSize(1024, 1024);

		frame.setVisible(true);

	}

	@Override
	public Exception registerScript(String key, String source, String label) {
		try {
			if (scripts.containsKey(key))
				scripts.get(key).reload(source);
			else {
				ScriptSandbox sandbox = new ScriptSandbox(label);
				sandbox.init(source, key, new java.util.HashMap(), new java.util.HashMap());
				scripts.put(key, sandbox);
				// System.err.println("Registered script " + key);
			}
		} catch (Exception e) {
			return e;
		}
		return null;
	}

	@Override
	public Object registerTeam(String key, String scriptKey, int faction, int nsoldiers, float x, float y) {
		if (faction == -1) {
			faction = 1;
			for (int i = 2; i <= 3; i++)
				if (factionStats.get(i).intValue() < factionStats.get(faction).intValue())
					faction = i;
		}

		byte slot = groupSlots[faction - 1].pop();

		if (x == -1) {
			float a = (float) ((double) slot * Math.PI / 100);
			x = (float) (1000 + Math.cos((float) faction * Math.PI / 3 * 2 + a) * 800);
			y = (float) (1000 + Math.sin((float) faction * Math.PI / 3 * 2 + a) * 800);
		}

		long teamId = teamCounter++;

		float a = (float) Math.atan2(1000 - y, 1000 - x);

		for (int i = 0; i < nsoldiers; i++) {
			long soldierId = soldierCounter++;
			newSoldiers.push(new Soldier(scriptKey, soldierId, teamId, faction, x + (float) Math.cos(a + i * Math.PI / 24), y + (float) Math.sin(a + i * Math.PI / 24), 8,
					(float) ((Math.random() - Math.random()) * Math.PI), (byte) i));
		}
		factionStats.get(faction).addAndGet(nsoldiers);
		scripts.get(scriptKey).faction = faction;
		scripts.get(scriptKey).getStats().faction = faction;
		// System.err.println("Registered team " + key + " with script " +
		// scriptKey + " in faction " + faction);
		return true;
	}

	@Override
	public Stats getScriptStats(String key) {
		if (scripts.containsKey(key))
			return scripts.get(key).getStats();
		else
			return null;
	}

	@Override
	public BattleStats getBattleStats() {
		BattleStats stats = new BattleStats();
		stats.duration = battleTime;
		stats.winner = winner;
		for (int i = 1; i <= 3; i++) {
			stats.kills[i - 1] = factionKills.get(i).intValue();
			stats.stats[i - 1] = factionStats.get(i).intValue();
		}
		return stats;
	}

	@Override
	public boolean isFinished() {
		return false;
	}

	@Override
	public Object pushInput(String teamKey, long soldierID, String value) {
		if (soldierID >= 0) {
			Soldier soldier = soldiersMap.get(soldierID);
			if (soldier != null && soldier.scriptKey.equals(teamKey)) {
				ScriptableObject.putProperty(soldier.status, "input", value);
				ScriptableObject.putProperty(soldier.status, "inputTime", battleTime);
				return true;
			}
		}
		return false;
	}

	@Override
	public Object setPointer(String teamKey, long soldierID, int x, int y) {
		if (soldierID >= 0 && Math.abs(x) <= 2000 && Math.abs(y) <= 2000) {
			Soldier soldier = soldiersMap.get(soldierID);
			if (soldier != null && soldier.scriptKey.equals(teamKey)) {
				soldier.pointer = new int[] { x, y };
				return true;
			}
		}
		return false;
	}

	@Override
	public long getBattleTime() {
		return battleTime;
	}

	@Override
	public boolean start() {
		return true;
	}
}

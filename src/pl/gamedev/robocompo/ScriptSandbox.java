package pl.gamedev.robocompo;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import sun.org.mozilla.javascript.internal.BaseFunction;
import sun.org.mozilla.javascript.internal.Context;
import sun.org.mozilla.javascript.internal.NativeArray;
import sun.org.mozilla.javascript.internal.NativeObject;
import sun.org.mozilla.javascript.internal.Script;
import sun.org.mozilla.javascript.internal.Scriptable;
import sun.org.mozilla.javascript.internal.ScriptableObject;

class FunctionCall implements Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = -277089866250804482L;

	public FunctionCall(String function, Object[] args) {
		this.function = function;
		this.args = args;
	}

	String function;
	Object[] args;
}

class ScriptTask extends FutureTask<ScriptResult> implements Comparable<ScriptTask> {
	long soldierId;
	byte order;

	public ScriptTask(Callable<ScriptResult> callable, long soldierId, byte order) {
		super(callable);
		this.soldierId = soldierId;
		this.order = order;
	}

	@Override
	public int compareTo(ScriptTask task) {
		return this.order < task.order ? -1 : this.order > task.order ? 1 : this.soldierId < task.soldierId ? -1 : this.soldierId > task.soldierId ? 1 : 0;
	}

}

public class ScriptSandbox {
	String label;
	Stats stats = new Stats();
	int faction = -1;

	public ScriptSandbox(String label) {
		this.label = label;
	}

	class Execution implements Callable<ScriptResult> {
		long battleTime;
		Soldier soldier;
		NativeArray viewport, events;
		NativeObject status;

		public Execution(long battleTime, Soldier soldier, NativeArray viewport, NativeArray events, NativeObject status) {
			this.battleTime = battleTime;
			this.soldier = soldier;
			this.viewport = viewport;
			this.events = events;
			this.status = status;
		}

		@Override
		protected void finalize() throws Throwable {
			super.finalize();
		}

		private void writeOutput() throws IOException, InterruptedException {
			output.put(battleTime);
			output.put(soldier.id);
			output.put(soldier.teamId);
			output.put(3);
			output.put("viewport");
			output.put(viewport);
			output.put("events");
			output.put(events);
			output.put("status");
			output.put(status);
		}

		private void readInput(ScriptResult result) throws IOException, ClassNotFoundException, InterruptedException {
			if ((Boolean) input.take()) {
				stats.total_time += (Long) input.take();
				stats.executions++;
				int ncalls = (Integer) input.take();
				for (int i = 0; i < ncalls; i++)
					result.calls.add((FunctionCall) input.take());
			} else {
				stats.timeouts++;
			}
		}

		@Override
		public ScriptResult call() throws Exception {
			ScriptResult result = new ScriptResult(soldier);
			synchronized (ready) {
				try {
					if (error) {
						reload(null);
						error = false;
					}

					writeOutput();

					readInput(result);

				} catch (Exception e) {
					e.printStackTrace();
					error = true;
					stats.errors++;
				}
			}
			return result;
		}
	}

	boolean error = false;

	public void reload(String source) throws IOException, InterruptedException {
		sandbox.interrupt();
		if (source == null) {
			close();
			init(this.source, key, new java.util.HashMap(), new java.util.HashMap());
		} else if (!source.equals(this.source))
			synchronized (ready) {
				output.put(-1);
				// pobierz pamięć podręczną i zrestartuj program
				// nie ma innej możliwości ze względu na sandbox
				// try {
				// Object memory = input.readObject();
				// Object teamMemory = input.readObject();
				close();
				init(source, key, null, null);
				// } catch (ClassNotFoundException e) {

				// }
			}
	}

	private String source;
	private String key;

	Object ready = new Object();

	LinkedBlockingQueue<Object> output;
	LinkedBlockingQueue<Object> input;

	Thread sandbox;

	// ObjectInputStream error;

	public void init(String source, String key, Object memory, Object teamMemory) throws IOException, InterruptedException {
		this.source = source;
		this.key = key;
		
		this.output = new LinkedBlockingQueue<Object>();
		this.input = new LinkedBlockingQueue<Object>();

		output.put(key);
		output.put(source);
		output.put(memory);
		output.put(teamMemory);

		(sandbox = new Thread(new Sandbox(output, input))).start();
	}

	public ScriptTask execute(long battleTime, Soldier soldier, NativeArray viewport, NativeArray events, NativeObject status) {
		return new ScriptTask(new Execution(battleTime, soldier, viewport, events, status), soldier.id, soldier.order);
	}

	@Override
	protected void finalize() throws Throwable {
		close();
		super.finalize();
	}

	public void close() {
	}

	class Sandbox implements Runnable {
		
		LinkedBlockingQueue<Object> input;
		LinkedBlockingQueue<Object> output;
		
		public Sandbox(LinkedBlockingQueue<Object> input,
		LinkedBlockingQueue<Object> output) {
			this.input = input;
			this.output = output;	
		}
		
		public void run() {
			try {
				process();
			} catch (InterruptedException ie) {
			}
		}

		private void process() throws InterruptedException {			

			String key = (String) input.take();
			String source = (String) input.take();

			final Context cx = Context.enter();
			cx.setOptimizationLevel(9);
			// cx.setMaximumInterpreterStackDepth(100);
			// cx.setInstructionObserverThreshold(1000000);
			final Scriptable scope = cx.initStandardObjects();
			// Typy obiektów
			ScriptableObject.putProperty(scope, "TYPE_TEAM", Battle.TYPE_TEAM);
			ScriptableObject.putProperty(scope, "TYPE_ALLY", Battle.TYPE_ALLY);
			ScriptableObject.putProperty(scope, "TYPE_ENEMY", Battle.TYPE_ENEMY);
			ScriptableObject.putProperty(scope, "TYPE_CENTER", Battle.TYPE_CENTER);
			ScriptableObject.putProperty(scope, "TYPE_BULLET", Battle.TYPE_BULLET);
			ScriptableObject.putProperty(scope, "TYPE_OBSTACLE", Battle.TYPE_OBSTACLE);
			ScriptableObject.putProperty(scope, "TYPE_DEAD", Battle.TYPE_DEAD);

			// Stany jednostek
			ScriptableObject.putProperty(scope, "STATE_WAIT", Soldier.STATE_WAIT);
			ScriptableObject.putProperty(scope, "STATE_ROTATE_LEFT", Soldier.STATE_ROTATE_LEFT);
			ScriptableObject.putProperty(scope, "STATE_ROTATE_RIGHT", Soldier.STATE_ROTATE_RIGHT);
			ScriptableObject.putProperty(scope, "STATE_ROTATE_FAST", Soldier.STATE_ROTATE_FAST);
			ScriptableObject.putProperty(scope, "STATE_MOVE", Soldier.STATE_MOVE);
			ScriptableObject.putProperty(scope, "STATE_SHOOT", Soldier.STATE_SHOOT);

			final Vector<FunctionCall> callStack = new Vector<FunctionCall>();

			Object inputMemory = input.take();
			Object inputTeamMemory = input.take();

			final HashMap<Long, NativeObject> memoryHash = inputMemory != null ? (HashMap<Long, NativeObject>) inputMemory : new HashMap<Long, NativeObject>();

			final HashMap<Long, NativeObject> teamMemoryHash = inputTeamMemory != null ? (HashMap<Long, NativeObject>) inputMemory : new HashMap<Long, NativeObject>();

			// debug
			BaseFunction debug = new BaseFunction() {
				/**
			 * 
			 */
				private static final long serialVersionUID = 1665403455798400358L;

				@Override
				public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
					synchronized (callStack) {
						if (args.length == 1 && callStack.size() < 32 && args[0] != null) {
							String message = args[0].toString();
							if (message.length() > 32)
								message = message.substring(0, 32);
							callStack.add(new FunctionCall("debug", new Object[] { message }));
						}
					}
					return null;
				}
			};
			BaseFunction setState = new BaseFunction() {
				/**
			 * 
			 */
				private static final long serialVersionUID = 5857079814390872559L;

				@Override
				public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
					if (args.length == 2 && args[0] instanceof Number && args[1] instanceof Boolean && callStack.size() < 32)
						synchronized (callStack) {
							callStack.add(new FunctionCall("setState", args));
						}
					return null;
				}
			};

			BaseFunction storeGet = new BaseFunction() {
				/**
			 * 
			 */
				private static final long serialVersionUID = 5857079814390872559L;

				@Override
				public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
					return null;
				}
			};

			BaseFunction storePut = new BaseFunction() {
				/**
			 * 
			 */
				private static final long serialVersionUID = 5857079814390872559L;

				@Override
				public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
					return null;
				}
			};

			Script compiled;

			try {
				compiled = cx.compileString(source, key, 0, null);
			} catch (Exception e) {
				compiled = cx.compileString("", key, 0, null);
			}

			final Script script = compiled;

			ScriptableObject.putProperty(scope, "debug", debug);
			ScriptableObject.putProperty(scope, "setState", setState);
			ScriptableObject.putProperty(scope, "storeGet", storeGet);
			ScriptableObject.putProperty(scope, "storePut", storePut);

			final AtomicBoolean exec = new AtomicBoolean(false);
			final AtomicBoolean error = new AtomicBoolean(false);
			final AtomicBoolean ready = new AtomicBoolean(true);
			final AtomicLong lastExec = new AtomicLong(0);

			final Thread executor = new Thread() {
				@Override
				public void run() {
					Context icx = Context.enter();
					icx.setOptimizationLevel(9);
					// icx.setMaximumInterpreterStackDepth(100);
					// icx.setInstructionObserverThreshold(1000000);
					while (true) {
						try {
							if (exec.get()) {
								lastExec.set(System.nanoTime());
								ready.set(false);
								try {
									script.exec(icx, scope);
									error.set(false);
								} catch (Exception e) {
									e.printStackTrace(System.err);
									error.set(true);
								}
								ready.set(true);
								synchronized (exec) {
									exec.set(false);
								}
							}
							Thread.sleep(1);
						} catch (InterruptedException e) {
							break;
						}
					}
				};
			};

			Runtime.getRuntime().addShutdownHook(new Thread() {
				@Override
				public void run() {
					executor.interrupt();
					super.run();
				}
			});

			executor.start();

			long t;

			HashMap<String, Object> propertyBuffer = new HashMap<String, Object>();

			while (true)
				try {
					long battleTime = (Long) input.take();
					if (battleTime == -1) {
						// przeładowanie skryptu
						// output.writeObject(memoryHash);
						// output.writeObject(teamMemoryHash);
						// output.flush();
						return;
					} else {
						long memoryId = (Long) input.take();
						long teamMemoryId = (Long) input.take();

						if (!memoryHash.containsKey(memoryId))
							memoryHash.put(memoryId, new NativeObject());
						if (!teamMemoryHash.containsKey(teamMemoryId))
							teamMemoryHash.put(teamMemoryId, new NativeObject());

						propertyBuffer.clear();
						int n = (Integer) input.take();
						for (int i = 0; i < n; i++)
							propertyBuffer.put((String) input.take(), input.take());

						boolean skip = false;
						try {
							if (ready.get()) {
								callStack.clear();
								long execId = lastExec.get();

								ScriptableObject.putProperty(scope, "t", battleTime);
								ScriptableObject.putProperty(scope, "memory", memoryHash.get(memoryId));
								ScriptableObject.putProperty(scope, "teamMemory", teamMemoryHash.get(teamMemoryId));

								for (String property : propertyBuffer.keySet())
									ScriptableObject.putProperty(scope, property, propertyBuffer.get(property));

								synchronized (exec) {
									exec.set(true);
								}
								while (execId == lastExec.get())
									Thread.sleep(0, 100000);
								t = System.currentTimeMillis();

								while (System.currentTimeMillis() - t < 50 && !ready.get())
									Thread.sleep(0, 100000);

								skip = !ready.get() || error.get();
								output.put(!skip);
								if (!skip) {
									output.put(System.currentTimeMillis() - t);
									synchronized (callStack) {
										output.put(callStack.size());
										for (FunctionCall call : callStack)
											output.put(call);
									}
								}
								// if (!skip)
								// System.out.println((System.currentTimeMillis()
								// -
								// t) + " " + (System.currentTimeMillis() - t) +
								// "ms");
							} else
								output.put(!(skip = true));
						} catch (Exception e) {
							e.printStackTrace(System.err);
							output.put(!(skip = true));
						}
					}
				} catch (Exception e) {
					break;
				}
		}
	}

	public Stats getStats() {
		return stats;
	}
}

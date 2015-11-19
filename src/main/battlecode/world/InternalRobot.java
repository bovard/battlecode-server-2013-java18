package battlecode.world;

import battlecode.common.*;
import battlecode.engine.ErrorReporter;
import battlecode.engine.GenericRobot;
import battlecode.engine.RobotRunnable;
import battlecode.engine.instrumenter.IndividualClassLoader;
import battlecode.engine.instrumenter.InstrumentationException;
import battlecode.engine.scheduler.ScheduledRunnable;
import battlecode.engine.signal.Signal;
import battlecode.server.Config;
import battlecode.world.signal.BroadcastSignal;
import battlecode.world.signal.DeathSignal;
import battlecode.world.signal.SpawnSignal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;

public class InternalRobot implements GenericRobot {
    public RobotType type;

    private final int myID;
    private Team myTeam;

    protected volatile MapLocation myLocation;
    protected final GameWorld myGameWorld;

    protected volatile double myHealthLevel;
    private double coreDelay;
    private double weaponDelay;
    private int zombieInfectedTurns;
    private int viperInfectedTurns;

    protected volatile long controlBits;

    int currentBytecodeLimit;
    private volatile int bytecodesUsed;
    protected volatile boolean hasBeenAttacked;
    private boolean healthChanged;
    private boolean didSelfDestruct;
    private boolean broadcasted;
    private volatile HashMap<Integer, Integer> broadcastMap;
    private int roundsAlive;

    private ArrayList<Signal> supplyActions;
    private ArrayList<SpawnSignal> missileLaunchActions;
    private Signal movementSignal;
    private Signal attackSignal;
    private Signal castSignal;

    private int buildDelay;

    /**
     * Create a new internal representation of a robot
     *
     * @param gw the world the robot exists in
     * @param type the type of the robot
     * @param loc the location of the robot
     * @param team the team of the robot
     * @param buildDelay the build
     * @param parent the parent of the robot, if one exists
     */
    @SuppressWarnings("unchecked")
    public InternalRobot(GameWorld gw, RobotType type, MapLocation loc, Team team,
            int buildDelay, Optional<InternalRobot> parent) {

        myID = gw.nextID();
        myTeam = team;
        myGameWorld = gw;
        myLocation = loc;
        gw.notifyAddingNewObject(this);
        this.type = type;
        this.buildDelay = buildDelay;

        myHealthLevel = getMaxHealth();
        if (type.isBuildable() && buildDelay > 0) { // What is this for?
            myHealthLevel /= 2.0;
        }

        coreDelay = 0.0;
        weaponDelay = 0.0;
        zombieInfectedTurns = 0;
        viperInfectedTurns = 0;

        controlBits = 0;

        currentBytecodeLimit = type.bytecodeLimit;
        bytecodesUsed = 0;
        hasBeenAttacked = false;
        healthChanged = true;

        didSelfDestruct = false;
        broadcasted = false;
        broadcastMap = new HashMap<>();
        roundsAlive = 0;

        supplyActions = new ArrayList<>();
        missileLaunchActions = new ArrayList<>();
        movementSignal = null;
        attackSignal = null;
        castSignal = null;

        myGameWorld.incrementTotalRobotTypeCount(getTeam(), type);

        if (!type.isBuildable() || buildDelay == 0) {
            myGameWorld.incrementActiveRobotTypeCount(getTeam(), type);
        }

        myGameWorld
                .updateMapMemoryAdd(getTeam(), loc, type.sensorRadiusSquared);

        // Signal the world that we've spawned
        gw.addSignal(new SpawnSignal(
                this.getID(),
                parent.isPresent() ? parent.get().getID() : 0,
                this.getLocation(),
                this.type,
                this.getTeam(),
                buildDelay
        ));

        RobotControllerImpl rc = new RobotControllerImpl(gw, this);

        if(rc.getTeam() == Team.ZOMBIE){
            loadZombiePlayer(rc);
        } else {
            String teamName = gw.getTeamName(team);
            loadPlayer(rc, teamName);
        }
    }

    private void loadPlayer(RobotControllerImpl rc, String teamName) {
        final boolean debugMethodsEnabled = Config.getGlobalConfig().getBoolean("bc.engine.debug-methods");

        // now, we instantiate and instrument the player's class
        Class playerClass;
        try {
            // The classloaders ignore silenced now - RobotMonitor takes care of it
            ClassLoader icl = new IndividualClassLoader(teamName, debugMethodsEnabled, false, true);
            playerClass = icl.loadClass(teamName + ".RobotPlayer");
            //~ System.out.println("PF done loading");
        } catch (InstrumentationException ie) {
            // if we get an InstrumentationException, then the error should have been reported, so we just kill the robot
            System.out.println("[Engine] Error during instrumentation of " + rc.getRobot().toString() + ".\n[Engine] Robot will self-destruct in 3...2...1...");
            rc.getRobot().suicide();
            return;
        } catch (Exception e) {
            ErrorReporter.report(e);
            rc.getRobot().suicide();
            return;
        }

        // finally, create the player's thread, and let it loose
        new ScheduledRunnable(new RobotRunnable(playerClass, rc), rc.getRobot().getID());

    }

    private void loadZombiePlayer(RobotControllerImpl rc) {
        final boolean debugMethodsEnabled = Config.getGlobalConfig().getBoolean("bc.engine.debug-methods");

        // instantiate and instrument the ZombiePlayer class
        Class playerClass;
        try {
            // The classloaders ignore silenced now - RobotMonitor takes care of it
            ClassLoader icl = new IndividualClassLoader("ZombiePlayer", debugMethodsEnabled, false, true);
            playerClass = icl.loadClass("ZombiePlayer.ZombiePlayer");
            //~ System.out.println("PF done loading");
        } catch (InstrumentationException ie) {
            // if we get an InstrumentationException, then the error should have been reported, so we just kill the robot
            System.out.println("[Engine] Error during instrumentation of " + rc.getRobot().toString() + ".\n[Engine] Robot will self-destruct in 3...2...1...");
            rc.getRobot().suicide();
            return;
        } catch (Exception e) {
            ErrorReporter.report(e);
            rc.getRobot().suicide();
            return;
        }

        // finally, create the ZombiePlayer's thread, and let it loose
        new ScheduledRunnable(new RobotRunnable(playerClass, rc), rc.getRobot().getID());

    }

    @Override
    public boolean equals(Object o) {
        return o != null && (o instanceof InternalRobot)
                && ((InternalRobot) o).getID() == myID;
    }

    @Override
    public int hashCode() {
        return myID;
    }

    // *********************************
    // ****** QUERY METHODS ************
    // *********************************

    public RobotInfo getRobotInfo() {
        return new RobotInfo(getID(), getTeam(), type, getLocation(),
                getCoreDelay(), getWeaponDelay(), getHealthLevel(),
                getZombieInfectedTurns(),getViperInfectedTurns());
    }

    public int getRoundsAlive() {
        return roundsAlive;
    }

    public int getID() {
        return myID;
    }

    protected void setTeam(Team newTeam) {
        myTeam = newTeam;
    }

    public Team getTeam() {
        return myTeam;
    }

    public MapLocation getLocation() {
        return myLocation;
    }

    public GameWorld getGameWorld() {
        return myGameWorld;
    }

    public boolean exists() {
        return myGameWorld.exists(this);
    }

    public InternalRobot container() {
        return null;
    }

    public MapLocation sensedLocation() {
        if (container() != null)
            return container().sensedLocation();
        else
            return getLocation();
    }

    // *********************************
    // ****** BASIC METHODS ************
    // *********************************

    public boolean isActive() {
        return !type.isBuildable() || roundsAlive >= buildDelay;
    }

    public boolean canExecuteCode() {
        if (getHealthLevel() <= 0.0)
            return false;
        return isActive();
    }

    public void setBytecodesUsed(int numBytecodes) {
        bytecodesUsed = numBytecodes;
    }

    public int getBytecodesUsed() {
        return bytecodesUsed;
    }

    public int getBytecodeLimit() {
        return canExecuteCode() ? this.currentBytecodeLimit : 0;
    }

    public boolean movedThisTurn() {
        return this.movementSignal != null;
    }

    public void setControlBits(long l) {
        controlBits = l;
    }

    public long getControlBits() {
        return controlBits;
    }

    public boolean hasBeenAttacked() {
        return hasBeenAttacked;
    }

    public void clearHealthChanged() {
        healthChanged = false;
    }

    public boolean healthChanged() {
        return healthChanged;
    }

    // *********************************
    // ****** ZOMBIE METHODS ***********
    // *********************************

    public int getZombieInfectedTurns() {
        return zombieInfectedTurns;
    }
    
    public int getViperInfectedTurns() {
        return viperInfectedTurns;
    }
    
    public boolean isInfected() {
        return (zombieInfectedTurns > 0 || viperInfectedTurns > 0);
    }

    public void setInfected(InternalRobot attacker) {
        if (attacker.type == RobotType.VIPER) {
            viperInfectedTurns = attacker.type.infectTurns;
        } else if (attacker.type.isZombie) {
            zombieInfectedTurns = attacker.type.infectTurns;
        }
    }

    public void processBeingInfected() { // TODO: Call this somewhere where it runs for each robot every turn
        if (viperInfectedTurns > 0) {
            takeDamage(GameConstants.VIPER_INFECTION_DAMAGE);
            viperInfectedTurns--;
        }
        if (zombieInfectedTurns > 0) {
            zombieInfectedTurns--;
        }
    }

    // *********************************
    // ****** HEALTH METHODS ***********
    // *********************************

    public double getHealthLevel() {
        return myHealthLevel;
    }

    public void takeDamage(double baseAmount) {
        healthChanged = true;
        if (baseAmount < 0) {
            changeHealthLevel(-baseAmount);
        } else {
            changeHealthLevelFromAttack(-baseAmount);
        }
    }

    public void takeDamage(double amt, InternalRobot source) {
        if (!(getTeam() == Team.NEUTRAL)) {
            healthChanged = true;
            takeDamage(amt);
        }
    }

    public void changeHealthLevelFromAttack(double amount) {
        healthChanged = true;
        hasBeenAttacked = true;
        changeHealthLevel(amount);
    }

    public void changeHealthLevel(double amount) {
        healthChanged = true;
        myHealthLevel += amount;
        if (myHealthLevel > getMaxHealth()) {
            myHealthLevel = getMaxHealth();
        }

        if (myHealthLevel <= 0 && getMaxHealth() != Integer.MAX_VALUE) {
            processLethalDamage();
        }
    }

    public void processLethalDamage() {
        myGameWorld.notifyDied(this);   // myGameWorld checks if infected and creates zombie
    }

    public double getMaxHealth() {
        return type.maxHealth;
    }

    // *********************************
    // ****** DELAYS METHODS ***********
    // *********************************

    public double getCoreDelay() {
        return coreDelay;
    }

    public double getWeaponDelay() {
        return weaponDelay;
    }

    public void addCoreDelay(double time) {
        coreDelay += time;
    }

    public void addWeaponDelay(double time) {
        weaponDelay += time;
    }

    public void addCooldownDelay(double delay) {
        coreDelay = Math.max(coreDelay, delay);
    }

    public void addLoadingDelay(double delay) {
        weaponDelay = Math.max(weaponDelay, delay);
    }

    public void decrementDelays() {
        weaponDelay--;
        coreDelay--;

        if (weaponDelay < 0.0) {
            weaponDelay = 0.0;
        }
        if (coreDelay < 0.0) {
            coreDelay = 0.0;
        }
    }

    public double getAttackDelayForType() {
        return type.attackDelay;
    }

    public double getMovementDelayForType() {
        return type.movementDelay;
    }

    public double getCooldownDelayForType() {
        return type.cooldownDelay;
    }

    public double calculateMovementActionDelay(MapLocation from,
            MapLocation to) {
        if (from.distanceSquaredTo(to) <= 1) {
            return getMovementDelayForType();
        } else {
            return getMovementDelayForType() * 1.4;
        }
    }

    // *********************************
    // ****** BROADCAST METHODS ********
    // *********************************

    public void addBroadcast(int channel, int data) {
        broadcastMap.put(channel, data);
        broadcasted = true;
    }

    public Integer getQueuedBroadcastFor(int channel) {
        return broadcastMap.get(channel);
    }

    public boolean hasBroadcasted() {
        return broadcasted;
    }

    // *********************************
    // ****** ACTION METHODS ***********
    // *********************************

    public void addAction(Signal s) {
        myGameWorld.visitSignal(s);
    }

    public void activateMovement(Signal s, double attackDelay,
            double movementDelay) {
        movementSignal = s;
        addLoadingDelay(attackDelay);
        addCoreDelay(movementDelay);
    }

    public void activateAttack(Signal s, double attackDelay,
            double movementDelay) {
        attackSignal = s;
        addWeaponDelay(attackDelay);
        addCooldownDelay(movementDelay);
    }

    public void setLocation(MapLocation loc) {
        MapLocation oldloc = getLocation();
        myGameWorld.notifyMovingObject(this, myLocation, loc);
        myLocation = loc;
        myGameWorld.updateMapMemoryRemove(getTeam(), oldloc,
                type.sensorRadiusSquared);
        myGameWorld
                .updateMapMemoryAdd(getTeam(), loc, type.sensorRadiusSquared);
    }

    public void setSelfDestruct() {
        didSelfDestruct = true;
    }

    public void unsetSelfDestruct() {
        didSelfDestruct = false;
    }

    public boolean didSelfDestruct() {
        return didSelfDestruct;
    }

    public void suicide() {
        myGameWorld.visitSignal((new DeathSignal(this.getID())));
    }
    
    public void transform(RobotType newType) {
        myGameWorld.decrementActiveRobotTypeCount(getTeam(), type);
        type = newType;
        myGameWorld.incrementTotalRobotTypeCount(getTeam(), newType);
        coreDelay += 10;
        weaponDelay += 10;
    }

    // *********************************
    // ****** GAMEPLAY METHODS *********
    // *********************************

    // should be called at the beginning of every round
    public void processBeginningOfRound() {
    }

    public void processBeginningOfTurn() {
        decrementDelays(); // expends supply to decrement delays

        this.currentBytecodeLimit = type.bytecodeLimit;
    }

    public void processEndOfTurn() {
        roundsAlive++;

        // resetting stuff
        hasBeenAttacked = false;

        // broadcasts
        if (broadcasted)
            myGameWorld.visitSignal(new BroadcastSignal(this.getID(), this.getTeam(), broadcastMap));

        broadcastMap = new HashMap<>();
        broadcasted = false;

        // perform supply actions
        for (Signal s : supplyActions) {
            myGameWorld.visitSignal(s);
        }
        supplyActions.clear();

        // perform attacks
        if (attackSignal != null) {
            myGameWorld.visitSignal(attackSignal);
            attackSignal = null;
        }

        // launch missiles
        for (SpawnSignal s : missileLaunchActions) {
            myGameWorld.visitSignal(s);
        }
        missileLaunchActions.clear();

        // perform movements (moving, spawning, mining)
        if (movementSignal != null) {
            myGameWorld.visitSignal(movementSignal);
            movementSignal = null;
        }
    }

    public void processEndOfRound() {
    }

    // *********************************
    // ****** MISC. METHODS ************
    // *********************************
    
    
    
    @Override
    public String toString() {
        return String.format("%s:%s#%d", getTeam(), type, getID());
    }

    public void freeMemory() {
        movementSignal = null;
        attackSignal = null;
    }
}

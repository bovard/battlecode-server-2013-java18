package battlecode.world;

import battlecode.common.*;
import battlecode.server.ErrorReporter;
import battlecode.server.GameState;
import battlecode.util.SquareArray;
import battlecode.world.signal.AutoSignalHandler;
import battlecode.world.signal.InternalSignal;
import battlecode.world.signal.SignalHandler;
import battlecode.serial.GameStats;
import battlecode.instrumenter.RobotDeathException;
import battlecode.server.Config;
import battlecode.world.control.RobotControlProvider;
import battlecode.world.signal.*;

import java.util.*;

/**
 * The primary implementation of the GameWorld interface for containing and
 * modifying the game map and the objects on it.
 */
public class GameWorld implements SignalHandler {
    /**
     * The current round we're running.
     */
    protected int currentRound;

    /**
     * Whether we're running.
     */
    protected boolean running = true;

    protected Team winner = null;
    protected final String teamAName;
    protected final String teamBName;
    protected final List<InternalSignal> currentInternalSignals;
    protected final List<InternalSignal> injectedInternalSignals;
    protected final long[][] teamMemory;
    protected final long[][] oldTeamMemory;
    protected final Map<Integer, InternalRobot> gameObjectsByID;
    protected final IDGenerator idGenerator;

    private final GameMap gameMap;

    private final RobotControlProvider controlProvider;

    private final GameStats gameStats = new GameStats(); // end-of-game stats

    private double[] teamResources = new double[4];
    private double[] teamRoundResources = new double[2];
    private double[] lastRoundResources = new double[2];
    private double[] teamResources = new double[2];
    private double[] teamSpawnRate = new double[2];
    private int[] teamCapturingNumber = new int[2];

    private Map<Team, Set<InternalRobot>> baseArchons = new EnumMap<>(Team.class);
    private final Map<MapLocation, InternalRobot> gameObjectsByLoc = new HashMap<>();

    private SquareArray.Double rubble;
    private SquareArray.Double parts;

    private Map<Integer, Integer> radio = new HashMap<Integer, Integer>();


    private Map<Team, Map<RobotType, Integer>> robotTypeCount = new EnumMap<>(
            Team.class);
    private int[] robotCount = new int[4];
    private Random rand;

    private List<MapLocation> encampments = new ArrayList<MapLocation>();
    private Map<MapLocation, Team> encampmentMap = new HashMap<MapLocation, Team>();
    private Map<Team, InternalRobot> baseHQs = new EnumMap<Team, InternalRobot>(Team.class);
    private Map<MapLocation, Team> mineLocations = new HashMap<MapLocation, Team>();
    private Map<Team, GameMap.MapMemory> mapMemory = new EnumMap<Team, GameMap.MapMemory>(Team.class);
    private Map<Team, Set<MapLocation>> knownMineLocations = new EnumMap<Team, Set<MapLocation>>(Team.class);
    private Map<Team, Map<Upgrade, Integer>> research = new EnumMap<Team, Map<Upgrade, Integer>>(Team.class);
    
    private Map<Team, Set<Upgrade>> upgrades = new EnumMap<Team, Set<Upgrade>>(Team.class);
    private Map<Integer, Integer> radio = new HashMap<Integer, Integer>();

    // robots to remove from the game at end of turn
    private List<InternalRobot> deadRobots = new ArrayList<InternalRobot>();

    @SuppressWarnings("unchecked")
    public GameWorld(GameMap gm, RobotControlProvider cp,
                     String teamA, String teamB,
                     long[][] oldTeamMemory) {
        
        currentRound = -1;
        teamAName = teamA;
        teamBName = teamB;
        gameObjectsByID = new LinkedHashMap<>();
        currentInternalSignals = new ArrayList<>();
        injectedInternalSignals = new ArrayList<>();
        idGenerator = new IDGenerator(gm.getSeed());
        teamMemory = new long[2][oldTeamMemory[0].length];
        this.oldTeamMemory = oldTeamMemory;

        gameMap = gm;
        controlProvider = cp;
        
        mapMemory.put(Team.A, new GameMap.MapMemory(gameMap));
        mapMemory.put(Team.B, new GameMap.MapMemory(gameMap));
        mapMemory.put(Team.NEUTRAL, new GameMap.MapMemory(gameMap));
        upgrades.put(Team.A, EnumSet.noneOf(Upgrade.class));
        upgrades.put(Team.B, EnumSet.noneOf(Upgrade.class));
        knownMineLocations.put(Team.A, new HashSet<MapLocation>());
        knownMineLocations.put(Team.B, new HashSet<MapLocation>());
        research.put(Team.A, new EnumMap<Upgrade, Integer>(Upgrade.class));
        research.put(Team.B, new EnumMap<Upgrade, Integer>(Upgrade.class));

        robotTypeCount.put(Team.A, new EnumMap<>(
                RobotType.class));
        robotTypeCount.put(Team.B, new EnumMap<>(
                RobotType.class));
        robotTypeCount.put(Team.NEUTRAL, new EnumMap<>(
                RobotType.class));


        controlProvider.matchStarted(this);

        
        rand = new Random(gameMap.getSeed());
    }

    /**
     * Run a single round of the game.
     * Synchronized because you shouldn't call this and inject() at the same time,
     * but their order of being executed isn't guaranteed.
     *
     * @return the state of the game after the round has run.
     */
    public synchronized GameState runRound() {
        if (!this.isRunning()) {
            return GameState.DONE;
        }

        try {
            if (this.getCurrentRound() != -1) {
                this.clearAllSignals();
            }
            this.processBeginningOfRound();
            this.controlProvider.roundStarted();

            // We iterate through the IDs so that we avoid ConcurrentModificationExceptions
            // of an iterator. Kinda gross, but whatever.
            final int[] idsToRun = gameObjectsByID.keySet().stream()
                    .mapToInt(i -> i)
                    .toArray();

            for (final int id : idsToRun) {
                final InternalRobot robot = gameObjectsByID.get(id);
                if (robot == null) {
                    // Robot might have died earlier in the iteration; skip it
                    continue;
                }

                robot.processBeginningOfTurn();
                this.controlProvider.runRobot(robot);
                robot.setBytecodesUsed(this.controlProvider.getBytecodesUsed(robot));
                
                if(robot.getHealthLevel() > 0) { // Only processEndOfTurn if robot is still alive
                    robot.processEndOfTurn();
                }
                // If the robot terminates but the death signal has not yet
                // been visited:
                if (this.controlProvider.getTerminated(robot) && gameObjectsByID
                        .get(id) != null) {
                    robot.suicide();
                }
            }

            this.controlProvider.roundEnded();
            this.processEndOfRound();

            if (!this.isRunning()) {
                this.controlProvider.matchEnded();
            }

        } catch (Exception e) {
            ErrorReporter.report(e);
            return GameState.DONE;
        }

        return GameState.RUNNING;
    }

    /**
     * Inject a signal into the game world, and return any new signals
     * that result from changes created by the signal.
     *
     * Synchronized because you shouldn't call this and runRound() at the same time,
     * but their order of being executed isn't guaranteed.
     *
     * @param injectedInternalSignal the signal to inject
     * @return signals that result from the injected signal (including the injected signal)
     * @throws RuntimeException if the signal injection fails
     */
    public synchronized InternalSignal[] inject(InternalSignal injectedInternalSignal) throws RuntimeException {
        clearAllSignals();

        visitSignal(injectedInternalSignal);

        return getAllSignals(false);

    }

    // *********************************
    // ****** BASIC MAP METHODS ********
    // *********************************

    public int getMapSeed() {
        return gameMap.getSeed();
    }

    public GameMap getGameMap() {
        return gameMap;
    }

    public InternalRobot getObject(MapLocation loc) {
        return gameObjectsByLoc.get(loc);
    }

    public InternalRobot getRobot(MapLocation loc) {
        return getObject(loc);
    }

    public Collection<InternalRobot> allObjects() {
        return gameObjectsByID.values();
    }

    public InternalRobot[] getAllGameObjects() {
        return gameObjectsByID.values().toArray(
                new InternalRobot[gameObjectsByID.size()]);
    }

    public boolean exists(InternalRobot o) {
        return gameObjectsByID.containsKey(o.getID());
    }

    public int getMessage(int channel) {
        Integer val = radio.get(channel);
        return val == null ? 0 : val;
    }

    public GameStats getGameStats() {
        return gameStats;
    }

    public String getTeamName(Team t) {
        switch (t) {
        case A:
            return teamAName;
        case B:
            return teamBName;
        case NEUTRAL:
            return "neutralplayer";
        default:
            return null;
        }
    }

    public Team getWinner() {
        return winner;
    }

    public boolean isRunning() {
        return running;
    }

    public long[][] getTeamMemory() {
        return teamMemory;
    }

    public long[][] getOldTeamMemory() {
        return oldTeamMemory;
    }

    public void setTeamMemory(Team t, int index, long state) {
        teamMemory[t.ordinal()][index] = state;
    }

    public void setTeamMemory(Team t, int index, long state, long mask) {
        long n = teamMemory[t.ordinal()][index];
        n &= ~mask;
        n |= (state & mask);
        teamMemory[t.ordinal()][index] = n;
    }

    public int getCurrentRound() {
        return currentRound;
    }

    public InternalRobot getObjectByID(int id) {
        return gameObjectsByID.get(id);
    }

    // *********************************
    // ****** MISC UTILITIES ***********
    // *********************************

    /**
     * Store a signal, to be passed out of the world.
     * The signal should have already been processed.
     *
     * @param s the signal
     */
    private void addSignal(InternalSignal s) {
        currentInternalSignals.add(s);
    }

    /**
     * Clear all processed signals from the last round / injection.
     */
    private void clearAllSignals() {
        currentInternalSignals.clear();
    }

    public boolean canMove(MapLocation loc, RobotType type) {
        return gameMap.onTheMap(loc) &&
                gameObjectsByLoc.get(loc) == null;
    }
    
    public boolean isEmpty(MapLocation loc) {
        return gameMap.onTheMap(loc) && gameObjectsByLoc.get(loc) == null;
    }

    protected boolean canAttackSquare(InternalRobot ir, MapLocation loc) {
        MapLocation myLoc = ir.getLocation();
        int d = myLoc.distanceSquaredTo(loc);
        int radius = ir.getType().attackRadiusSquared;
        if (ir.getType() == RobotType.TURRET) {
            return (d <= radius && d >= GameConstants.TURRET_MINIMUM_RANGE);
        }
        return d <= radius;
    }

    // TODO: make a faster implementation of this
    public MapLocation[] getAllMapLocationsWithinRadiusSq(MapLocation center,
            int radiusSquared) {
        ArrayList<MapLocation> locations = new ArrayList<>();

        int radius = (int) Math.sqrt(radiusSquared);
        radius = Math.min(radius, Math.max(GameConstants.MAP_MAX_HEIGHT,
                GameConstants.MAP_MAX_WIDTH));

        int minXPos = center.x - radius;
        int maxXPos = center.x + radius;
        int minYPos = center.y - radius;
        int maxYPos = center.y + radius;

        for (int x = minXPos; x <= maxXPos; x++) {
            for (int y = minYPos; y <= maxYPos; y++) {
                MapLocation loc = new MapLocation(x, y);
                if (gameMap.onTheMap(loc)
                        && loc.distanceSquaredTo(center) <= radiusSquared)
                    locations.add(loc);
            }
        }

        return locations.toArray(new MapLocation[locations.size()]);
    }

    // TODO: make a faster implementation of this
    protected InternalRobot[] getAllRobotsWithinRadiusSq(MapLocation center,
            int radiusSquared) {
        if (radiusSquared == 0) {
            if (getRobot(center) == null) {
                return new InternalRobot[0];
            } else {
                return new InternalRobot[]{ getRobot(center) };
            }
        } else if (radiusSquared < 16) {
            MapLocation[] locs = getAllMapLocationsWithinRadiusSq(center,
                    radiusSquared);
            ArrayList<InternalRobot> robots = new ArrayList<>();
            for (MapLocation loc : locs) {
                InternalRobot res = getRobot(loc);
                if (res != null) {
                    robots.add(res);
                }
            }
            return robots.toArray(new InternalRobot[robots.size()]);
        }

        ArrayList<InternalRobot> robots = new ArrayList<>();

        for (InternalRobot o : gameObjectsByID.values()) {
            if (o == null)
                continue;
            if (o.getLocation() != null
                    && o.getLocation().distanceSquaredTo(center) <= radiusSquared)
                robots.add(o);
        }

        return robots.toArray(new InternalRobot[robots.size()]);
    }

    // Used by zombies.

    /**
     * @param loc the location to find nearest robots.
     * @return the info of the nearest player-controlled robot, or null
     *         if there are no player-controlled robots
     */
    public RobotInfo getNearestPlayerControlled(MapLocation loc) {
        int distSq = Integer.MAX_VALUE;
        ArrayList<MapLocation> closest = null;
        for (InternalRobot robot : gameObjectsByID.values()) {
            if (!robot.getTeam().isPlayer()) continue;
            
            MapLocation newLoc = robot.getLocation();
            int newDistSq = newLoc.distanceSquaredTo(loc);
            if (newDistSq < distSq) {
                closest = new ArrayList<MapLocation>();
                closest.add(newLoc);
                distSq = newDistSq;
            } else if (newDistSq == distSq) {
                closest.add(newLoc);
            }
        }

        if (closest == null) {
            return null;
        }
        
        return gameObjectsByLoc.get(closest.get(rand.nextInt(closest.size()))).getRobotInfo();
    }

    // *********************************
    // ****** ENGINE ACTIONS ***********
    // *********************************

    // should only be called by InternalRobot.setLocation
    public void notifyMovingObject(InternalRobot o, MapLocation oldLoc,
            MapLocation newLoc) {
        if (oldLoc != null) {
            if (gameObjectsByLoc.get(oldLoc) != o) {
                ErrorReporter
                        .report("Internal Error: invalid oldLoc in notifyMovingObject");
                return;
            }
            gameObjectsByLoc.remove(oldLoc);
        }
        if (newLoc != null) {
            gameObjectsByLoc.put(newLoc, o);
        }
    }

    // *********************************
    // ****** COUNTING ROBOTS **********
    // *********************************

    public int getRobotCount(Team team) {
        return robotCount[team.ordinal()];
    }

    public void incrementRobotCount(Team team) {
        robotCount[team.ordinal()]++;
    }

    public void decrementRobotCount(Team team) {
        robotCount[team.ordinal()]--;
    }

    // only returns active robots
    public int getRobotTypeCount(Team team, RobotType type) {
        if (robotTypeCount.get(team).containsKey(type)) {
            return robotTypeCount.get(team).get(type);
        } else {
            return 0;
        }
    }

    public void incrementRobotTypeCount(Team team, RobotType type) {
        if (robotTypeCount.get(team).containsKey(type)) {
            robotTypeCount.get(team).put(type,
                    robotTypeCount.get(team).get(type) + 1);
        } else {
            robotTypeCount.get(team).put(type, 1);
        }
    }
    
    // decrement from active robots (used during TTM <-> Turret transform)
    public void decrementRobotTypeCount(Team team, RobotType type) {
        Integer currentCount = getRobotTypeCount(team, type);
        robotTypeCount.get(team).put(type,currentCount - 1);
    }

    // *********************************
    // ****** RUBBLE METHODS **********
    // *********************************
    public double getRubble(MapLocation loc) {
        return 0;
    }
    
    public void alterRubble(MapLocation loc, double amount) {
    }

    // *********************************
    // ****** PARTS METHODS ************
    // *********************************
    public double getParts(MapLocation loc) {
        return 0;
    }

    public double takeParts(MapLocation loc) { // Remove parts from location
        return 0;
    }

    protected void adjustResources(Team t, double amount) {
        teamResources[t.ordinal()] += amount;
    }

    public double resources(Team t) {
        return teamResources[t.ordinal()];
    }

    // *********************************
    // ****** GAMEPLAY *****************
    // *********************************

    /**
     * Spawns a new robot with the given parameters.
     *
     * @param type the type of the robot
     * @param loc the location of the robot
     * @param team the team of the robot
     * @param buildDelay the build delay of the robot
     * @param parent the parent of the robot, or Optional.empty() if there is no parent
     * @return the ID of the spawned robot.
     */
    public int spawnRobot(RobotType type,
                           MapLocation loc,
                           Team team,
                           int buildDelay,
                           Optional<InternalRobot> parent) {

        int ID = idGenerator.nextID();

        visitSpawnSignal(new SpawnSignal(
                ID,
                parent.isPresent() ? parent.get().getID() : SpawnSignal.NO_ID,
                loc,
                type,
                team,
                buildDelay
        ));
        return ID;
    }

    public void processBeginningOfRound() {
        currentRound++;

        // process all gameobjects
        for (InternalRobot gameObject : gameObjectsByID.values()) {
            gameObject.processBeginningOfRound();
        }
    }

    public boolean setWinnerIfNonzero(double n, DominationFactor d) {
        if (n > 0)
            setWinner(Team.A, d);
        else if (n < 0)
            setWinner(Team.B, d);
        return n != 0;
    }

    public void setWinner(Team t, DominationFactor d) {
        winner = t;
        gameStats.setDominationFactor(d);
        // running = false;

    }

    public boolean timeLimitReached() {
        return currentRound >= gameMap.getRounds() - 1;
    }

    public boolean isArmageddonDaytime() {
        return false;
    }
    
    public void processEndOfRound() {
        // process all gameobjects
        for (InternalRobot gameObject : gameObjectsByID.values()) {
            gameObject.processEndOfRound();
        }

        // free parts
        teamResources[Team.A.ordinal()] += Math.max(0.0, GameConstants
                .ARCHON_PART_INCOME - GameConstants.PART_INCOME_UNIT_PENALTY
                * getRobotCount(Team.A));
        teamResources[Team.B.ordinal()] += Math.max(0.0, GameConstants
                .ARCHON_PART_INCOME - GameConstants.PART_INCOME_UNIT_PENALTY
                * getRobotCount(Team.B));

        // Add signals for team resources
        addSignal(new FluxChangeSignal(teamResources));
        addSignal(new ResearchChangeSignal(research));

        if (timeLimitReached() && winner == null) {
           //time limit damage to HQs
            for (InternalRobot r : baseHQs.values()) {
                r.takeDamage(GameConstants.TIME_LIMIT_DAMAGE);
            }
            
//          if both are killed by time limit damage in the same round, then more tie breakers
            if (baseHQs.get(Team.A).getEnergonLevel() <= 0.0 && baseHQs.get(Team.B).getEnergonLevel() <= 0.0) {
                // TODO more TIE BREAKHERS HERE
                InternalRobot HQA = baseHQs.get(Team.A);
                InternalRobot HQB = baseHQs.get(Team.B);
                double diff = HQA.getEnergonLevel() - HQB.getEnergonLevel();
                
                double campdiff = getEncampmentsByTeam(Team.A).size() - getEncampmentsByTeam(Team.B).size();
                
                if (!(
                        // first tie breaker - encampment count
                           setWinnerIfNonzero(campdiff, DominationFactor.BARELY_BEAT)
                        // second tie breaker - total energon difference
                        || setWinnerIfNonzero(getEnergonDifference(), DominationFactor.BARELY_BEAT)
                        // third tie breaker - mine count
                        || setWinnerIfNonzero(getMineDifference(), DominationFactor.BARELY_BEAT)
                     ))
                {
                        // fourth tie breaker - power difference
                    if (!(setWinnerIfNonzero(teamResources[Team.A.ordinal()] - teamResources[Team.B.ordinal()], DominationFactor.BARELY_BEAT)))
                    {
                        if (HQA.getID() < HQB.getID())
                            setWinner(Team.B, DominationFactor.WON_BY_DUBIOUS_REASONS);
                        else
                            setWinner(Team.A, DominationFactor.WON_BY_DUBIOUS_REASONS);
                    }
                }
            }
        }

        if (winner != null) {
            running = false;
        }


        long aPoints = Math.round(teamRoundResources[Team.A.ordinal()] * 100), bPoints = Math.round(teamRoundResources[Team.B.ordinal()] * 100);

        roundStats = new RoundStats(teamResources[0] * 100, teamResources[1] * 100, teamRoundResources[0] * 100, teamRoundResources[1] * 100);
        
        for (int x=0; x<teamResources.length; x++)
        {
            if (hasUpgrade(Team.values()[x], Upgrade.FUSION))
                teamResources[x] = teamResources[x]*GameConstants.POWER_DECAY_RATE_FUSION;
            else
                teamResources[x] = teamResources[x]*GameConstants.POWER_DECAY_RATE;
//          System.out.print(teamResources[x]+" ");
        }
//        System.out.println();
        
        lastRoundResources = teamRoundResources;
        teamRoundResources = new double[2];
    }

    public InternalSignal[] getAllSignals(boolean includeBytecodesUsedSignal) {
        ArrayList<InternalRobot> allRobots = new ArrayList<>();
        for (InternalRobot obj : gameObjectsByID.values()) {
            if (obj == null)
                continue;
            allRobots.add(obj);
        }

        InternalRobot[] robots = allRobots.toArray(new InternalRobot[allRobots.size()]);

        if (includeBytecodesUsedSignal) {
            currentInternalSignals.add(new BytecodesUsedSignal(robots));
        }
        currentInternalSignals.add(new RobotDelaySignal(robots));
        currentInternalSignals.add(new InfectionSignal(robots));

        HealthChangeSignal healthChange = new HealthChangeSignal(robots);

        // Reset health levels.
        for (final InternalRobot robot : robots) {
            robot.clearHealthChanged();
        }

        if (healthChange.getRobotIDs().length > 0) {
            currentInternalSignals.add(healthChange);
        }

        return currentInternalSignals.toArray(new InternalSignal[currentInternalSignals.size()]);
    }

    // ******************************
    // SIGNAL HANDLER METHODS
    // ******************************

    SignalHandler signalHandler = new AutoSignalHandler(this);

    public void visitSignal(InternalSignal s) {
        signalHandler.visitSignal(s);
    }


    @SuppressWarnings("unused")
    public void visitAttackSignal(AttackSignal s) {
        
        InternalRobot attacker = (InternalRobot) getObjectByID(s.getRobotID());
        MapLocation targetLoc = s.getTargetLoc();
        RobotLevel level = s.getTargetHeight();
        
        switch (attacker.getType()) {
        case SOLDIER:
            Direction dir = Direction.NORTH;
            MapLocation nearby;
            InternalRobot nearbyrobot;
            ArrayList<InternalRobot> todamage = new ArrayList<InternalRobot>();
            do {
                nearby = targetLoc.add(dir);
                nearbyrobot = getRobot(nearby, level);
                if (nearbyrobot != null)
                    if (nearbyrobot.getTeam() != attacker.getTeam())
                        todamage.add(nearbyrobot);
                dir = dir.rotateLeft();
            } while (dir != Direction.NORTH);
            if (todamage.size()>0) {
                double damage = attacker.getType().attackPower/todamage.size();
                for (InternalRobot r : todamage)
                    r.takeDamage(damage, attacker);
            }
            break;
        case ARTILLERY:
            InternalRobot target;
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++) {

                    target = getRobot(targetLoc.add(dx, dy), level);

                    if (target != null)
                        if (dx == 0 && dy == 0)
                            target.takeDamage(attacker.getType().attackPower, attacker);
                        else
                            target.takeDamage(attacker.getType().attackPower*GameConstants.ARTILLERY_SPLASH_RATIO, attacker);
                }

            break;
        default:
            // ERROR, should never happen
        }
        
        // TODO if we want units to not damange allied units
        // TODO CORY FIX IT
//        switch (attacker.getType()) {
//            case SOLDIER:
//                break;
//            case ARTILLERY:
//              int dist = (int)Math.sqrt(GameConstants.ARTILLERY_SPLASH_RADIUS_SQUARED);
//              InternalRobot target;
//                for (int dx=-dist; dx<=dist; dx++)
//                  for (int dy=-dist; dy<=dist; dy++)
//                  {
//                      if (dx==0 && dy==0) continue;
//                      target = getRobot(targetLoc.add(dx, dy), level);
//                      if (target != null)
//                          target.takeDamage(attacker.getType().attackPower, attacker);
//                  }
//                      
//                break;
//            default:
//              // ERROR, should never happen
//        }
        
        addSignal(s);
    }

    @SuppressWarnings("unused")
    public void visitBroadcastSignal(BroadcastSignal s) {
        radio.putAll(s.broadcastMap);
        s.broadcastMap = null;
        addSignal(s);
    }

    @SuppressWarnings("unused")
    public void visitBuildSignal(BuildSignal s) {
        int parentID = s.getParentID();
        MapLocation loc = s.getLoc();
        InternalRobot parent = getObjectByID(parentID);

        int cost = s.getType().partCost;
        adjustResources(s.getTeam(), -cost);

        // note: this also adds the signal

        spawnRobot(s.getType(),
                loc,
                s.getTeam(),
                s.getDelay(),
                Optional.of(parent));
    }


    @SuppressWarnings("unused")
    public void visitControlBitsSignal(ControlBitsSignal s) {
        InternalRobot r = getObjectByID(s.getRobotID());
        r.setControlBits(s.getControlBits());

        addSignal(s);
    }

    @SuppressWarnings("unused")
    public void visitDeathSignal(DeathSignal s) {
        if (!running) {
            // All robots emit death signals after the game
            // ends. We still want the client to draw
            // the robots.
            return;
        }

        int ID = s.getObjectID();
        InternalRobot obj = getObjectByID(ID);

        if (obj == null) {
            throw new RuntimeException("visitDeathSignal of nonexistent robot: "+s.getObjectID());
        }

        if (obj.getLocation() == null) {
            throw new RuntimeException("Object has no location: "+obj);
        }

        MapLocation loc = obj.getLocation();
        if (gameObjectsByLoc.get(loc) != obj) {
            throw new RuntimeException("Object location out of sync: "+obj);
        }

        if (obj instanceof InternalRobot) {
            InternalRobot r = (InternalRobot) obj;
            if (r.type == RobotType.SOLDIER && (r.getCapturingType() != null))
                teamCapturingNumber[r.getTeam().ordinal()]--;
            if (r.hasBeenAttacked()) {
                gameStats.setUnitKilled(r.getTeam(), currentRound);
            }
            if (r.type == RobotType.HQ) {
                setWinner(r.getTeam().opponent(), getDominationFactor(r.getTeam().opponent()));
            } else if (r.type.isEncampment) {
                encampmentMap.put(r.getLocation(), Team.NEUTRAL);
            }
        }

        decrementRobotTypeCount(obj.getTeam(), obj.getType());
        decrementRobotCount(obj.getTeam());

        


        controlProvider.robotKilled(obj);
        gameObjectsByID.remove(obj.getID());
        gameObjectsByLoc.remove(loc);

        

        addSignal(s);
    }

    @SuppressWarnings("unused")
    public void visitIndicatorDotSignal(IndicatorDotSignal s) {
        addSignal(s);
    }

    @SuppressWarnings("unused")
    public void visitIndicatorLineSignal(IndicatorLineSignal s) {
        addSignal(s);
    }

    @SuppressWarnings("unused")
    public void visitIndicatorStringSignal(IndicatorStringSignal s) {
        addSignal(s);
    }

    @SuppressWarnings("unused")
    public void visitMatchObservationSignal(MatchObservationSignal s) {
        addSignal(s);
    }

    @SuppressWarnings("unused")
    public void visitEnergonChangeSignal(EnergonChangeSignal s) {
        int[] robotIDs = s.getRobotIDs();
        double[] energon = s.getEnergon();
        for (int i = 0; i < robotIDs.length; i++) {
            InternalRobot r = (InternalRobot) getObjectByID(robotIDs[i]);
            System.out.println("el " + energon[i] + " " + r.getEnergonLevel());
            r.changeEnergonLevel(energon[i] - r.getEnergonLevel());
        }
    }
    
    @SuppressWarnings("unused")
    public void visitShieldChangeSignal(ShieldChangeSignal s) {
        int[] robotIDs = s.getRobotIDs();
        double[] shield = s.getShield();
        for (int i = 0; i < robotIDs.length; i++) {
            InternalRobot r = (InternalRobot) getObjectByID(robotIDs[i]);
            System.out.println("sh " + shield[i] + " " + r.getEnergonLevel());
            r.changeShieldLevel(shield[i] - r.getShieldLevel());
        }
    }

    @SuppressWarnings("unused")
    public void visitHatSignal(HatSignal s) {
        addSignal(s);
    }

    @SuppressWarnings("unused")
    public void visitMovementSignal(MovementSignal s) {
        InternalRobot r = getObjectByID(s.getRobotID());
        r.setLocation(s.getNewLoc());
        addSignal(s);
    }


    @SuppressWarnings("unused")
    public void visitMovementOverrideSignal(MovementOverrideSignal s) {
        InternalRobot r = getObjectByID(s.getRobotID());
        r.setLocation(s.getNewLoc());

        addSignal(s);
    }

    @SuppressWarnings({"unchecked", "unused"})
    public void visitSpawnSignal(SpawnSignal s) {
        // This robot has no id.
        // We need to assign it an id and spawn that.
        // Note that the current spawn signal is discarded.
        if (s.getRobotID() == SpawnSignal.NO_ID) {
            spawnRobot(
                    s.getType(),
                    s.getLoc(),
                    s.getTeam(),
                    s.getDelay(),
                    Optional.ofNullable(
                            gameObjectsByID.get(s.getParentID())
                    )
            );
            return;
        }

        InternalRobot parent;
        int parentID = s.getParentID();

        if (parentID == SpawnSignal.NO_ID) {
            parent = null;
        } else {
            parent = getObjectByID(parentID);
        }

        InternalRobot robot =
                new InternalRobot(
                        this,
                        s.getRobotID(),
                        s.getType(),
                        s.getLoc(),
                        s.getTeam(),
                        s.getDelay(),
                        Optional.ofNullable(parent)
                );

        incrementRobotTypeCount(s.getTeam(), s.getType());
        incrementRobotCount(s.getTeam());

        gameObjectsByID.put(s.getRobotID(), robot);

        if (s.getLoc() != null) {
            gameObjectsByLoc.put(s.getLoc(), robot);

        }

        if (s.getType().isEncampment)
        {
            encampmentMap.put(s.getLoc(), s.getTeam());
        }

        // Robot might be killed during creation if player
        // contains errors; enqueue the spawn before we
        // tell the control provider about it
        addSignal(s);

        controlProvider.robotSpawned(robot);
    }

    @SuppressWarnings("unused")
    public void visitResearchSignal(ResearchSignal s) {
        InternalRobot hq = (InternalRobot)getObjectByID(s.getRobotID());
//      hq.setResearching(s.getUpgrade());
        researchUpgrade(hq.getTeam(), s.getUpgrade());
        addSignal(s);
    }
    
    @SuppressWarnings("unused")
    public void visitMinelayerSignal(MinelayerSignal s) {
        // noop
        addSignal(s);
    }
    
    @SuppressWarnings("unused")
    public void visitCaptureSignal(CaptureSignal s) {
        // noop
        teamCapturingNumber[s.getTeam().ordinal()]++;
        addSignal(s);
    }
    
    @SuppressWarnings("unused")
    public void visitMineSignal(MineSignal s) {
        MapLocation loc = s.getMineLoc();
        if (s.shouldAdd()) {
            if (gameMap.getTerrainTile(loc) == TerrainTile.LAND) {
                addMine(s.getMineTeam(), loc);
            }
        } else {
            if (s.getMineTeam() != getMine(s.getMineLoc()))
                removeMines(s.getMineTeam(), loc);
        }
        addSignal(s);
    }
    
    @SuppressWarnings("unused")
    public void visitRegenSignal(RegenSignal s) {
        InternalRobot medbay = (InternalRobot) getObjectByID(s.robotID);
        
        MapLocation targetLoc = medbay.getLocation();
        RobotLevel level = RobotLevel.ON_GROUND;
        
        int dist = (int)Math.sqrt(medbay.type.attackRadiusMaxSquared);
        InternalRobot target;
        for (int dx=-dist; dx<=dist; dx++)
            for (int dy=-dist; dy<=dist; dy++)
            {
                if (dx*dx+dy*dy > medbay.type.attackRadiusMaxSquared) continue;
                target = getRobot(targetLoc.add(dx, dy), level);
                if (target != null)
                    if (target.getTeam() == medbay.getTeam() && target.type != RobotType.HQ)
                        target.takeDamage(-medbay.type.attackPower, medbay);
            }
        addSignal(s);
    }
    
    @SuppressWarnings("unused")
    public void visitShieldSignal(ShieldSignal s) {
        InternalRobot shields = (InternalRobot) getObjectByID(s.robotID);
        
        MapLocation targetLoc = shields.getLocation();
        RobotLevel level = RobotLevel.ON_GROUND;
        
        int dist = (int)Math.sqrt(shields.type.attackRadiusMaxSquared);
        InternalRobot target;
        for (int dx=-dist; dx<=dist; dx++)
            for (int dy=-dist; dy<=dist; dy++)
            {
                if (dx*dx+dy*dy > shields.type.attackRadiusMaxSquared) continue;
                target = getRobot(targetLoc.add(dx, dy), level);
                if (target != null)
                    if (target.getTeam() == shields.getTeam())
                        target.takeShieldedDamage(-shields.type.attackPower);
            }
        addSignal(s);
    }
    
    @SuppressWarnings("unused")
    public void visitScanSignal(ScanSignal s) {
//      nothing needs to be done
        addSignal(s);
    }
    
    @SuppressWarnings("unused")
    public void visitNodeBirthSignal(NodeBirthSignal s) {
        addEncampment(s.location, Team.NEUTRAL);
        addSignal(s);
    }

    @SuppressWarnings("unused")
    public void visitTypeChangeSignal(TypeChangeSignal s) {
        addSignal(s);
    }
}

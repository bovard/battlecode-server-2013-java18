package battlecode.common;

/**
 * Defines constants that affect gameplay.
 */
@SuppressWarnings("unused")
public interface GameConstants {

    // *********************************
    // ****** MAP CONSTANTS ************
    // *********************************

    // *********************************
    // ****** GAME PARAMETERS **********
    // *********************************


    /** Maximum archons that can appear on a map (per team). */
    int NUMBER_OF_ARCHONS_MAX = 4;

    /** Represents the multiple of the sightRange for which the cost is fixed. */
    double BROADCAST_RANGE_MULTIPLIER = 2;

    /** Represents the base delay increase of a broadcast. */
    double BROADCAST_BASE_DELAY_INCREASE = 0.05;

    /** The additional delay increase of broadcasting beyond the base cost.
     *  See specs for the formula. */
    double BROADCAST_ADDITIONAL_DELAY_INCREASE = 0.03;

    // *********************************
    // ****** PARTS *******************
    // *********************************

    /** The amount that each team starts with */
    double PARTS_INITIAL_AMOUNT = 300;
    
    /** The part income per turn (independent of number of archons).  */
    double ARCHON_PART_INCOME = 2;

    /** The decrease in part income per turn per unit that you have. */
    double PART_INCOME_UNIT_PENALTY = 0.01;

    /** The parts reward for destroying a zomie den */
    double DEN_PART_REWARD = 200;

    // *********************************
    // ****** RUBBLE *******************
    // *********************************

    /** The threshold of rubble that robots (except SCOUTs) can't move
     * through. */
    double RUBBLE_OBSTRUCTION_THRESH = 100;

    /** The threshold of rubble that slows robots (except SCOUTS). */
    double RUBBLE_SLOW_THRESH = 50;

    /** Percentage of rubble removed with each clear. */
    double RUBBLE_CLEAR_PERCENTAGE = 0.05;

    /** Flat amount of rubble removed with each clear. */
    double RUBBLE_CLEAR_FLAT_AMOUNT = 10;

    /** The fraction of rubble produced from a turret kill. */
    double RUBBLE_FROM_TURRET_FACTOR = 1.0 / 3.0;

    // *********************************
    // ****** UNIT PROPERTIES **********
    // *********************************
   
    /** Guard's attack is scaled by this when attacking a Zombie opponent. */
    double GUARD_ZOMBIE_MULTIPLIER = 2;
    
    /** Guard takes less damage from attacks dealing more than this much damage. */
    double GUARD_DEFENSE_THRESHOLD = 10;
    
    /** Amount of damage guards can block */
    double GUARD_DAMAGE_REDUCTION = 4;
    
    /** Damage a robot receives from a Viper's infection per turn */
    double VIPER_INFECTION_DAMAGE = 2;

    /** Minimum attack range (range squared) of a Turret */
    int TURRET_MINIMUM_RANGE = 6;

    /** Time to transform between Turret and TTM */
    int TURRET_TRANSFORM_DELAY = 10;

    /** The factor that delays are multiplied by when a unit moves diagonally. */
    double DIAGONAL_DELAY_MULTIPLIER = 1.4;

    /** Amount an archon repairs another bot for. */
    double ARCHON_REPAIR_AMOUNT = 1.0;

    /** Archon activation range (ranged squared). */
    int ARCHON_ACTIVATION_RANGE = 2;

    /** Amount of damage robots take when standing next to dens that are spawning. **/
    double DEN_SPAWN_PROXIMITY_DAMAGE = 10.0;

    /** Number of turns that elapse for the zombie outbreak level to increase */
    int OUTBREAK_TIMER = 300;

    // *********************************
    // ****** ARMAGEDDON ***************
    // *********************************

    /** Armageddon: number of turns in day/night cycle **/
    int ARMAGEDDON_DAY_TIMER = 300;
    int ARMAGEDDON_NIGHT_TIMER = 900;
    
    /** Armageddon: day/night outbreak multiplier **/
    double ARMAGEDDON_DAY_OUTBREAK_MULTIPLIER = 1.0;
    double ARMAGEDDON_NIGHT_OUTBREAK_MULTIPLIER = 2.0;
    
    /** Armageddon: zombie regeneration levels night and day **/
    double ARMAGEDDON_DAY_ZOMBIE_REGENERATION = -0.2;
    double ARMAGEDDON_NIGHT_ZOMBIE_REGENERATION = 0.05;
    
    // *********************************
    // ****** MESSAGING ****************
    // *********************************

    /** The maximum size of the message queue. Any more messages push the oldest message out */
    int SIGNAL_QUEUE_MAX_SIZE = 1000;
    
    /** The maximum number of basic signals a robot can send per turn */
    int BASIC_SIGNALS_PER_TURN = 5;
    
    /** The maximum number of message signals a robot can send per turn */
    int MESSAGE_SIGNALS_PER_TURN = 20;
    
    // *********************************
    // ****** GAMEPLAY PROPERTIES ******
    // *********************************

    /** The default game seed. **/
    int GAME_DEFAULT_SEED = 6370;

    /** The default game maxiumum number of rounds. **/
    int GAME_DEFAULT_ROUNDS = 3000;


    /** The minimum possible map height. */
    int MAP_MIN_HEIGHT = 20;
    
    /** The maximum possible map height. */
    int MAP_MAX_HEIGHT = 70;

    /** The minumum possible map width. */
    int MAP_MIN_WIDTH = 20;
    
    /** The maxiumum possible map width. */
    int MAP_MAX_WIDTH = 70;
    
    /** A valid map must have at least this many encampment locations. */
    int MAP_MINIMUM_ENCAMPMENTS = 5;
    
    /** The bytecode penalty that is imposed each time an exception is thrown */
    int EXCEPTION_BYTECODE_PENALTY = 500;
    
    /** The number of indicator strings that a player can associate with a robot */
    int NUMBER_OF_INDICATOR_STRINGS = 3;
    
    /** The base number of bytecodes a robot can execute each round */
    int BYTECODE_LIMIT = 10000;
    
    /** The number of longs that your team can remember between games. */
    int TEAM_MEMORY_LENGTH = 32;

    /** The total amount of damage to be applied to a team's HQ once the round limit is reached */
    double TIME_LIMIT_DAMAGE = 1.0;
 
    /** The upkeep cost of a unit per round. Note that units pay even more than this base cost to execute bytecodes */
    double UNIT_POWER_UPKEEP = 1.0;
    
    /** If a team cannot pay a unit's upkeep in power, it pays this cost in energon instead. */
    double UNIT_ENERGON_UPKEEP = 5.0;
    
    /** The minimum possible round at which nodes may begin taking end-of-round damage */
    int ROUND_MIN_LIMIT = 2000;
    
    /** The maximum possible round at which nodes may begin taking end-of-round damage */
    int ROUND_MAX_LIMIT = 2000;
    
    /** Radius of artillery splash damage in units squared */
    int ARTILLERY_SPLASH_RADIUS_SQUARED = 2;
    
    /** Percantage of direct artillery damage that the splash damage does */
    double ARTILLERY_SPLASH_RATIO = 0.3;
   
    /** Rate at which SHIELDS decay. This number will be subtracted from each unit's shield pool after its turn. */
    double SHIELD_DECAY_RATE = 1.0;
   
    /** Extra sight radius bonus to unit vision when VISION is researched */
    int VISION_UPGRADE_BONUS = 19;
   
    /** Base power production per HQ */
    double HQ_POWER_PRODUCTION = 40;
    
    /** Additiona power provided by each generator */
    double GENERATOR_POWER_PRODUCTION = 10;
    
    /** Maximum amount of shields a single robot can carry */
    double SHIELD_CAP = 100000000.0;
    
    /** The energy to bytecode converstion rate */
    double POWER_COST_PER_BYTECODE = 0.0001;
    
    /** The maximum read/write-able of radio channel number */
    int BROADCAST_MAX_CHANNELS = 65535;
    
    /** The power cost required to broadcast a message to a single radio channel */
    double BROADCAST_SEND_COST = 0.03;
    
    /** The power cost required to read a message from a single radio channel */
    double BROADCAST_READ_COST = 0.003;
   
    /** The number of rounds required by a soldier to lay a mine */
    int MINE_LAY_DELAY = 25;
    
    /** The number of rounds required by a soldier to defuse a mine */
    int MINE_DEFUSE_DELAY = 12;

    /** The number of rounds required by a soldier to defuse a mine if they have DEFUSION upgrade */
    int MINE_DEFUSE_DEFUSION_DELAY = 5;
   
    /** The power cost required to begin an encampement capture */
    double CAPTURE_POWER_COST = 10;
    
    /** The number of rounds required by a SOLDIER to capture an encampment */
    int CAPTURE_ROUND_DELAY = 50;
   
    /** The amount of damage that a mine deals to a robot standing on it per round */
    double MINE_DAMAGE = 10;
    
    /** The percentage of mine damage that shields can absorb */
    double MINE_DAMAGE_RATIO_ABSORBED_BY_SHIELD = 0.9;
    
    /** The rate at which stockpiled power decays without the FUSION upgrade */
    double POWER_DECAY_RATE = 0.80;
    
    /** The rate at which stockpiled energy decays with the FUSION upgrade */
    double POWER_DECAY_RATE_FUSION = 0.99;
    
    /** Rounds required to spawn a unit at the start of the game */
    int HQ_SPAWN_DELAY = 10;

    /** Constant used to calculate how suppliers factor into the HQ spawn delay */
    double HQ_SPAWN_DELAY_CONSTANT = 10;
    
    /** Amount of power required to wear a hat */
    double HAT_POWER_COST = 40.0;
}

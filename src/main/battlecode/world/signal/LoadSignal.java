package battlecode.world.signal;

import battlecode.engine.signal.Signal;
import battlecode.world.InternalRobot;

public class LoadSignal implements InternalSignal {

    public final int transportID;
    public final int passengerID;

    public LoadSignal(InternalRobot transport, InternalRobot passenger) {
        transportID = transport.getID();
        passengerID = passenger.getID();
    }

}

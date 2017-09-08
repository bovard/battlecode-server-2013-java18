package battlecode.world.signal;

import battlecode.engine.signal.Signal;
import battlecode.world.InternalRobot;

public class ScanSignal implements InternalSignal {

	private static final long serialVersionUID = 962748541149750719L;
	public final int robotID;

    public ScanSignal(InternalRobot r) {
        robotID = r.getID();
    }

}

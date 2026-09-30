package dev.watchcraft.watchcraft.entity;

/**
 * Plain, side-agnostic holder for the state the (client only) drone controller owns.
 * Kept free of any client class reference so a dedicated server can load this safely.
 */
public final class DroneControlState {
    /** Entity id of the drone the local client is currently linked to, or -1. */
    public static int pilotedDroneId = -1;

    private DroneControlState() {
    }

    public static void reset() {
        pilotedDroneId = -1;
    }
}

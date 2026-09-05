package com.agent.software.tools.toolkits.client;

/**
 * Global mutex lock for communicating with the client.
 *
 * talk_to_client blocks and waits for user input; only one member at a time is allowed to talk to the client:
 * when the lock is held, other members calling talk_to_client immediately receive an error message,
 * avoiding multiple people competing for console input at the same time.
 *
 * The process-level default singleton is obtained via {@link #getInstance()}; each {@link
 * com.agent.software.AgentSystem} holds its own lock instance directly, so multiple systems do not block each other.
 * The constructor is public, making it easy to create independent instances for testing and multi-instance scenarios.
 */
public final class ClientCommunicationLock {

    private static final ClientCommunicationLock INSTANCE = new ClientCommunicationLock();

    private String holderRoleId = null;
    private String holderName = null;

    public ClientCommunicationLock() {
    }

    public static ClientCommunicationLock getInstance() {
        return INSTANCE;
    }

    /**
     * Try to acquire the exclusive right to communicate with the client.
     *
     * @return null means acquisition succeeded; otherwise returns an error description (current holder info).
     *         Repeated acquisition by the same role is treated as success (re-entrant) and will not lock itself out.
     */
    public synchronized String tryAcquire(String roleId, String name) {
        if (holderRoleId != null && !holderRoleId.equals(roleId)) {
            return "another member " + holderName + " (" + holderRoleId
                    + ") is currently talking to the client";
        }
        holderRoleId = roleId;
        holderName = name;
        return null;
    }

    /** Release the lock (only the holder can release it). Idempotent. */
    public synchronized void release(String roleId) {
        if (holderRoleId != null && holderRoleId.equals(roleId)) {
            holderRoleId = null;
            holderName = null;
        }
    }

    /** Whether it is currently held. */
    public synchronized boolean isHeld() {
        return holderRoleId != null;
    }

    /** role_id of the current holder (null when the lock is free). */
    public synchronized String holderRoleId() {
        return holderRoleId;
    }

    /** Display name of the current holder (null when the lock is free). */
    public synchronized String holderName() {
        return holderName;
    }

    /** Description of the current holder (returns null if not held). */
    public synchronized String holderDescription() {
        return holderRoleId == null ? null : holderName + " (" + holderRoleId + ")";
    }
}

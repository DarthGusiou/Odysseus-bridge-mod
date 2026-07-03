package com.odysseus.bridge;

import baritone.api.BaritoneAPI;
import baritone.api.event.events.PathEvent;
import baritone.api.event.listener.AbstractGameEventListener;

/**
 * Subscribes to Baritone's process events and forwards them to Odysseus
 * over the bridge as JSON messages. This is how the panel shows live
 * status without any server chat.
 *
 * Kept isolated in its own class so if Baritone's event API isn't
 * present at runtime (older / newer version), the rest of the addon
 * still works — see try/catch in OdysseusBridge.onInitializeClient.
 */
public class BaritoneHook {

    /** Attach event listeners to Baritone; called once on addon init. */
    public static void install(BridgeClient client) {
        AbstractGameEventListener listener = new AbstractGameEventListener() {
            @Override
            public void onPathEvent(PathEvent event) {
                String eventName = switch (event) {
                    case CALC_STARTED       -> "path_start";
                    case CALC_FINISHED_NOW_EXECUTING -> "path_start";
                    case CONTINUING_ONTO_PLANNED_NEXT -> "path_step";
                    case AT_GOAL            -> "goal_reached";
                    case PATH_FINISHED_NEXT_STILL_GOING -> "path_step";
                    case CALC_FAILED        -> "path_failed";
                    case CANCELED           -> "cancelled";
                    default                 -> "log";
                };
                client.sendEvent(eventName, event.name());
            }
        };
        BaritoneAPI.getProvider().getPrimaryBaritone()
                .getGameEventHandler().registerEventListener(listener);
    }
}

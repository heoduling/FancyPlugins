package com.fancyinnovations.fancynpcsmodel.listeners;

import com.fancyinnovations.fancynpcsmodel.fancynpcshook.CustomModelAttribute;
import de.oliver.fancynpcs.api.events.NpcModifyEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public class NpcTransformListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNpcModify(NpcModifyEvent event) {
        if (!CustomModelAttribute.hasAttribute(event.getNpc())) {
            return;
        }

        if (event.getModification() == NpcModifyEvent.NpcModification.LOCATION
                || event.getModification() == NpcModifyEvent.NpcModification.ROTATION) {
            CustomModelAttribute.invalidateTrackerForTransform(event.getNpc());
        }
    }
}

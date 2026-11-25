package de.oliver.fancynpcs;

import de.oliver.fancynpcs.api.actions.ActionManager;
import de.oliver.fancynpcs.api.actions.NpcAction;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ActionManagerImpl implements ActionManager {

    private final Map<String, NpcAction> actions = new ConcurrentHashMap<>();

    @Override
    public void registerAction(NpcAction action) {
        actions.put(action.getName(), action);
    }

    @Override
    public NpcAction getActionByName(String name) {
        return actions.getOrDefault(name, null);
    }

    @Override
    public void unregisterAction(NpcAction action) {
        actions.computeIfPresent(action.getName(), (name, current) -> current == action ? null : current);
    }

    @Override
    public List<NpcAction> getAllActions() {
        return List.copyOf(actions.values());
    }
}

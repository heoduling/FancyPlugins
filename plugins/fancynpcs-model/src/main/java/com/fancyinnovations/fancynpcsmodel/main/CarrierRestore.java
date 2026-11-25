package com.fancyinnovations.fancynpcsmodel.main;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class CarrierRestore {
    private CarrierRestore() {}

    public static boolean setVirtualAppearance(Entity carrier, boolean masked, boolean invisible, boolean glowing) {
        var access = virtualAccess(carrier);
        if (access == null) return false;
        try {
            Object handle = access.handle.invoke(carrier);
            // FancyNpcs creates packet-only carriers, not entities registered in the world.
            access.persistentInvisibility.setBoolean(handle, masked);
            access.setFlag.invoke(handle, 5, invisible);
            access.setGlowing.invoke(handle, glowing);
            if (masked) {
                access.setName.invoke(handle, (Object) null);
                access.setNameVisible.invoke(handle, false);
            }
            return true;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not set the FancyNpcs carrier appearance", e);
        }
    }

    public static void syncVirtualAppearance(Entity carrier, Player viewer) {
        var access = virtualAccess(carrier);
        if (access == null) return;
        try {
            access.refresh.invoke(access.handle.invoke(carrier), access.handle.invoke(viewer));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not send the FancyNpcs carrier appearance", e);
        }
    }

    private static VirtualAccess virtualAccess(Entity carrier) {
        String version = Bukkit.getMinecraftVersion();
        return !carrier.isValid() && (version.equals("26.2") || version.startsWith("26.2."))
                ? VirtualAccess.INSTANCE : null;
    }

    private static final class VirtualAccess {
        private static final VirtualAccess INSTANCE = resolve();
        private final Method handle, setFlag, setGlowing, setName, setNameVisible, refresh;
        private final Field persistentInvisibility;

        private VirtualAccess() throws ReflectiveOperationException {
            ClassLoader loader = Bukkit.getServer().getClass().getClassLoader();
            Class<?> craft = Class.forName("org.bukkit.craftbukkit.entity.CraftEntity", false, loader);
            Class<?> entity = Class.forName("net.minecraft.world.entity.Entity", false, loader);
            Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer", false, loader);
            Class<?> component = Class.forName("net.minecraft.network.chat.Component", false, loader);
            handle = craft.getMethod("getHandleRaw");
            persistentInvisibility = entity.getField("persistentInvisibility");
            setFlag = entity.getMethod("setSharedFlag", int.class, boolean.class);
            setGlowing = entity.getMethod("setGlowingTag", boolean.class);
            setName = entity.getMethod("setCustomName", component);
            setNameVisible = entity.getMethod("setCustomNameVisible", boolean.class);
            refresh = entity.getMethod("refreshEntityData", serverPlayer);
        }

        private static VirtualAccess resolve() {
            try {
                return new VirtualAccess();
            } catch (ReflectiveOperationException e) {
                FancyNpcsModelPlugin.get().getLogger().log(Level.WARNING, "Packet-only carrier appearance is unavailable", e);
                return null;
            }
        }
    }

    public static void afterDetach(Plugin model, Plugin host, Entity entity, Location location, boolean invisible, boolean glowing) {
        if (Bukkit.isStopping()) return;
        Runnable restore = () -> {
            Plugin current = Bukkit.getPluginManager().getPlugin(model.getName());
            if (current != null && current != model && current.isEnabled()) return;
            if (Bukkit.isOwnedByCurrentRegion(entity)) {
                entity.setInvisible(invisible);
                entity.setGlowing(glowing);
                model.getLogger().fine("Restored the NPC carrier on its owning region.");
            } else {
                model.getLogger().warning("NPC moved out of its region before carrier restoration.");
            }
        };
        if (Bukkit.isOwnedByCurrentRegion(entity)) {
            restore.run();
        } else if (host.isEnabled() && location != null && location.getWorld() != null) {
            // The addon is already disabling. The still-enabled carrier owner submits the work.
            Bukkit.getRegionScheduler().execute(host, location, restore);
        } else {
            model.getLogger().warning("Could not schedule NPC carrier restoration.");
        }
    }
}

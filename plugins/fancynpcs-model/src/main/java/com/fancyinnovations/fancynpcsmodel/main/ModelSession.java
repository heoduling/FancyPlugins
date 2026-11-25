package com.fancyinnovations.fancynpcsmodel.main;

import org.bukkit.plugin.Plugin;
import org.bukkit.command.CommandSender;

public interface ModelSession {
    Plugin host();

    void start();

    void stop(boolean restoreCarriers);

    int modelCount();

    void publicNpc(CommandSender sender, String target, String mode);
}

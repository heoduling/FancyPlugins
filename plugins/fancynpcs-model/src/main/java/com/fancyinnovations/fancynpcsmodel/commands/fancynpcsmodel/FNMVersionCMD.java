package com.fancyinnovations.fancynpcsmodel.commands.fancynpcsmodel;

import com.fancyinnovations.fancynpcsmodel.utils.FancyContext;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.bukkit.command.CommandSender;

public class FNMVersionCMD extends FancyContext {

    public static final FNMVersionCMD INSTANCE = new FNMVersionCMD();

    private FNMVersionCMD() {
    }

    public void version(CommandSender sender) {
        var translator = translator();
        String localVersion = plugin.getPluginMeta().getVersion();
        translator.translate("commands.fancynpcsmodel.version.current_version")
                .withPrefix()
                .replace("version", localVersion)
                .send(sender);

        ComparableVersion newestVersion = plugin.getNewestVersion();
        if (newestVersion != null && new ComparableVersion(localVersion).compareTo(newestVersion) < 0) {
            translator.translate("commands.fancynpcsmodel.version.version_outdated")
                    .withPrefix()
                    .replace("version", localVersion)
                    .replace("latestVersion", newestVersion.toString())
                    .replace("downloadURL", plugin.getVersionFetcher().getDownloadUrl())
                    .send(sender);
        }
    }

}

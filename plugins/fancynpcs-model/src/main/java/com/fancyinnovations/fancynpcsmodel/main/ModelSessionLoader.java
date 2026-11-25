package com.fancyinnovations.fancynpcsmodel.main;

import java.net.URL;
import java.net.URLClassLoader;

final class ModelSessionLoader extends URLClassLoader {
    private static final String ROOT = "com.fancyinnovations.fancynpcsmodel.";
    private final ClassLoader hostLoader;

    ModelSessionLoader(URL jar, ClassLoader modelLoader, ClassLoader hostLoader) {
        super(new URL[]{jar}, modelLoader);
        this.hostLoader = hostLoader;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith("de.oliver.fancynpcs.") || name.startsWith("de.oliver.cloud.")
                || name.startsWith("com.fancyinnovations.config.featureflags.")) {
            return hostLoader.loadClass(name);
        }
        if (name.startsWith(ROOT + "integration.") || name.startsWith(ROOT + "commands.npc.")
                || name.startsWith(ROOT + "fancynpcshook.") || name.startsWith(ROOT + "listeners.")) {
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null) type = findClass(name);
                if (resolve) resolveClass(type);
                return type;
            }
        }
        return super.loadClass(name, resolve);
    }
}

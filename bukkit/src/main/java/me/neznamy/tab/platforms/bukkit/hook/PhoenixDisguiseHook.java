package me.neznamy.tab.platforms.bukkit.hook;

import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.features.disguise.DisguiseIdentity;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.UUID;

/**
 * Reads Phoenix disguises through reflection, so TAB keeps working without Phoenix and without
 * a compile-time dependency on it. Every lookup goes by the player's real UUID.
 */
public class PhoenixDisguiseHook {

    private final Method getInstance;
    private final Method getProfileHandler;
    private final Method getCachedProfile;
    private final Method getDisguiseData;
    private final Method isDisguised;
    private final Method getDisguiseName;
    private final Method getDisguiseUuid;

    /** Set after the first failed call, so a broken Phoenix does not spam the console */
    private volatile boolean broken;

    private PhoenixDisguiseHook(@NotNull ClassLoader loader) throws ReflectiveOperationException {
        Class<?> phoenix = Class.forName("xyz.refinedev.phoenix.Phoenix", false, loader);
        Class<?> profileHandler = Class.forName("xyz.refinedev.phoenix.handler.IProfileHandler", false, loader);
        Class<?> profile = Class.forName("xyz.refinedev.phoenix.profile.IProfile", false, loader);
        Class<?> disguiseData = Class.forName("xyz.refinedev.phoenix.profile.disguise.IDisguiseData", false, loader);
        getInstance = phoenix.getMethod("getInstance");
        getProfileHandler = publicMethod(phoenix, "getProfileHandler");
        getCachedProfile = profileHandler.getMethod("getCachedProfile", UUID.class);
        getDisguiseData = profile.getMethod("getDisguiseData");
        isDisguised = disguiseData.getMethod("isDisguised");
        getDisguiseName = disguiseData.getMethod("getDisguiseName");
        getDisguiseUuid = disguiseData.getMethod("getDisguiseUuid");
    }

    /**
     * Creates the hook if an enabled plugin provides Phoenix. Phoenix may be loaded by a loader plugin,
     * so the plugin named "Phoenix" is tried first and then every enabled plugin's class loader.
     *
     * @return  Hook, or {@code null} if Phoenix is missing (or not enabled yet)
     */
    @Nullable
    public static PhoenixDisguiseHook create() {
        Plugin named = Bukkit.getPluginManager().getPlugin("Phoenix");
        if (named != null && named.isEnabled()) {
            PhoenixDisguiseHook hook = tryCreate(named.getClass().getClassLoader());
            if (hook != null) return hook;
        }
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (!plugin.isEnabled() || plugin == named) continue;
            PhoenixDisguiseHook hook = tryCreate(plugin.getClass().getClassLoader());
            if (hook != null) return hook;
        }
        return null;
    }

    @Nullable
    private static PhoenixDisguiseHook tryCreate(@NotNull ClassLoader loader) {
        try {
            return new PhoenixDisguiseHook(loader);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /**
     * Returns the disguise of the player.
     *
     * @param   realId
     *          Player's real UUID
     * @return  Disguise identity, or {@code null} if the player is not disguised
     */
    @Nullable
    public DisguiseIdentity getDisguise(@NotNull UUID realId) {
        if (broken) return null;
        try {
            Object phoenix = getInstance.invoke(null);
            if (phoenix == null) return null;
            Object handler = getProfileHandler.invoke(phoenix);
            if (handler == null) return null;
            Object profile = getCachedProfile.invoke(handler, realId);
            if (profile == null) return null;
            Object data = getDisguiseData.invoke(profile);
            if (data == null || !Boolean.TRUE.equals(isDisguised.invoke(data))) return null;
            String name = (String) getDisguiseName.invoke(data);
            if (name == null || name.isEmpty()) return null;
            return new DisguiseIdentity(realId, name, (UUID) getDisguiseUuid.invoke(data));
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            broken = true;
            TAB.getInstance().getErrorManager().criticalError("Failed to read a Phoenix disguise. Disguised players will show real ping and name until TAB is reloaded.", e);
            return null;
        }
    }

    /**
     * Finds a public method callable through reflection. The implementation class may be non-public,
     * in which case the method is looked up on its public supertypes.
     *
     * @param   type
     *          Class to search
     * @param   name
     *          Method name
     * @return  Callable method
     * @throws  NoSuchMethodException
     *          If no public declaration exists
     */
    @NotNull
    private static Method publicMethod(@NotNull Class<?> type, @NotNull String name) throws NoSuchMethodException {
        Method method = type.getMethod(name);
        if (Modifier.isPublic(method.getDeclaringClass().getModifiers())) return method;
        for (Class<?> superType = type; superType != null; superType = superType.getSuperclass()) {
            for (Class<?> anInterface : superType.getInterfaces()) {
                try {
                    return publicMethod(anInterface, name);
                } catch (NoSuchMethodException ignored) {
                    // Not declared here
                }
            }
        }
        throw new NoSuchMethodException(type.getName() + "#" + name + " is not publicly accessible");
    }
}

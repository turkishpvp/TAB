package me.neznamy.tab.platforms.bukkit;

import lombok.Getter;
import lombok.Setter;
import me.neznamy.tab.platforms.bukkit.provider.ImplementationProvider;
import me.neznamy.tab.shared.ProtocolVersion;
import me.neznamy.tab.shared.util.ReflectionUtils;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Class for detecting server software and version and finding available NMS implementation(s).
 */
@Getter
public class ServerVersionInfo {

    /** Package name of the server implementation, null on Paper 1.20.5+ / Spigot 26+ */
    @Nullable
    private final String serverPackage;

    /** Minecraft version as returned by the platform */
    @NotNull
    private final String minecraftVersion;

    /** Name of the server software, either "Paper" or "Spigot" */
    @NotNull
    private final String serverName;

    /** Server version */
    private final ProtocolVersion serverVersion;

    /** Implementation for creating new instances using content available on the server */
    @NotNull
    @Setter
    private ImplementationProvider implementationProvider;

    /** Name of the implementation package, v1_8_R3 on supported servers */
    @Nullable
    private String implementationPackage;

    /**
     * Constructs new instance and detects server version info.
     */
    public ServerVersionInfo() {
        // Server package
        String CRAFTBUKKIT_PACKAGE = Bukkit.getServer().getClass().getPackage().getName();
        String[] array = CRAFTBUKKIT_PACKAGE.split("\\.");
        serverPackage = array.length > 3 ? array[3] : null;

        // Minecraft version
        if (ReflectionUtils.methodExists(Bukkit.class, "getMinecraftVersion")) {
            serverName = "Paper";
            minecraftVersion = Bukkit.getMinecraftVersion();
        } else {
            serverName = "Spigot";
            minecraftVersion = Bukkit.getBukkitVersion().split("-")[0];
        }

        // Minecraft version to server version
        serverVersion = ProtocolVersion.fromFriendlyName(minecraftVersion);

        // Find implementation
        implementationProvider = findImplementationProvider();
    }

    /**
     * Finds implementation provider for current server software and version.
     *
     * @return  Implementation provider for current server
     * @throws  IllegalStateException
     *          If no implementation was found
     */
    @NotNull
    private ImplementationProvider findImplementationProvider() {
        if (serverPackage == null) {
            // Paper 1.20.5+ / Spigot 26+, which this build does not contain implementations for
            throw new IllegalStateException(String.format(
                    "Your server version (%s %s) is not supported, this build only supports 1.8.8.",
                    serverName, minecraftVersion
            ));
        }
        try {
            implementationPackage = serverPackage;
            return (ImplementationProvider) Class.forName("me.neznamy.tab.platforms.bukkit." + serverPackage + ".NMSImplementationProvider").getConstructor().newInstance();
        } catch (ReflectiveOperationException ignored) {
            throw new IllegalStateException(String.format(
                    "Your server version (%s - %s) is not supported, this build only supports 1.8.8.",
                    minecraftVersion, serverPackage
            ));
        }
    }
}

package me.neznamy.tab.platforms.bukkit.v1_8_R3;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import lombok.SneakyThrows;
import me.neznamy.tab.platforms.bukkit.platform.BukkitPlatform;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.features.nametags.MultiLinePlayerData;
import me.neznamy.tab.shared.platform.MultiLineRenderer;
import me.neznamy.tab.shared.platform.TabPlayer;
import me.neznamy.tab.shared.util.ReflectionUtils;
import net.minecraft.server.v1_8_R3.*;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Multi-line nametags for 1.8.8, a native port of MultiLineAPI's mount renderer.
 * <p>
 * Lines are invisible marker armor stands chained on top of the player with invisible spacer entities between them:
 * {@code player <- spacers <- stand(lowest line) <- spacers <- stand <- ...}. 1.8 allows one passenger per vehicle and
 * positions a rider at {@code vehicle y + vehicle height * 0.75 + rider y offset}: 1.35 above the player, 0 per marker
 * armor stand (label renders 0.5 above it) and a fixed step per spacer type, including negative step of a slime with
 * negative size. Configured heights are approximated by the best combination of spacers (usually within 1 cm).
 * The client moves the whole chain with the player, so movement costs no packets.
 * <p>
 * Lowering lines on sneak (like vanilla nametag) resizes two base spacers with metadata instead of respawning:
 * slime size 1 to 0 (-0.3825) and baby ocelot to adult (+0.2625), -0.12 in total.
 * <p>
 * Marker armor stands cannot be targeted by the client, ocelots can. Attacks on them are held until the next movement
 * packet (1.8 client sends attack before that tick's rotation) and forwarded to the player only if the look ray hits
 * the player, so hitboxes are not extended.
 * <p>
 * Everything is driven by packets sent to the viewer. Per-viewer state lives in a channel handler and is only
 * touched by that channel's event loop.
 */
public class NMSMultiLineRenderer implements MultiLineRenderer {

    private static final String HANDLER_NAME = "TAB-MultiLine";

    /** Size of fake entity id block of a player, enough for 41 lines with maximum spacings */
    private static final int ID_BLOCK = 512;

    /** Entity ids above this value are fake entities of this renderer, server ids never get this high */
    private static final int ID_FLOOR = 2_000_000_000;

    private static final double PLAYER_MOUNT_OFFSET = 1.35;
    private static final double LABEL_OFFSET = 0.5;

    /** Maximum amount of spacers used for fine-tuning a single height */
    private static final int MAX_FINE_SPACERS = 5;

    /** Height change of the sneak adjusters (slime 1 to 0 and baby ocelot to adult) */
    private static final double SNEAK_ADJUSTERS_HEIGHT = 0.3825 + 0.2625;

    /** ponytail: fixed tolerance for server/client position difference of the target, lag compensation if hits get lost */
    private static final double HIT_TOLERANCE = 0.1;
    private static final double REACH = 6;

    private static final int TYPE_ARMOR_STAND = 30;

    private static final byte FLAG_SNEAKING = 0x02;
    private static final byte FLAG_INVISIBLE = 0x20;

    private static final Field SPAWN_ID = ReflectionUtils.getField(PacketPlayOutNamedEntitySpawn.class, "a");
    private static final Field SPAWN_WATCHER = ReflectionUtils.getField(PacketPlayOutNamedEntitySpawn.class, "i");
    private static final Field DESTROY_IDS = ReflectionUtils.getField(PacketPlayOutEntityDestroy.class, "a");
    private static final Field METADATA_ID = ReflectionUtils.getField(PacketPlayOutEntityMetadata.class, "a");
    private static final Field METADATA_LIST = ReflectionUtils.getField(PacketPlayOutEntityMetadata.class, "b");
    private static final Field STATUS_ID = ReflectionUtils.getField(PacketPlayOutEntityStatus.class, "a");
    private static final Field STATUS_VALUE = ReflectionUtils.getField(PacketPlayOutEntityStatus.class, "b");
    private static final Field ATTACH_LEASH = ReflectionUtils.getField(PacketPlayOutAttachEntity.class, "a");
    private static final Field ATTACH_PASSENGER = ReflectionUtils.getField(PacketPlayOutAttachEntity.class, "b");
    private static final Field ATTACH_VEHICLE = ReflectionUtils.getField(PacketPlayOutAttachEntity.class, "c");
    private static final Field LIVING_ID = ReflectionUtils.getField(PacketPlayOutSpawnEntityLiving.class, "a");
    private static final Field LIVING_TYPE = ReflectionUtils.getField(PacketPlayOutSpawnEntityLiving.class, "b");
    private static final Field LIVING_X = ReflectionUtils.getField(PacketPlayOutSpawnEntityLiving.class, "c");
    private static final Field LIVING_Y = ReflectionUtils.getField(PacketPlayOutSpawnEntityLiving.class, "d");
    private static final Field LIVING_Z = ReflectionUtils.getField(PacketPlayOutSpawnEntityLiving.class, "e");
    private static final Field LIVING_WATCHER = ReflectionUtils.getField(PacketPlayOutSpawnEntityLiving.class, "l");
    private static final Field USE_ENTITY_ID = ReflectionUtils.getField(PacketPlayInUseEntity.class, "a");

    /**
     * Static so ids of a new renderer after reload never collide with lines still being destroyed by the old one.
     * ponytail: ids are never reused, ~280k joins per server start before reaching ID_FLOOR.
     */
    private static final AtomicInteger nextIdBase = new AtomicInteger(Integer.MAX_VALUE);

    /** Spacer combinations by height in millimeters */
    private static final Map<Long, Spacer[]> recipes = new ConcurrentHashMap<>();

    /** Players by their entity id */
    private final Map<Integer, TabPlayer> owners = new ConcurrentHashMap<>();

    /** Players by first id of their fake entity id block */
    private final Map<Integer, TabPlayer> ownersByIdBase = new ConcurrentHashMap<>();

    /** Handlers of viewers currently seeing a player, by player's entity id */
    private final Map<Integer, Set<Handler>> viewersOf = new ConcurrentHashMap<>();

    @Override
    public void inject(@NotNull Object player) {
        Player p = (Player) player;
        Channel channel = channel(p);
        try {
            if (channel.pipeline().get(HANDLER_NAME) != null) channel.pipeline().remove(HANDLER_NAME);
            if (channel.pipeline().get("packet_handler") == null) return; // Disconnected already
            channel.pipeline().addBefore("packet_handler", HANDLER_NAME, new Handler(channel, p.getUniqueId(), ((CraftPlayer) p).getHandle()));
        } catch (NoSuchElementException | IllegalArgumentException ignored) {
            // Disconnected in the meantime
        }
    }

    @Override
    public void onJoin(@NotNull TabPlayer player) {
        register(player);
        // Player may already be seen by others (spawn packets are sent before TAB loads the player)
        refreshOwner(player);
        refreshViewer(player);
    }

    private void register(@NotNull TabPlayer player) {
        MultiLinePlayerData data = player.multiLineData;
        if (data.idBase != 0) return; // Already registered
        data.entityId = handle(player).getId();
        data.idBase = nextIdBase.getAndAdd(-ID_BLOCK);
        owners.put(data.entityId, player);
        ownersByIdBase.put(data.idBase, player);
    }

    @Override
    public void onQuit(@NotNull TabPlayer player) {
        owners.remove(player.multiLineData.entityId, player);
        ownersByIdBase.remove(player.multiLineData.idBase, player);
    }

    @Override
    public void refreshOwner(@NotNull TabPlayer owner) {
        int entityId = owner.multiLineData.entityId;
        Set<Handler> viewers = viewersOf.get(entityId);
        if (viewers == null) return;
        for (Handler handler : viewers) {
            handler.post(() -> handler.sync(entityId));
        }
    }

    @Override
    public void refreshViewer(@NotNull TabPlayer viewer) {
        Handler handler = handler(viewer);
        if (handler != null) handler.post(handler::syncAll);
    }

    @Override
    public void refresh(@NotNull TabPlayer owner, @NotNull TabPlayer viewer) {
        Handler handler = handler(viewer);
        if (handler == null) return;
        int entityId = owner.multiLineData.entityId;
        handler.post(() -> handler.sync(entityId));
    }

    @Override
    public void setSelfView(@NotNull TabPlayer owner, boolean show) {
        Handler handler = handler(owner);
        if (handler == null) return;
        int entityId = owner.multiLineData.entityId;
        // Player entity metadata carries the sneak bit, lines start at the right height right away
        byte flags = handle(owner).getDataWatcher().getByte(0);
        handler.post(() -> {
            if (show) {
                handler.onSpawn(entityId, flags, false);
            } else {
                View view = handler.views.remove(entityId);
                if (view != null) handler.destroyLines(view);
            }
        });
    }

    @Override
    public void load() {
        BukkitPlatform platform = (BukkitPlatform) TAB.getInstance().getPlatform();
        for (Player player : platform.getOnlinePlayers()) {
            inject(player);
        }
        for (TabPlayer player : TAB.getInstance().getOnlinePlayers()) {
            register(player);
        }
        // Players already seeing each other will not receive spawn packets again, read the tracker (main thread only)
        Bukkit.getScheduler().runTask(platform.getPlugin(), () -> {
            for (TabPlayer owner : TAB.getInstance().getOnlinePlayers()) {
                EntityPlayer handle = handle(owner);
                EntityTrackerEntry entry = ((WorldServer) handle.world).tracker.trackedEntities.get(handle.getId());
                if (entry == null) continue;
                byte flags = handle.getDataWatcher().getByte(0);
                for (EntityPlayer viewer : entry.trackedPlayers) {
                    Handler handler = handler(viewer.playerConnection.networkManager.channel);
                    if (handler != null) handler.post(() -> handler.onSpawn(handle.getId(), flags, false));
                }
            }
        });
    }

    @Override
    public void unload() {
        for (Player player : ((BukkitPlatform) TAB.getInstance().getPlatform()).getOnlinePlayers()) {
            Channel channel = channel(player);
            Handler handler = handler(channel);
            if (handler == null) continue;
            channel.eventLoop().execute(() -> {
                handler.destroyAll();
                channel.flush();
                try {
                    channel.pipeline().remove(handler);
                } catch (NoSuchElementException ignored) {
                    // Disconnected
                }
            });
        }
        owners.clear();
        ownersByIdBase.clear();
        viewersOf.clear();
    }

    @NotNull
    private static EntityPlayer handle(@NotNull TabPlayer player) {
        return ((CraftPlayer) player.getPlayer()).getHandle();
    }

    @NotNull
    private static Channel channel(@NotNull Player player) {
        return ((CraftPlayer) player).getHandle().playerConnection.networkManager.channel;
    }

    @Nullable
    private static Handler handler(@NotNull TabPlayer player) {
        return handler(channel((Player) player.getPlayer()));
    }

    @Nullable
    private static Handler handler(@NotNull Channel channel) {
        return (Handler) channel.pipeline().get(HANDLER_NAME);
    }

    @Nullable
    private TabPlayer ownerOfFakeEntity(int entityId) {
        int base = Integer.MAX_VALUE - ((Integer.MAX_VALUE - entityId) / ID_BLOCK) * ID_BLOCK;
        return ownersByIdBase.get(base);
    }

    /**
     * Invisible entity used to lift riders by a fixed height.
     */
    private enum Spacer {

        /** Baby ocelot, 0.35 tall */
        OCELOT(98, 0.2625),

        /** Silverfish, 0.2 y offset + 0.3 tall */
        SILVERFISH(60, 0.425),

        /** Slime of size 1 */
        SLIME(55, 0.3825),

        /** Slime of size -1, negative height pulls riders down */
        SLIME_DOWN(55, -0.3825);

        private final int type;
        private final double height;

        Spacer(int type, double height) {
            this.type = type;
            this.height = height;
        }
    }

    /**
     * Returns combination of spacers lifting riders by given height as closely as possible.
     *
     * @param   height
     *          Height in blocks, may be negative
     * @return  Spacers to use
     */
    @NotNull
    private static Spacer[] recipe(double height) {
        return recipes.computeIfAbsent(Math.round(height * 1000), key -> {
            // Cover large heights with silverfish, then find the best small combination for the rest
            int coarse = (int) Math.max(0, Math.floor((height - 1) / Spacer.SILVERFISH.height));
            double rest = height - coarse * Spacer.SILVERFISH.height;
            int[] best = null;
            double bestError = Double.MAX_VALUE;
            // ponytail: brute force over ~250 combinations, result is cached per height
            for (int o = 0; o <= MAX_FINE_SPACERS; o++) {
                for (int s = 0; o + s <= MAX_FINE_SPACERS; s++) {
                    for (int up = 0; o + s + up <= MAX_FINE_SPACERS; up++) {
                        for (int down = 0; o + s + up + down <= MAX_FINE_SPACERS; down++) {
                            if (up > 0 && down > 0) continue; // Cancel each other out
                            double error = Math.abs(o * Spacer.OCELOT.height + s * Spacer.SILVERFISH.height
                                    + up * Spacer.SLIME.height + down * Spacer.SLIME_DOWN.height - rest);
                            int count = o + s + up + down;
                            if (best == null || error < bestError - 1e-9 || (Math.abs(error - bestError) < 1e-9 && count < best[0] + best[1] + best[2] + best[3])) {
                                best = new int[]{o, s + coarse, up, down};
                                bestError = error;
                            }
                        }
                    }
                }
            }
            List<Spacer> spacers = new ArrayList<>();
            Spacer[] types = Spacer.values();
            for (int i = 0; i < types.length; i++) {
                for (int j = 0; j < best[i]; j++) spacers.add(types[i]);
            }
            return spacers.toArray(new Spacer[0]);
        });
    }

    /**
     * State of a player seen by the viewer.
     */
    private static class View {

        private final int entityId;

        /** Entity flags (metadata index 0) as last sent to the viewer */
        private byte flags;

        private boolean dead;

        /** Entity id of a passenger not managed by this renderer, 0 if none */
        private int foreignPassenger;

        /** Currently spawned fake entities, all ids from idBase downwards */
        private int entityCount;
        private int idBase;
        @Nullable private MultiLinePlayerData.Layout layout;
        private boolean sneaking;
        private int[] lineIds;
        private String[] texts;

        /** Sneak adjuster ids, 0 if not used */
        private int adjusterSlime;
        private int adjusterOcelot;

        private View(int entityId) {
            this.entityId = entityId;
        }
    }

    /**
     * Packet handler of a single viewer.
     */
    private final class Handler extends ChannelDuplexHandler {

        @NotNull private final Channel channel;
        @NotNull private final UUID viewerId;
        @NotNull private final EntityPlayer viewerHandle;
        @Nullable private ChannelHandlerContext context;

        /** Players seen by this viewer by entity id */
        private final Map<Integer, View> views = new HashMap<>();

        /** Last position and rotation sent by the client */
        private double x;
        private double y;
        private double z;
        private float yaw;
        private float pitch;

        /** Incoming packets held until next movement packet, starting with an attack on a fake entity */
        @Nullable private List<Object> held;

        private Handler(@NotNull Channel channel, @NotNull UUID viewerId, @NotNull EntityPlayer viewerHandle) {
            this.channel = channel;
            this.viewerId = viewerId;
            this.viewerHandle = viewerHandle;
            x = viewerHandle.locX;
            y = viewerHandle.locY;
            z = viewerHandle.locZ;
            yaw = viewerHandle.yaw;
            pitch = viewerHandle.pitch;
        }

        @Override
        public void handlerAdded(@NotNull ChannelHandlerContext ctx) {
            context = ctx;
        }

        @Override
        public void handlerRemoved(@NotNull ChannelHandlerContext ctx) {
            releaseHeld(ctx);
            for (Integer entityId : views.keySet()) {
                Set<Handler> viewers = viewersOf.get(entityId);
                if (viewers != null) viewers.remove(this);
            }
            views.clear();
        }

        @Override
        public void channelInactive(@NotNull ChannelHandlerContext ctx) throws Exception {
            handlerRemoved(ctx);
            super.channelInactive(ctx);
        }

        /**
         * Runs task on this viewer's event loop and flushes written packets.
         *
         * @param   task
         *          Task to run
         */
        private void post(@NotNull Runnable task) {
            channel.eventLoop().execute(() -> {
                if (context == null || !channel.isActive()) return;
                try {
                    task.run();
                } catch (Throwable t) {
                    TAB.getInstance().getErrorManager().printError("Failed to update multi-line nametags for " + viewerHandle.getName(), t);
                }
                context.flush();
            });
        }

        // ------------------
        // Outgoing packets
        // ------------------

        @Override
        public void write(@NotNull ChannelHandlerContext ctx, @Nullable Object packet, @NotNull ChannelPromise promise) throws Exception {
            Object out = packet;
            try {
                if (packet instanceof PacketPlayOutEntityDestroy) {
                    out = onDestroy((PacketPlayOutEntityDestroy) packet);
                } else if (packet instanceof PacketPlayOutRespawn) {
                    // Client removed all entities
                    for (View view : views.values()) {
                        Set<Handler> viewers = viewersOf.get(view.entityId);
                        if (viewers != null) viewers.remove(this);
                    }
                    views.clear();
                }
            } catch (Throwable t) {
                TAB.getInstance().getErrorManager().printError("Failed to process packet for multi-line nametags", t);
            }
            super.write(ctx, out, promise);
            try {
                if (packet instanceof PacketPlayOutNamedEntitySpawn) {
                    onSpawn(SPAWN_ID.getInt(packet), ((DataWatcher) SPAWN_WATCHER.get(packet)).getByte(0), true);
                } else if (packet instanceof PacketPlayOutEntityMetadata) {
                    onMetadata(packet);
                } else if (packet instanceof PacketPlayOutEntityStatus) {
                    onStatus(packet);
                } else if (packet instanceof PacketPlayOutAttachEntity) {
                    onAttach(packet);
                }
            } catch (Throwable t) {
                TAB.getInstance().getErrorManager().printError("Failed to process packet for multi-line nametags", t);
            }
        }

        private void onSpawn(int entityId, byte flags, boolean fromPacket) {
            View view = views.get(entityId);
            if (view == null) {
                view = new View(entityId);
                views.put(entityId, view);
                viewersOf.computeIfAbsent(entityId, k -> ConcurrentHashMap.newKeySet()).add(this);
            } else if (fromPacket) {
                destroyLines(view); // Client replaced the entity, old lines lost their vehicle
            }
            view.flags = flags;
            view.dead = false;
            view.foreignPassenger = 0;
            sync(view);
        }

        @SneakyThrows
        @NotNull
        private Object onDestroy(@NotNull PacketPlayOutEntityDestroy packet) {
            int[] ids = (int[]) DESTROY_IDS.get(packet);
            List<View> withLines = null;
            int extraCount = 0;
            for (int id : ids) {
                View view = views.remove(id);
                if (view == null) continue;
                Set<Handler> viewers = viewersOf.get(id);
                if (viewers != null) viewers.remove(this);
                if (view.entityCount == 0) continue;
                if (withLines == null) withLines = new ArrayList<>();
                withLines.add(view);
                extraCount += view.entityCount;
            }
            if (withLines == null) return packet;
            // Packet instance is shared between viewers, send a new one destroying lines in the same packet as the player
            int[] merged = Arrays.copyOf(ids, ids.length + extraCount);
            int index = ids.length;
            for (View view : withLines) {
                for (int i = 0; i < view.entityCount; i++) {
                    merged[index++] = view.idBase - i;
                }
            }
            return new PacketPlayOutEntityDestroy(merged);
        }

        @SneakyThrows
        @SuppressWarnings("unchecked")
        private void onMetadata(@NotNull Object packet) {
            View view = views.get(METADATA_ID.getInt(packet));
            if (view == null) return;
            List<DataWatcher.WatchableObject> list = (List<DataWatcher.WatchableObject>) METADATA_LIST.get(packet);
            if (list == null) return;
            for (DataWatcher.WatchableObject object : list) {
                if (object.a() != 0) continue;
                byte flags = (Byte) object.b();
                int changed = flags ^ view.flags;
                view.flags = flags;
                if ((changed & (FLAG_INVISIBLE | FLAG_SNEAKING)) != 0) sync(view);
                return;
            }
        }

        @SneakyThrows
        private void onStatus(@NotNull Object packet) {
            if (STATUS_VALUE.getByte(packet) != 3) return; // Death animation
            View view = views.get(STATUS_ID.getInt(packet));
            if (view == null) return;
            view.dead = true;
            sync(view);
        }

        @SneakyThrows
        private void onAttach(@NotNull Object packet) {
            if (ATTACH_LEASH.getInt(packet) != 0) return;
            int passenger = ATTACH_PASSENGER.getInt(packet);
            int vehicle = ATTACH_VEHICLE.getInt(packet);
            if (vehicle != -1) {
                View view = views.get(vehicle);
                if (view == null || passenger > ID_FLOOR) return;
                // 1.8 allows one passenger, lines would be dismounted by the client
                view.foreignPassenger = passenger;
                sync(view);
            } else {
                for (View view : views.values()) {
                    if (view.foreignPassenger == passenger) {
                        view.foreignPassenger = 0;
                        sync(view);
                    }
                }
            }
        }

        // ------------------
        // Lines
        // ------------------

        private void syncAll() {
            for (View view : views.values()) {
                sync(view);
            }
        }

        private void sync(int entityId) {
            View view = views.get(entityId);
            if (view != null) sync(view);
        }

        private void sync(@NotNull View view) {
            TabPlayer owner = owners.get(view.entityId);
            MultiLinePlayerData.Layout layout = desiredLayout(view, owner);
            if (owner == null || layout == null || layout.lines.length == 0) {
                destroyLines(view);
                return;
            }
            if (view.entityCount == 0 || view.layout == null || !view.layout.hasSameStructure(layout) || view.idBase != owner.multiLineData.idBase) {
                destroyLines(view);
                spawnLines(view, owner, layout);
                return;
            }
            view.layout = layout;
            String[] lines = layout.lines;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].equals(view.texts[i])) continue;
                view.texts[i] = lines[i];
                DataWatcher watcher = new DataWatcher(null);
                watcher.a(2, lines[i]);
                context.write(new PacketPlayOutEntityMetadata(view.lineIds[i], watcher, true));
            }
            boolean sneaking = (view.flags & FLAG_SNEAKING) != 0;
            if (sneaking != view.sneaking) {
                view.sneaking = sneaking;
                for (int id : view.lineIds) {
                    DataWatcher watcher = new DataWatcher(null);
                    watcher.a(0, standFlags(sneaking));
                    context.write(new PacketPlayOutEntityMetadata(id, watcher, true));
                }
                if (view.adjusterSlime != 0) {
                    DataWatcher slime = new DataWatcher(null);
                    slime.a(16, (byte) (sneaking ? 0 : 1));
                    context.write(new PacketPlayOutEntityMetadata(view.adjusterSlime, slime, true));
                    DataWatcher ocelot = new DataWatcher(null);
                    ocelot.a(12, (byte) (sneaking ? 0 : -1));
                    context.write(new PacketPlayOutEntityMetadata(view.adjusterOcelot, ocelot, true));
                }
            }
        }

        @Nullable
        private MultiLinePlayerData.Layout desiredLayout(@NotNull View view, @Nullable TabPlayer owner) {
            if (owner == null) return null;
            if (view.dead || view.foreignPassenger != 0 || (view.flags & FLAG_INVISIBLE) != 0) return null;
            TabPlayer viewer = TAB.getInstance().getPlayer(viewerId);
            if (viewer == null) return null; // Not loaded yet, refreshed on join
            if (owner.multiLineData.spectator || viewer.multiLineData.spectator) return null; // Spectators see invisible entities
            if (!owner.teamData.isNameTagVisibleTo(viewer)) return null;
            return owner.multiLineData.layout;
        }

        private void spawnLines(@NotNull View view, @NotNull TabPlayer owner, @NotNull MultiLinePlayerData.Layout layout) {
            MultiLinePlayerData data = owner.multiLineData;
            EntityPlayer ownerHandle = handle(owner);
            double lx = ownerHandle.locX;
            double lz = ownerHandle.locZ;
            double[] ly = {ownerHandle.locY + PLAYER_MOUNT_OFFSET};
            int[] index = {0};
            int[] vehicle = {view.entityId};
            boolean sneaking = (view.flags & FLAG_SNEAKING) != 0;
            String[] lines = layout.lines;
            double[] heights = layout.heights;

            // Base below the lowest line
            double base = heights[heights.length - 1] - PLAYER_MOUNT_OFFSET - LABEL_OFFSET;
            view.adjusterSlime = 0;
            view.adjusterOcelot = 0;
            if (layout.lowerWhenSneaking) {
                base -= SNEAK_ADJUSTERS_HEIGHT;
                view.adjusterSlime = spawnSpacer(data.idBase, index, vehicle, ly, lx, lz, 55, sneaking ? 0 : 1, false, Spacer.SLIME.height);
                view.adjusterOcelot = spawnSpacer(data.idBase, index, vehicle, ly, lx, lz, 98, 0, !sneaking, Spacer.OCELOT.height);
            }
            spawnSpacers(recipe(base), data.idBase, index, vehicle, ly, lx, lz);

            int[] lineIds = new int[lines.length];
            for (int line = lines.length - 1; line >= 0; line--) {
                int id = data.idBase - index[0]++;
                spawn(id, vehicle[0], TYPE_ARMOR_STAND, lx, ly[0], lz, standWatcher(lines[line], sneaking));
                vehicle[0] = id;
                lineIds[line] = id;
                if (line > 0) spawnSpacers(recipe(heights[line - 1]), data.idBase, index, vehicle, ly, lx, lz);
            }
            view.entityCount = index[0];
            view.idBase = data.idBase;
            view.layout = layout;
            view.sneaking = sneaking;
            view.lineIds = lineIds;
            view.texts = lines.clone();
        }

        private void spawnSpacers(@NotNull Spacer[] spacers, int idBase, int[] index, int[] vehicle, double[] y, double x, double z) {
            for (Spacer spacer : spacers) {
                int size = spacer == Spacer.SLIME ? 1 : spacer == Spacer.SLIME_DOWN ? -1 : 0;
                spawnSpacer(idBase, index, vehicle, y, x, z, spacer.type, size, spacer == Spacer.OCELOT, spacer.height);
            }
        }

        private int spawnSpacer(int idBase, int[] index, int[] vehicle, double[] y, double x, double z, int type, int slimeSize,
                                boolean baby, double height) {
            int id = idBase - index[0]++;
            DataWatcher watcher = new DataWatcher(null);
            watcher.a(0, FLAG_INVISIBLE);
            watcher.a(4, (byte) 1); // Silent
            watcher.a(15, (byte) 1); // No AI
            if (type == 98) watcher.a(12, (byte) (baby ? -1 : 0)); // Age
            if (type == 55) watcher.a(16, (byte) slimeSize); // Slime size
            spawn(id, vehicle[0], type, x, y[0], z, watcher);
            vehicle[0] = id;
            y[0] += height;
            return id;
        }

        @SneakyThrows
        private void spawn(int id, int vehicle, int type, double x, double y, double z, @NotNull DataWatcher watcher) {
            PacketPlayOutSpawnEntityLiving spawn = new PacketPlayOutSpawnEntityLiving();
            LIVING_ID.setInt(spawn, id);
            LIVING_TYPE.setInt(spawn, type);
            LIVING_X.setInt(spawn, MathHelper.floor(x * 32));
            LIVING_Y.setInt(spawn, MathHelper.floor(y * 32));
            LIVING_Z.setInt(spawn, MathHelper.floor(z * 32));
            LIVING_WATCHER.set(spawn, watcher);
            context.write(spawn);
            PacketPlayOutAttachEntity attach = new PacketPlayOutAttachEntity();
            ATTACH_LEASH.setInt(attach, 0);
            ATTACH_PASSENGER.setInt(attach, id);
            ATTACH_VEHICLE.setInt(attach, vehicle);
            context.write(attach);
        }

        @NotNull
        private DataWatcher standWatcher(@NotNull String text, boolean sneaking) {
            DataWatcher watcher = new DataWatcher(null);
            watcher.a(0, standFlags(sneaking));
            watcher.a(2, text);
            watcher.a(3, (byte) 1); // Custom name visible
            watcher.a(4, (byte) 1); // Silent
            watcher.a(10, (byte) 0x10); // Marker, zero size and cannot be targeted
            return watcher;
        }

        private byte standFlags(boolean sneaking) {
            // Sneaking stand label is not visible through walls, same as vanilla
            return (byte) (FLAG_INVISIBLE | (sneaking ? FLAG_SNEAKING : 0));
        }

        private void destroyLines(@NotNull View view) {
            if (view.entityCount == 0) return;
            int[] ids = new int[view.entityCount];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = view.idBase - i;
            }
            context.write(new PacketPlayOutEntityDestroy(ids));
            view.entityCount = 0;
            view.layout = null;
            view.lineIds = null;
            view.texts = null;
        }

        private void destroyAll() {
            if (context == null) return;
            for (View view : views.values()) {
                destroyLines(view);
            }
        }

        // ------------------
        // Incoming packets
        // ------------------

        @Override
        public void channelRead(@NotNull ChannelHandlerContext ctx, @NotNull Object packet) throws Exception {
            try {
                if (packet instanceof PacketPlayInFlying) {
                    PacketPlayInFlying flying = (PacketPlayInFlying) packet;
                    if (flying.h()) {
                        yaw = flying.d();
                        pitch = flying.e();
                    }
                    // Attack was sent with this tick's rotation, but position of previous tick
                    releaseHeld(ctx);
                    if (flying.g()) {
                        x = flying.a();
                        y = flying.b();
                        z = flying.c();
                    }
                } else if (held != null) {
                    held.add(packet);
                    if (held.size() > 32) releaseHeld(ctx); // Client not sending movement, do not hold forever
                    return;
                } else if (packet instanceof PacketPlayInUseEntity && USE_ENTITY_ID.getInt(packet) > ID_FLOOR) {
                    held = new ArrayList<>();
                    held.add(packet);
                    return;
                }
            } catch (Throwable t) {
                TAB.getInstance().getErrorManager().printError("Failed to process incoming packet for multi-line nametags", t);
            }
            super.channelRead(ctx, packet);
        }

        private void releaseHeld(@NotNull ChannelHandlerContext ctx) {
            List<Object> packets = held;
            if (packets == null) return;
            held = null;
            boolean redirected = false;
            try {
                redirected = redirect(packets.get(0));
            } catch (Throwable t) {
                TAB.getInstance().getErrorManager().printError("Failed to redirect attack on multi-line nametag", t);
            }
            for (int i = redirected ? 0 : 1; i < packets.size(); i++) {
                ctx.fireChannelRead(packets.get(i));
            }
        }

        /**
         * Changes target of interaction with a line entity to its owner if player's look ray hits the owner.
         *
         * @param   packet
         *          Use entity packet targeting a fake entity
         * @return  {@code true} if packet should be forwarded, {@code false} if dropped
         */
        @SneakyThrows
        private boolean redirect(@NotNull Object packet) {
            TabPlayer owner = ownerOfFakeEntity(USE_ENTITY_ID.getInt(packet));
            if (owner == null) return false;
            EntityPlayer ownerHandle = handle(owner);
            if (ownerHandle.getId() == viewerHandle.getId()) return false; // Spigot kicks for interacting with self
            Vec3D eye = new Vec3D(x, y + (viewerHandle.isSneaking() ? 1.54 : 1.62), z);
            double yawRadians = -yaw * 0.017453292F - Math.PI;
            double pitchRadians = -pitch * 0.017453292F;
            double horizontal = -Math.cos(pitchRadians);
            Vec3D end = eye.add(Math.sin(yawRadians) * horizontal * REACH, Math.sin(pitchRadians) * REACH, Math.cos(yawRadians) * horizontal * REACH);
            AxisAlignedBB box = ownerHandle.getBoundingBox().grow(0.1 + HIT_TOLERANCE, 0.1 + HIT_TOLERANCE, 0.1 + HIT_TOLERANCE);
            if (!box.a(eye) && box.a(eye, end) == null) return false;
            USE_ENTITY_ID.setInt(packet, ownerHandle.getId());
            return true;
        }
    }
}

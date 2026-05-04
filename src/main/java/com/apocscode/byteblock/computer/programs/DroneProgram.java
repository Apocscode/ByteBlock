package com.apocscode.byteblock.computer.programs;

import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.computer.OSEvent;
import com.apocscode.byteblock.computer.OSProgram;
import com.apocscode.byteblock.computer.PixelBuffer;
import com.apocscode.byteblock.computer.TerminalBuffer;
import com.apocscode.byteblock.entity.DroneEntity;
import com.apocscode.byteblock.item.GpsToolItem;
import com.apocscode.byteblock.network.BluetoothNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * DroneProgram — top-down minimap app for commanding drone fleets via Bluetooth.
 *
 * Renders a 128×128 heightmap-sampled minimap centered on the host computer,
 * overlays every drone registered on the OS's current Bluetooth channel, and
 * translates map clicks into "drone:waypoint:x:y:z" broadcasts. If a drone is
 * currently selected, commands target that drone only; otherwise they broadcast
 * to all drones on the channel.
 *
 * Controls:
 *   Left-click map       → send selected drone (or all) to that block
 *   Right-click map      → return all drones home
 *   Click a drone dot    → select that drone (cycles through overlapping ones)
 *   Left-click toolbar   → tool buttons (Home / Clear / Hover / Rescan)
 */
public class DroneProgram extends OSProgram {

    // Layout (640×400 pixel canvas).
    private static final int MAP_X = 8;
    private static final int MAP_Y = 24;
    private static final int MAP_SIZE = 320; // 320x320 map area
    private static final int TILE_PX = 4;    // 1 map pixel = 4 screen pixels
    private static final int MAP_TILES = MAP_SIZE / TILE_PX; // 80x80 map samples

    // Sidebar (right side).
    private static final int SIDEBAR_X = MAP_X + MAP_SIZE + 8;
    private static final int SIDEBAR_W = 640 - SIDEBAR_X - 8;

    // Toolbar y.
    private static final int TOOLBAR_Y = MAP_Y + MAP_SIZE + 8;

    // Colors (ARGB) — brightened palette.
    private static final int COL_BG = 0xFF1c2430;
    private static final int COL_PANEL = 0xFF2c3a4a;
    private static final int COL_BORDER = 0xFF5a7088;
    private static final int COL_TEXT = 0xFFf4f8fc;
    private static final int COL_DIM = 0xFFa8b8c8;
    private static final int COL_DRONE_IDLE = 0xFF30ff60;   // bright green
    private static final int COL_DRONE_MOVING = 0xFFffd040;
    private static final int COL_DRONE_LOW = 0xFFff5040;
    private static final int COL_DRONE_DEF = 0xFF60b8ff;
    private static final int COL_DRONE_SEL = 0xFFffffff;
    private static final int COL_ME = 0xFF60ffff;
    private static final int COL_BTN = 0xFF44607c;
    private static final int COL_BTN_HOT = 0xFF6088b4;

    // Terrain cache: sampled block colors, regenerated lazily.
    private int[][] mapTiles = new int[MAP_TILES][MAP_TILES];
    private BlockPos lastSampleOrigin = null;
    private int tileRefreshCursor = 0;
    private static final int TILES_PER_TICK = 64;

    // Drone tracking.
    private final List<DroneBlip> drones = new ArrayList<>();
    private int hoverToolbarIdx = -1;

    private int mapBlockScale = 2; // 1 map-tile = `mapBlockScale` world blocks
    // Free-pan offset in blocks from computer position (0,0 = centered on computer).
    private int mapPanX = 0, mapPanZ = 0;
    // Drag-to-pan state.
    private boolean isDragging   = false;
    private int dragAnchorPx     = 0, dragAnchorPy = 0;
    private int panAtDragX       = 0, panAtDragZ   = 0;
    private boolean dragHasMoved = false;
    private int clickButton      = 0;
    // View mode: false=surface heightmap, true=Y-slice at computer elevation.
    private boolean caveMode = false;
    // Waypoint pins shown on the map (cleared by Clear button or right-click).
    private final List<BlockPos> mapWaypoints = new ArrayList<>();
    // Multi-select: any number of drones can be selected simultaneously.
    // Uses entity integer ID (entity.getId()) — always non-null on both sides.
    // Broadcasts go to all selected drones; if none selected, to all visible drones.
    private final Set<Integer> selectedIds = new LinkedHashSet<>();
    // GPS markers cached from drone entity scans AND BT ch9100 broadcasts.
    // Keyed by drone UUID (scan) or "bt:<a>:<b>" (broadcast).
    // Persists when drones fly out of entity-scan range — cleared by Clear button.
    private final java.util.Map<String, DroneGps> persistentGps = new java.util.LinkedHashMap<>();
    // Stable entityId → droneUUID mapping. Updated every scan; never shrinks so
    // commands can still reach drones that have flown out of entity-scan range.
    private final java.util.Map<Integer, UUID> entityToUUID = new java.util.LinkedHashMap<>();
    // Drone trail: ring of last MAX_TRAIL world positions per drone UUID.
    private static final int MAX_TRAIL = 24;
    private final java.util.Map<UUID, java.util.ArrayDeque<BlockPos>> droneTrails = new java.util.LinkedHashMap<>();
    // Named view bookmarks (pan offsets relative to computer; max 6).
    private static final int MAX_BOOKMARKS = 6;
    private final java.util.List<int[]>  viewBookmarks = new ArrayList<>();
    private final java.util.List<String> bookmarkNames = new ArrayList<>();

    public DroneProgram() {
        super("Drones");
    }

    @Override
    public void init(JavaOS os) {
        this.os = os;
    }

    @Override
    public boolean tick() {
        // Refresh terrain a few tiles per tick to amortise cost.
        sampleTerrainIncremental();
        // Rescan drone registry every tick (cheap — in-memory list).
        refreshDrones();
        return running;
    }

    @Override
    public void handleEvent(OSEvent event) {
        switch (event.getType()) {
            case MOUSE_CLICK_PX -> handlePixelClick(event.getInt(0), event.getInt(1), event.getInt(2));
            case MOUSE_DRAG_PX  -> handleDrag(event.getInt(1), event.getInt(2));
            case MOUSE_UP       -> handleMouseUp(event.getInt(0));
            case MOUSE_SCROLL -> handleScroll(event.getInt(0));
            case KEY -> {
                int key = event.getInt(0);
                if (key == 256) { running = false; return; } // ESC
                if (key == 84 || key == 116) { // T — toggle surface/cave
                    caveMode = !caveMode;
                    lastSampleOrigin = null;
                    tileRefreshCursor = 0;
                }
            }
            case BLUETOOTH -> {
                // Cache GPS tool broadcasts from channel 9100 so chest markers
                // appear even when no drones are currently in entity-scan range.
                if (event.getInt(0) == 9100) {
                    String payload = event.getString(1);
                    if (payload.startsWith("gps_tool:")) {
                        DroneGps g = parseGpsJson(payload.substring("gps_tool:".length()));
                        if (g != null) persistentGps.put("bt:" + g.a + ":" + g.b, g);
                    }
                }
            }
            default -> {}
        }
    }

    /** Scroll up = zoom in (smaller scale), scroll down = zoom out (max 64 = ~320 chunks wide). */
    private void handleScroll(int dir) {
        int old = mapBlockScale;
        if (dir > 0) {
            if (mapBlockScale > 1) mapBlockScale /= 2;
        } else if (dir < 0) {
            if (mapBlockScale < 64) mapBlockScale *= 2;
        }
        if (mapBlockScale != old) {
            lastSampleOrigin = null;
            tileRefreshCursor = 0;
        }
    }

    // ── Input ─────────────────────────────────────────────────────────

    private void handlePixelClick(int button, int px, int py) {
        // Toolbar buttons first.
        int btnW = 80, btnH = 18, btnGap = 6;
        String[] labels = { "Set Home", "Go Home", "Clear", "Hover", "Rescan", "Pin" };
        for (int i = 0; i < labels.length; i++) {
            int bx = MAP_X + i * (btnW + btnGap);
            if (px >= bx && px < bx + btnW && py >= TOOLBAR_Y && py < TOOLBAR_Y + btnH) {
                handleToolbar(labels[i]);
                return;
            }
        }

        // Map area.
        if (px >= MAP_X && px < MAP_X + MAP_SIZE && py >= MAP_Y && py < MAP_Y + MAP_SIZE) {
            if (button == 1) {
                // Right-click: go home immediately.
                broadcast("drone:home");
                mapWaypoints.clear();
            } else {
                // Left-click: begin drag-to-pan. Waypoint fired on release if no pan occurred.
                isDragging   = true;
                dragHasMoved = false;
                clickButton  = button;
                dragAnchorPx = px;
                dragAnchorPy = py;
                panAtDragX   = mapPanX;
                panAtDragZ   = mapPanZ;
            }
            return;
        }

        // Sidebar drone-list row clicks — toggle individual selection.
        int rowY = MAP_Y + 18;
        boolean clickedRow = false;
        for (DroneBlip d : drones) {
            if (px >= SIDEBAR_X && px < SIDEBAR_X + SIDEBAR_W
                    && py >= rowY && py < rowY + 36) {
                toggleSelect(d.entityId);
                clickedRow = true;
                return;
            }
            rowY += 36;
        }
        // Click empty sidebar area — deselect all.
        if (!clickedRow && px >= SIDEBAR_X && px < SIDEBAR_X + SIDEBAR_W) {
            selectedIds.clear();
        }
    }

    private void handleToolbar(String label) {
        switch (label) {
            case "Set Home" -> broadcast("drone:setHome");
            case "Go Home" -> broadcast("drone:home");
            case "Clear" -> {
                broadcast("drone:clear");
                mapWaypoints.clear();
                persistentGps.clear();
                droneTrails.clear();
                viewBookmarks.clear();
                bookmarkNames.clear();
                entityToUUID.clear();
                selectedIds.clear();
            }
            case "Hover" -> broadcast("drone:hover:true");
            case "Rescan" -> {
                lastSampleOrigin = null;
                tileRefreshCursor = 0;
                mapPanX = 0; mapPanZ = 0;
            }
            case "Pin" -> {
                if (viewBookmarks.size() >= MAX_BOOKMARKS) {
                    viewBookmarks.remove(0);
                    bookmarkNames.remove(0);
                }
                viewBookmarks.add(new int[]{mapPanX, mapPanZ});
                bookmarkNames.add("V" + (viewBookmarks.size()));
            }
        }
    }

    private void handleDrag(int px, int py) {
        if (!isDragging) return;
        int dpx = px - dragAnchorPx;
        int dpy = py - dragAnchorPy;
        if (Math.abs(dpx) > 3 || Math.abs(dpy) > 3) dragHasMoved = true;
        if (dragHasMoved) {
            // Invert so map feels natural (drag right = see further right).
            mapPanX = panAtDragX - dpx * mapBlockScale;
            mapPanZ = panAtDragZ - dpy * mapBlockScale;
            lastSampleOrigin = null;
            tileRefreshCursor = 0;
        }
    }

    private void handleMouseUp(int button) {
        if (!isDragging) return;
        isDragging = false;
        if (!dragHasMoved && button == clickButton
                && dragAnchorPx >= MAP_X && dragAnchorPx < MAP_X + MAP_SIZE
                && dragAnchorPy >= MAP_Y && dragAnchorPy < MAP_Y + MAP_SIZE) {
            // 1. Drone blip hit-test — clicking a drone selects it instead of placing a waypoint.
            for (DroneBlip d : drones) {
                int[] t = worldToTile(d.pos);
                if (t[0] < 0 || t[0] >= MAP_TILES || t[1] < 0 || t[1] >= MAP_TILES) continue;
                int dpx = MAP_X + t[0] * TILE_PX + TILE_PX / 2;
                int dpy = MAP_Y + t[1] * TILE_PX + TILE_PX / 2;
                if (Math.abs(dragAnchorPx - dpx) <= 7 && Math.abs(dragAnchorPy - dpy) <= 7) {
                    toggleSelect(d.entityId);
                    return;
                }
            }
            // 2. Bookmark hit-test — clicking a bookmark marker jumps the view.
            BlockPos origin = os.getBlockPos();
            if (origin != null) {
                for (int bi = 0; bi < viewBookmarks.size(); bi++) {
                    int[] bm = viewBookmarks.get(bi);
                    BlockPos bmWorld = new BlockPos(origin.getX() + bm[0], 0, origin.getZ() + bm[1]);
                    int[] t = worldToTile(bmWorld);
                    if (t[0] >= 0 && t[0] < MAP_TILES && t[1] >= 0 && t[1] < MAP_TILES) {
                        int bpx = MAP_X + t[0] * TILE_PX + TILE_PX / 2;
                        int bpz = MAP_Y + t[1] * TILE_PX + TILE_PX / 2;
                        if (Math.abs(dragAnchorPx - bpx) <= 8 && Math.abs(dragAnchorPy - bpz) <= 8) {
                            mapPanX = bm[0]; mapPanZ = bm[1];
                            lastSampleOrigin = null; tileRefreshCursor = 0;
                            return;
                        }
                    }
                }
            }
            // 3. Fall through — send waypoint to clicked position.
            int tileX = (dragAnchorPx - MAP_X) / TILE_PX;
            int tileZ = (dragAnchorPy - MAP_Y) / TILE_PX;
            BlockPos world = tileToWorld(tileX, tileZ);
            Level lvl = os.getLevel();
            int surfaceY;
            try {
                surfaceY = (lvl != null)
                        ? lvl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, world.getX(), world.getZ())
                        : world.getY();
            } catch (Exception ex) { surfaceY = world.getY(); }
            broadcast("drone:waypoint:" + world.getX() + ":" + (surfaceY + 2) + ":" + world.getZ());
            if (mapWaypoints.size() >= 20) mapWaypoints.remove(0);
            mapWaypoints.add(new BlockPos(world.getX(), surfaceY, world.getZ()));
        }
    }

    private void toggleSelect(int entityId) {
        if (!selectedIds.add(entityId)) selectedIds.remove(entityId);
    }

    private void broadcast(String msg) {
        Level lvl = os.getLevel();
        BlockPos pos = os.getBlockPos();
        if (lvl == null || pos == null) return;
        if (!selectedIds.isEmpty()) {
            // Use the cached UUID map so commands reach drones even when they have
            // flown out of entity-scan range since selection.
            for (int entityId : new ArrayList<>(selectedIds)) {
                UUID droneUUID = entityToUUID.get(entityId);
                if (droneUUID != null) {
                    BluetoothNetwork.send(lvl, pos, droneUUID, msg);
                }
            }
        } else {
            // No selection — send to every drone seen in the last scan.
            for (UUID droneUUID : new ArrayList<>(entityToUUID.values())) {
                BluetoothNetwork.send(lvl, pos, droneUUID, msg);
            }
        }
    }

    // ── Data ──────────────────────────────────────────────────────────

    private void refreshDrones() {
        drones.clear();
        Level lvl = os.getLevel();
        BlockPos origin = os.getBlockPos();
        if (lvl == null || origin == null) return;

        // Scan for all drone entities in range — display all regardless of channel.
        // Channel filtering only applies to commands (broadcasts).
        double scanR = MAP_TILES * mapBlockScale * 1.5;
        net.minecraft.world.phys.AABB aabb =
                new net.minecraft.world.phys.AABB(origin).inflate(scanR, 64, scanR);
        for (DroneEntity d : lvl.getEntitiesOfClass(DroneEntity.class, aabb)) {
            DroneBlip b = new DroneBlip();
            b.id = d.getDroneId();
            b.entityId = d.getId();
            b.pos = d.blockPosition();
            b.fuel = d.getFuel();
            b.defender = d.isDefender();
            b.group = d.getSwarmGroup();
            b.variant = d.getVariant().name();
            b.hasTarget = !d.getSwarmGroup().isEmpty() || d.isDefender();
            b.homePos = d.getSyncedHomePos();
            b.channel = d.getSyncedBluetoothChannel();
            b.name = d.hasCustomName() ? d.getCustomName().getString() : "";
            // Read GPS tool positions stored on the drone.
            net.minecraft.world.item.ItemStack gpsTool = d.getGpsToolStack();
            if (!gpsTool.isEmpty()) {
                b.gpsA          = GpsToolItem.getA(gpsTool);
                b.gpsB          = GpsToolItem.getB(gpsTool);
                b.gpsMode       = GpsToolItem.getMode(gpsTool).name();
                b.gpsInputLabel = GpsToolItem.getInputLabel(gpsTool);
                b.gpsOutputLabel= GpsToolItem.getOutputLabel(gpsTool);
                b.gpsPath       = GpsToolItem.getPath(gpsTool);
                // Persist so markers survive when this drone flies out of scan range.
                DroneGps cached = new DroneGps();
                cached.mode   = b.gpsMode;
                cached.a      = b.gpsA;
                cached.b      = b.gpsB;
                cached.labelA = b.gpsInputLabel;
                cached.labelB = b.gpsOutputLabel;
                cached.path   = b.gpsPath != null ? b.gpsPath : java.util.Collections.emptyList();
                persistentGps.put(b.id.toString(), cached);
            }
            drones.add(b);
            // Cache the UUID so broadcast can still reach this drone if it leaves range.
            entityToUUID.put(b.entityId, b.id);
            // Update trail — only record when position changes.
            droneTrails.computeIfAbsent(b.id, k -> new java.util.ArrayDeque<>());
            java.util.ArrayDeque<BlockPos> trail = droneTrails.get(b.id);
            if (trail.isEmpty() || !trail.peekLast().equals(b.pos)) {
                trail.addLast(b.pos.immutable());
                if (trail.size() > MAX_TRAIL) trail.removeFirst();
            }
        }
    }

    private void sampleTerrainIncremental() {
        Level lvl = os.getLevel();
        BlockPos origin = os.getBlockPos();
        if (lvl == null || origin == null) return;
        BlockPos center = getMapCenter();
        // Wipe cache when view centre drifts too far.
        if (lastSampleOrigin == null
                || Math.abs(center.getX() - lastSampleOrigin.getX()) > MAP_TILES * mapBlockScale / 4
                || Math.abs(center.getZ() - lastSampleOrigin.getZ()) > MAP_TILES * mapBlockScale / 4) {
            lastSampleOrigin = center;
            tileRefreshCursor = 0;
            for (int[] row : mapTiles) java.util.Arrays.fill(row, 0);
        }
        int caveY  = origin.getY(); // Y-slice used in cave mode
        int total  = MAP_TILES * MAP_TILES;
        for (int n = 0; n < TILES_PER_TICK && tileRefreshCursor < total; n++, tileRefreshCursor++) {
            int tx = tileRefreshCursor % MAP_TILES;
            int tz = tileRefreshCursor / MAP_TILES;
            BlockPos wp = tileToWorld(tx, tz);
            // Unloaded chunk — show as dark void.
            if (!lvl.hasChunkAt(wp)) {
                mapTiles[tz][tx] = 0xFF111118;
                continue;
            }
            if (caveMode) {
                // Cave view: sample the block at the computer's Y level.
                BlockPos sample = new BlockPos(wp.getX(), caveY, wp.getZ());
                BlockState state = lvl.getBlockState(sample);
                mapTiles[tz][tx] = blockToMapColor(state, 0);
            } else {
                // Surface view: use heightmap.
                int surfaceY;
                try {
                    surfaceY = lvl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wp.getX(), wp.getZ());
                } catch (Exception ex) { surfaceY = origin.getY(); }
                BlockPos sample = new BlockPos(wp.getX(), Math.max(0, surfaceY - 1), wp.getZ());
                BlockState state = lvl.getBlockState(sample);
                mapTiles[tz][tx] = blockToMapColor(state, surfaceY - origin.getY());
            }
        }
    }

    /** World position at the visual centre of the map (computer pos + pan offset). */
    private BlockPos getMapCenter() {
        BlockPos origin = os.getBlockPos();
        if (origin == null) return BlockPos.ZERO;
        return new BlockPos(origin.getX() + mapPanX, origin.getY(), origin.getZ() + mapPanZ);
    }

    private BlockPos tileToWorld(int tx, int tz) {
        BlockPos center = getMapCenter();
        int half = MAP_TILES / 2;
        return new BlockPos(
                center.getX() + (tx - half) * mapBlockScale,
                center.getY(),
                center.getZ() + (tz - half) * mapBlockScale);
    }

    private int[] worldToTile(BlockPos p) {
        BlockPos center = getMapCenter();
        int half = MAP_TILES / 2;
        return new int[]{
                (p.getX() - center.getX()) / mapBlockScale + half,
                (p.getZ() - center.getZ()) / mapBlockScale + half};
    }

    private int blockToMapColor(BlockState state, int altitudeOffset) {
        if (state.isAir()) return 0xFF2a2a34;
        // Pick a base color by block registry name, then modulate by altitude.
        String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        int base;
        if (state.getFluidState().isEmpty()) {
            base = switch (name) {
                case "grass_block", "short_grass", "tall_grass" -> 0xFF5aba5a;
                case "sand", "sandstone" -> 0xFFdccc90;
                case "stone", "cobblestone", "andesite", "granite", "diorite", "deepslate" -> 0xFF989898;
                case "dirt", "coarse_dirt", "podzol" -> 0xFF9a7a58;
                case "snow", "snow_block", "powder_snow" -> 0xFFf0f4f8;
                case "gravel" -> 0xFFb0b0b0;
                case "oak_log", "spruce_log", "birch_log", "jungle_log", "acacia_log", "dark_oak_log", "mangrove_log" -> 0xFF7a5838;
                case "oak_leaves", "spruce_leaves", "birch_leaves", "jungle_leaves", "acacia_leaves", "dark_oak_leaves", "mangrove_leaves" -> 0xFF4aaa48;
                case "netherrack" -> 0xFF983838;
                case "end_stone" -> 0xFFe8dea8;
                case "lava" -> 0xFFe86018;
                default -> 0xFF888e96;
            };
        } else if (state == Blocks.LAVA.defaultBlockState()
                || name.contains("lava")) {
            base = 0xFFe86018;
        } else {
            base = 0xFF3888d8; // water — lighter blue
        }
        // Gentle shading by altitude relative to computer.
        int shade = Math.max(-25, Math.min(25, altitudeOffset));
        return shadeColor(base, shade);
    }

    private int shadeColor(int argb, int shade) {
        int a = (argb >>> 24) & 0xFF;
        int r = Math.max(0, Math.min(255, ((argb >> 16) & 0xFF) + shade));
        int g = Math.max(0, Math.min(255, ((argb >> 8) & 0xFF) + shade));
        int b = Math.max(0, Math.min(255, (argb & 0xFF) + shade));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ── Render ────────────────────────────────────────────────────────

    @Override
    public void render(TerminalBuffer buffer) {
        // Fallback text mode for when rendered without graphics.
        buffer.writeAt(0, 0, "Drone Map — graphical mode required");
        buffer.writeAt(0, 1, "Drones registered: " + drones.size());
        int i = 2;
        for (DroneBlip d : drones) {
            if (i > 20) break;
            buffer.writeAt(0, i++, d.id.toString().substring(0, 8)
                    + " @ " + d.pos.getX() + "," + d.pos.getY() + "," + d.pos.getZ()
                    + " fuel=" + d.fuel);
        }
    }

    @Override
    public void renderGraphics(PixelBuffer pb) {
        pb.clear(COL_BG);

        // Header.
        String modeStr = caveMode
                ? "[CAVE Y=" + (os.getBlockPos() != null ? os.getBlockPos().getY() : "?") + "]"
                : "[SURFACE]";
        pb.drawString(MAP_X, 6, "Drone Fleet Control  " + modeStr, caveMode ? 0xFFaa66ff : COL_TEXT);
        String panStr = (mapPanX != 0 || mapPanZ != 0) ? "  pan" : "";
        pb.drawStringRight(SIDEBAR_X + SIDEBAR_W, 6, "1:" + mapBlockScale + panStr + "  T=mode", COL_DIM);
        // Map area border.
        pb.fillRect(MAP_X - 1, MAP_Y - 1, MAP_SIZE + 2, MAP_SIZE + 2, COL_BORDER);
        pb.fillRect(MAP_X, MAP_Y, MAP_SIZE, MAP_SIZE, COL_PANEL);

        // Terrain tiles.
        for (int tz = 0; tz < MAP_TILES; tz++) {
            for (int tx = 0; tx < MAP_TILES; tx++) {
                int c = mapTiles[tz][tx];
                if (c == 0) continue;
                pb.fillRect(MAP_X + tx * TILE_PX, MAP_Y + tz * TILE_PX, TILE_PX, TILE_PX, c);
            }
        }

        // Crosshair / grid every 16 blocks.
        int step = 16 / Math.max(1, mapBlockScale);
        if (step >= 4) {
            for (int i = 0; i < MAP_TILES; i += step) {
                pb.drawHLine(MAP_X, MAP_X + MAP_SIZE - 1, MAP_Y + i * TILE_PX, 0x40ffffff);
                pb.drawVLine(MAP_X + i * TILE_PX, MAP_Y, MAP_Y + MAP_SIZE - 1, 0x40ffffff);
            }
        }

        // Cardinal compass labels — drawn just inside the map border so they
        // don't conflict with the header text or go out of bounds.
        int cx = MAP_X + MAP_SIZE / 2;
        int cy = MAP_Y + MAP_SIZE / 2;
        pb.drawStringCentered(MAP_X, MAP_SIZE, MAP_Y + 2,              "N", 0xFFffdd44);
        pb.drawStringCentered(MAP_X, MAP_SIZE, MAP_Y + MAP_SIZE - 14,  "S", 0xFFffdd44);
        pb.drawString(MAP_X + 2,              cy - 4, "W", 0xFFffdd44);
        pb.drawString(MAP_X + MAP_SIZE - 10,  cy - 4, "E", 0xFFffdd44);

        // Scale bar — bottom-left corner of map, shows distance in blocks.
        {
            int sbBlocks = mapBlockScale * 20; // 20 tiles wide
            int sbPx    = 20 * TILE_PX;       // = 80 px
            int sbX = MAP_X + 4, sbY = MAP_Y + MAP_SIZE - 15;
            pb.fillRect(sbX, sbY + 5, sbPx, 2, 0xCCffffff);
            pb.fillRect(sbX, sbY + 2, 2, 7, 0xCCffffff);
            pb.fillRect(sbX + sbPx - 1, sbY + 2, 2, 7, 0xCCffffff);
            pb.drawStringBg(sbX + sbPx / 2 - 12, sbY - 5, sbBlocks + "m", 0xFFffffff, 0xBB000000);
        }

        // "You are here" marker — computer's actual world tile (white box), may not be map
        // centre when the map is panned.
        BlockPos computerPos = os.getBlockPos();
        if (computerPos != null) {
            int[] ct = worldToTile(computerPos);
            if (ct[0] >= 0 && ct[0] < MAP_TILES && ct[1] >= 0 && ct[1] < MAP_TILES) {
                int cpx = MAP_X + ct[0] * TILE_PX + TILE_PX / 2;
                int cpz = MAP_Y + ct[1] * TILE_PX + TILE_PX / 2;
                pb.fillRect(cpx - 5, cpz - 5, 11, 11, 0xFF111a24);
                pb.drawRect(cpx - 4, cpz - 4, 9, 9, 0xFF888888);
                pb.drawRect(cpx - 3, cpz - 3, 7, 7, 0xFFffffff);
                pb.fillRect(cpx - 1, cpz - 1, 3, 3, 0xFFffffff);
            }
        }
        // Faint crosshair at the visual map centre when panned away from the computer.
        if (mapPanX != 0 || mapPanZ != 0) {
            pb.drawHLine(cx - 5, cx + 5, cy, 0x60ffffff);
            pb.drawVLine(cx, cy - 5, cy + 5, 0x60ffffff);
        }

        // ── GPS mode-aware markers ──────────────────────────────────────────────
        // Collect deduplicated position markers for ROUTE and WAYPOINT modes.
        java.util.Map<BlockPos, String[]> gpsInputs  = new java.util.LinkedHashMap<>();
        java.util.Map<BlockPos, String[]> gpsOutputs = new java.util.LinkedHashMap<>();
        java.util.Map<BlockPos, String>   gpsWpts    = new java.util.LinkedHashMap<>();
        for (DroneGps g : persistentGps.values()) {
            if (g.mode.isEmpty()) continue;
            switch (g.mode) {
                case "ROUTE" -> {
                    if (g.a != null) {
                        String lbl = g.labelA.isEmpty() ? "In" : g.labelA;
                        gpsInputs.putIfAbsent(g.a, new String[]{lbl});
                    }
                    if (g.b != null) {
                        String lbl = g.labelB.isEmpty() ? "Out" : g.labelB;
                        gpsOutputs.putIfAbsent(g.b, new String[]{lbl});
                    }
                }
                case "WAYPOINT" -> {
                    if (g.a != null) {
                        String lbl = g.labelA.isEmpty() ? "Wpt" : g.labelA;
                        gpsWpts.putIfAbsent(g.a, lbl);
                    }
                }
                case "AREA" -> {
                    if (g.a != null && g.b != null) {
                        int[] t1 = worldToTile(g.a);
                        int[] t2 = worldToTile(g.b);
                        int ax1 = MAP_X + Math.max(0, Math.min(MAP_TILES - 1, t1[0])) * TILE_PX;
                        int az1 = MAP_Y + Math.max(0, Math.min(MAP_TILES - 1, t1[1])) * TILE_PX;
                        int ax2 = MAP_X + Math.max(0, Math.min(MAP_TILES - 1, t2[0])) * TILE_PX + TILE_PX;
                        int az2 = MAP_Y + Math.max(0, Math.min(MAP_TILES - 1, t2[1])) * TILE_PX + TILE_PX;
                        int rx = Math.min(ax1, ax2), rz = Math.min(az1, az2);
                        int rw = Math.abs(ax2 - ax1), rh = Math.abs(az2 - az1);
                        if (rw > 1 && rh > 1) pb.drawRect(rx, rz, rw, rh, 0xFFffcc00);
                        if (t1[0] >= 0 && t1[0] < MAP_TILES && t1[1] >= 0 && t1[1] < MAP_TILES) {
                            pb.fillRect(ax1 - 3, az1 - 3, 7, 7, 0xFFaa8800);
                            pb.fillRect(ax1 - 2, az1 - 2, 5, 5, 0xFFffcc00);
                            pb.setPixel(ax1, az1, 0xFFffffff);
                        }
                        if (t2[0] >= 0 && t2[0] < MAP_TILES && t2[1] >= 0 && t2[1] < MAP_TILES) {
                            int acx2 = MAP_X + t2[0] * TILE_PX + TILE_PX / 2;
                            int acz2 = MAP_Y + t2[1] * TILE_PX + TILE_PX / 2;
                            pb.fillRect(acx2 - 3, acz2 - 3, 7, 7, 0xFFaa8800);
                            pb.fillRect(acx2 - 2, acz2 - 2, 5, 5, 0xFFffcc00);
                            pb.setPixel(acx2, acz2, 0xFFffffff);
                        }
                    }
                }
                case "PATH" -> {
                    if (g.path != null && !g.path.isEmpty()) {
                        int[] prev = null;
                        for (BlockPos node : g.path) {
                            int[] t = worldToTile(node);
                            if (t[0] < 0 || t[0] >= MAP_TILES || t[1] < 0 || t[1] >= MAP_TILES) {
                                prev = null; continue;
                            }
                            int npx = MAP_X + t[0] * TILE_PX + TILE_PX / 2;
                            int npz = MAP_Y + t[1] * TILE_PX + TILE_PX / 2;
                            if (prev != null) pb.drawLine(prev[0], prev[1], npx, npz, 0xFFcc33ff);
                            pb.fillRect(npx - 3, npz - 3, 7, 7, 0xFF660099);
                            pb.fillRect(npx - 2, npz - 2, 5, 5, 0xFFcc33ff);
                            pb.setPixel(npx, npz, 0xFFffffff);
                            prev = new int[]{npx, npz};
                        }
                    }
                }
            }
        }
        // Draw GPS input markers (green squares — route source / inventory input).
        for (var e : gpsInputs.entrySet()) {
            int[] t = worldToTile(e.getKey());
            if (t[0] < 0 || t[0] >= MAP_TILES || t[1] < 0 || t[1] >= MAP_TILES) continue;
            int mx = MAP_X + t[0] * TILE_PX + TILE_PX / 2;
            int my = MAP_Y + t[1] * TILE_PX + TILE_PX / 2;
            pb.fillRect(mx - 5, my - 5, 11, 11, 0xFF006622);
            pb.fillRect(mx - 4, my - 4, 9,  9,  0xFF33ff66);
            pb.fillRect(mx - 1, my - 1, 3,  3,  0xFFffffff);
            String lbl = e.getValue()[0]; if (lbl.length() > 8) lbl = lbl.substring(0, 8);
            pb.drawString(mx - lbl.length() * 4, my - 16, lbl, 0xFF33ff66);
        }
        // Draw GPS output markers (blue squares — route destination / inventory output).
        for (var e : gpsOutputs.entrySet()) {
            int[] t = worldToTile(e.getKey());
            if (t[0] < 0 || t[0] >= MAP_TILES || t[1] < 0 || t[1] >= MAP_TILES) continue;
            int mx = MAP_X + t[0] * TILE_PX + TILE_PX / 2;
            int my = MAP_Y + t[1] * TILE_PX + TILE_PX / 2;
            pb.fillRect(mx - 5, my - 5, 11, 11, 0xFF003399);
            pb.fillRect(mx - 4, my - 4, 9,  9,  0xFF3399ff);
            pb.fillRect(mx - 1, my - 1, 3,  3,  0xFFffffff);
            String lbl = e.getValue()[0]; if (lbl.length() > 8) lbl = lbl.substring(0, 8);
            pb.drawString(mx - lbl.length() * 4, my - 16, lbl, 0xFF3399ff);
        }
        // Draw GPS waypoint markers (cyan X — WAYPOINT mode).
        for (var e : gpsWpts.entrySet()) {
            int[] t = worldToTile(e.getKey());
            if (t[0] < 0 || t[0] >= MAP_TILES || t[1] < 0 || t[1] >= MAP_TILES) continue;
            int wx = MAP_X + t[0] * TILE_PX + TILE_PX / 2;
            int wy = MAP_Y + t[1] * TILE_PX + TILE_PX / 2;
            pb.drawLine(wx - 5, wy - 5, wx + 5, wy + 5, 0xFF00ccff);
            pb.drawLine(wx + 5, wy - 5, wx - 5, wy + 5, 0xFF00ccff);
            pb.fillRect(wx - 1, wy - 1, 3, 3, 0xFFffffff);
            String lbl = e.getValue(); if (lbl.length() > 8) lbl = lbl.substring(0, 8);
            pb.drawString(wx - lbl.length() * 4, wy - 16, lbl, 0xFF00ccff);
        }

        // Waypoint pins — yellow X markers.
        for (BlockPos wp : mapWaypoints) {
            int[] t = worldToTile(wp);
            if (t[0] < 0 || t[0] >= MAP_TILES || t[1] < 0 || t[1] >= MAP_TILES) continue;
            int wx = MAP_X + t[0] * TILE_PX + TILE_PX / 2;
            int wy = MAP_Y + t[1] * TILE_PX + TILE_PX / 2;
            pb.drawLine(wx - 5, wy - 5, wx + 5, wy + 5, 0xFFffe840);
            pb.drawLine(wx + 5, wy - 5, wx - 5, wy + 5, 0xFFffe840);
            pb.fillRect(wx - 1, wy - 1, 3, 3, 0xFFffffff);
        }

        // Bookmark markers — cyan diamonds, clickable to jump view.
        {
            BlockPos bmOrigin = os.getBlockPos();
            for (int bi = 0; bi < viewBookmarks.size(); bi++) {
                int[] bm = viewBookmarks.get(bi);
                if (bmOrigin == null) break;
                BlockPos bmWorld = new BlockPos(bmOrigin.getX() + bm[0], 0, bmOrigin.getZ() + bm[1]);
                int[] t = worldToTile(bmWorld);
                if (t[0] < 0 || t[0] >= MAP_TILES || t[1] < 0 || t[1] >= MAP_TILES) continue;
                int bpx = MAP_X + t[0] * TILE_PX + TILE_PX / 2;
                int bpz = MAP_Y + t[1] * TILE_PX + TILE_PX / 2;
                pb.drawLine(bpx, bpz - 5, bpx + 5, bpz, 0xFF00ccff);
                pb.drawLine(bpx + 5, bpz, bpx, bpz + 5, 0xFF00ccff);
                pb.drawLine(bpx, bpz + 5, bpx - 5, bpz, 0xFF00ccff);
                pb.drawLine(bpx - 5, bpz, bpx, bpz - 5, 0xFF00ccff);
                pb.fillRect(bpx - 1, bpz - 1, 3, 3, 0xFF00ccff);
                pb.drawString(bpx + 6, bpz - 9, bookmarkNames.get(bi), 0xFF00ccff);
            }
        }

        // Home lines — faint line from each selected drone to its home position.
        for (DroneBlip d : drones) {
            if (!selectedIds.contains(d.entityId) || d.homePos == null) continue;
            int[] dt = worldToTile(d.pos);
            int[] ht = worldToTile(d.homePos);
            if (dt[0] < 0 || dt[0] >= MAP_TILES || dt[1] < 0 || dt[1] >= MAP_TILES) continue;
            if (ht[0] < 0 || ht[0] >= MAP_TILES || ht[1] < 0 || ht[1] >= MAP_TILES) continue;
            int dpx = MAP_X + dt[0] * TILE_PX + TILE_PX / 2;
            int dpy = MAP_Y + dt[1] * TILE_PX + TILE_PX / 2;
            int hpx = MAP_X + ht[0] * TILE_PX + TILE_PX / 2;
            int hpy = MAP_Y + ht[1] * TILE_PX + TILE_PX / 2;
            pb.drawLine(dpx, dpy, hpx, hpy, 0x60aaddff);
            pb.fillRect(hpx - 3, hpy - 3, 7, 7, 0xFF1a3a5a);
            pb.drawRect(hpx - 3, hpy - 3, 7, 7, 0xFF88ccff);
            pb.setPixel(hpx, hpy, 0xFFffffff);
        }

        // Drone trails — fading path of recent positions.
        for (DroneBlip d : drones) {
            java.util.ArrayDeque<BlockPos> trail = droneTrails.get(d.id);
            if (trail == null || trail.size() < 2) continue;
            BlockPos[] pts = trail.toArray(new BlockPos[0]);
            int baseCol = droneColor(d) & 0x00FFFFFF;
            for (int i = 0; i < pts.length - 1; i++) {
                int alpha = 30 + (i * 180 / pts.length);
                int[] t1 = worldToTile(pts[i]);
                int[] t2 = worldToTile(pts[i + 1]);
                if (t1[0] < 0 || t1[0] >= MAP_TILES || t1[1] < 0 || t1[1] >= MAP_TILES) continue;
                if (t2[0] < 0 || t2[0] >= MAP_TILES || t2[1] < 0 || t2[1] >= MAP_TILES) continue;
                int px1 = MAP_X + t1[0] * TILE_PX + TILE_PX / 2;
                int py1 = MAP_Y + t1[1] * TILE_PX + TILE_PX / 2;
                int px2 = MAP_X + t2[0] * TILE_PX + TILE_PX / 2;
                int py2 = MAP_Y + t2[1] * TILE_PX + TILE_PX / 2;
                pb.drawLine(px1, py1, px2, py2, (alpha << 24) | baseCol);
            }
        }

        // Drone blips — bright green dot with black outline for readability.
        for (DroneBlip d : drones) {
            int[] t = worldToTile(d.pos);
            if (t[0] < 0 || t[0] >= MAP_TILES || t[1] < 0 || t[1] >= MAP_TILES) continue;
            int dx = MAP_X + t[0] * TILE_PX + TILE_PX / 2;
            int dy = MAP_Y + t[1] * TILE_PX + TILE_PX / 2;
            int col = droneColor(d);
            boolean sel = selectedIds.contains(d.entityId);
            // Black outline (8px square) so the dot pops against any terrain.
            pb.fillRect(dx - 4, dy - 4, 9, 9, 0xFF000000);
            // Colored dot.
            pb.fillRect(dx - 3, dy - 3, 7, 7, col);
            // Bright inner pixel.
            pb.fillRect(dx - 1, dy - 1, 3, 3, 0xFFffffff);
            if (sel) {
                pb.drawRect(dx - 6, dy - 6, 13, 13, COL_DRONE_SEL);
                pb.drawRect(dx - 5, dy - 5, 11, 11, COL_DRONE_SEL);
            }
        }

        // Toolbar.
        int btnW = 80, btnH = 18, btnGap = 6;
        String[] labels = { "Set Home", "Go Home", "Clear", "Hover", "Rescan", "Pin" };
        for (int i = 0; i < labels.length; i++) {
            int bx = MAP_X + i * (btnW + btnGap);
            pb.fillRect(bx, TOOLBAR_Y, btnW, btnH, COL_BTN);
            pb.drawRect(bx, TOOLBAR_Y, btnW, btnH, COL_BORDER);
            pb.drawStringCentered(bx, btnW, TOOLBAR_Y + 3, labels[i], COL_TEXT);
        }

        // Sidebar drone list.
        pb.fillRect(SIDEBAR_X - 1, MAP_Y - 1, SIDEBAR_W + 2, MAP_SIZE + 2, COL_BORDER);
        pb.fillRect(SIDEBAR_X, MAP_Y, SIDEBAR_W, MAP_SIZE, COL_PANEL);
        // Sidebar header.
        int selCount = selectedIds.size();
        String selLabel = selCount == 0 ? "none" : selCount + "/" + drones.size() + " selected";
        pb.fillRect(SIDEBAR_X, MAP_Y, SIDEBAR_W, 16, 0xFF1a3a5a);
        pb.drawString(SIDEBAR_X + 4, MAP_Y + 2, "Drones — " + selLabel, 0xFF60ffff);
        int rowY = MAP_Y + 18;
        for (DroneBlip d : drones) {
            if (rowY > MAP_Y + MAP_SIZE - 36) break;
            boolean sel = selectedIds.contains(d.entityId);
            // Row background: bright blue when selected.
            if (sel) pb.fillRect(SIDEBAR_X + 1, rowY, SIDEBAR_W - 2, 34, 0xFF0d4a8a);
            // Left accent bar when selected.
            if (sel) pb.fillRect(SIDEBAR_X + 1, rowY, 3, 34, 0xFF00cfff);
            int col = droneColor(d);
            pb.fillRect(SIDEBAR_X + 7, rowY + 14, 6, 6, col); // dot centered between the two text lines
            // Line 1: name if set, else info line
            // Line 2: info if name shown, else home position
            String nameLine = d.name.isEmpty() ? null : d.name;
            String infoLine = "ch" + d.channel + " " + d.variant
                    + (d.group.isEmpty() ? "" : "[" + d.group + "]")
                    + " F" + d.fuel
                    + (d.gpsMode.isEmpty() ? "" : " GPS");
            String homeLine = d.homePos != null
                    ? "H:" + d.homePos.getX() + "," + d.homePos.getY() + "," + d.homePos.getZ()
                    : "H: not set";
            String line1 = nameLine != null ? nameLine : infoLine;
            String line2 = nameLine != null ? infoLine : homeLine;
            int c1 = nameLine != null ? (sel ? 0xFFffffff : 0xFFdddddd) : (sel ? 0xFF00ffff : COL_TEXT);
            int c2 = sel ? 0xFF88ccff : COL_DIM;
            pb.drawString(SIDEBAR_X + 16, rowY + 2,
                    line1.substring(0, Math.min(line1.length(), 32)), c1);
            pb.drawString(SIDEBAR_X + 16, rowY + 19,
                    line2.substring(0, Math.min(line2.length(), 32)), c2);
            rowY += 36;
        }
        if (drones.isEmpty()) {
            pb.drawString(SIDEBAR_X + 4, MAP_Y + 24, "No drones nearby.", COL_DIM);
        }

        // Footer controls hint.
        pb.drawString(MAP_X, TOOLBAR_Y + 24, "Drag: pan | Tap: waypoint/select drone | R: home | scroll: zoom | T: surface/cave | Pin: bookmark", COL_DIM);

        // ── Fuel alert banner — overlays header if any drone is critically low ──
        {
            boolean anyLow = false;
            StringBuilder alertMsg = new StringBuilder();
            for (DroneBlip d : drones) {
                if (d.fuel > 0 && d.fuel < 120) {
                    anyLow = true;
                    String nm = d.name.isEmpty() ? d.id.toString().substring(0, 6) : d.name;
                    if (alertMsg.length() > 0) alertMsg.append("  ");
                    alertMsg.append(nm);
                }
            }
            if (anyLow) {
                boolean blink = (System.currentTimeMillis() / 350) % 2 == 0;
                pb.fillRect(0, 0, 640, 14, blink ? 0xFFbb1111 : 0xFF771111);
                pb.drawStringCentered(0, 640, 2, "! LOW FUEL: " + alertMsg, 0xFFffdddd);
            }
        }

        // ── Legend / key panel (bottom-right, below toolbar row) ───────────────
        {
            int lgX = SIDEBAR_X, lgY = TOOLBAR_Y;
            pb.fillRect(lgX - 1, lgY - 1, SIDEBAR_W + 2, 50, COL_BORDER);
            pb.fillRect(lgX, lgY, SIDEBAR_W, 48, COL_PANEL);
            pb.fillRect(lgX, lgY, SIDEBAR_W, 12, 0xFF1a3a5a);
            pb.drawString(lgX + 4, lgY + 2, "Key", 0xFF60ffff);
            // Row 1: computer + drone states.
            int lx = lgX + 4, ly = lgY + 14;
            pb.drawRect(lx, ly + 4, 8, 8, 0xFFffffff);
            pb.drawString(lx + 10, ly + 2, "You", COL_TEXT);
            lx += 44;
            pb.fillRect(lx, ly + 5, 7, 7, COL_DRONE_IDLE);
            pb.drawString(lx + 9, ly + 2, "Idle", COL_TEXT);
            lx += 42;
            pb.fillRect(lx, ly + 5, 7, 7, COL_DRONE_MOVING);
            pb.drawString(lx + 9, ly + 2, "Move", COL_TEXT);
            lx += 46;
            pb.fillRect(lx, ly + 5, 7, 7, COL_DRONE_LOW);
            pb.drawString(lx + 9, ly + 2, "Low", COL_TEXT);
            lx += 40;
            pb.fillRect(lx, ly + 5, 7, 7, COL_DRONE_DEF);
            pb.drawString(lx + 9, ly + 2, "Def", COL_TEXT);
            // Row 2: GPS markers.
            lx = lgX + 4; ly = lgY + 30;
            pb.fillRect(lx, ly + 4, 9, 9, 0xFF33ff66);
            pb.drawString(lx + 11, ly + 2, "Input", 0xFF33ff66);
            lx += 58;
            pb.fillRect(lx, ly + 4, 9, 9, 0xFF3399ff);
            pb.drawString(lx + 11, ly + 2, "Output", 0xFF3399ff);
            lx += 68;
            pb.drawLine(lx + 1, ly + 4, lx + 7, ly + 10, 0xFF00ccff);
            pb.drawLine(lx + 7, ly + 4, lx + 1, ly + 10, 0xFF00ccff);
            pb.drawString(lx + 11, ly + 2, "Wpt", 0xFF00ccff);
            lx += 44;
            pb.drawRect(lx, ly + 4, 9, 9, 0xFFffcc00);
            pb.drawString(lx + 11, ly + 2, "Area", 0xFFffcc00);
            lx += 48;
            pb.fillRect(lx + 1, ly + 5, 7, 7, 0xFFcc33ff);
            pb.drawString(lx + 11, ly + 2, "Path", 0xFFcc33ff);
        }
    }

    private int droneColor(DroneBlip d) {
        if (d.fuel > 0 && d.fuel < 120) return COL_DRONE_LOW; // only critical fuel = red (~6s)
        if (d.defender) return COL_DRONE_DEF;
        if (d.hasTarget) return COL_DRONE_MOVING;
        return COL_DRONE_IDLE; // bright green — default
    }

    // ── GPS JSON parser (BT ch9100 broadcasts) ────────────────────────────────
    private static DroneGps parseGpsJson(String json) {
        try {
            DroneGps g = new DroneGps();
            g.mode   = jsonStr(json, "mode");
            g.a      = jsonPos(json, "\"a\":");
            g.b      = jsonPos(json, "\"b\":");
            g.labelA = jsonStr(json, "inputLabel");
            g.labelB = jsonStr(json, "outputLabel");
            int pi = json.indexOf("\"path\":[");
            if (pi >= 0) {
                int pos = pi + 8;
                while (pos < json.length()) {
                    char c = json.charAt(pos);
                    if (c == '{') {
                        int end = json.indexOf('}', pos);
                        if (end < 0) break;
                        BlockPos bp = jsonPosFromObj(json.substring(pos + 1, end));
                        if (bp != null) g.path.add(bp);
                        pos = end + 1;
                    } else if (c == ']') break;
                    else pos++;
                }
            }
            return g.mode.isEmpty() ? null : g;
        } catch (Exception e) { return null; }
    }
    private static String jsonStr(String s, String key) {
        String k = "\"" + key + "\":\"";
        int i = s.indexOf(k); if (i < 0) return "";
        i += k.length(); int j = s.indexOf('"', i);
        return j <= i ? "" : s.substring(i, j);
    }
    private static BlockPos jsonPos(String s, String key) {
        int i = s.indexOf(key); if (i < 0) return null;
        i = s.indexOf('{', i + key.length() - 1); if (i < 0) return null;
        int j = s.indexOf('}', i); if (j < 0) return null;
        return jsonPosFromObj(s.substring(i + 1, j));
    }
    private static BlockPos jsonPosFromObj(String obj) {
        int x = jsonInt(obj, "\"x\":"), y = jsonInt(obj, "\"y\":"), z = jsonInt(obj, "\"z\":");
        return x == Integer.MIN_VALUE ? null : new BlockPos(x, y, z);
    }
    private static int jsonInt(String s, String key) {
        int i = s.indexOf(key); if (i < 0) return Integer.MIN_VALUE;
        i += key.length(); int j = i;
        while (j < s.length() && (s.charAt(j) == '-' || Character.isDigit(s.charAt(j)))) j++;
        return j == i ? Integer.MIN_VALUE : Integer.parseInt(s.substring(i, j));
    }

    private static class DroneGps {
        String mode = "";
        BlockPos a = null, b = null;
        String labelA = "", labelB = "";
        java.util.List<BlockPos> path = new java.util.ArrayList<>();
    }

    private static class DroneBlip {
        UUID id;       // for broadcasting
        int entityId;  // for selection (always valid, no sync required)
        BlockPos pos;
        BlockPos homePos;
        int fuel;
        boolean defender;
        boolean hasTarget;
        String group = "";
        String variant = "STANDARD";
        int channel = 1;
        String name = "";  // custom name if set
        // GPS tool data (null if no GPS tool applied).
        BlockPos gpsA = null;          // input / source chest
        BlockPos gpsB = null;          // output / dest chest
        String gpsMode = "";
        String gpsInputLabel  = "";
        String gpsOutputLabel = "";
        java.util.List<BlockPos> gpsPath = null;  // PATH mode nodes
    }
}

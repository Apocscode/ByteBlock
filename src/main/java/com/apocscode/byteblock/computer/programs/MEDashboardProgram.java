package com.apocscode.byteblock.computer.programs;

import com.apocscode.byteblock.ByteBlock;
import com.apocscode.byteblock.block.entity.ByteChestBlockEntity;
import com.apocscode.byteblock.block.entity.MonitorBlockEntity;
import com.apocscode.byteblock.block.entity.PeripheralBlockEntity;
import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.computer.OSEvent;
import com.apocscode.byteblock.computer.OSProgram;
import com.apocscode.byteblock.computer.PixelBuffer;
import com.apocscode.byteblock.computer.TerminalBuffer;
import com.apocscode.byteblock.computer.peripheral.AE2PeripheralAdapter;
import com.apocscode.byteblock.computer.peripheral.AE2PeripheralAdapter.AECraftingJob;
import com.apocscode.byteblock.computer.peripheral.AE2PeripheralAdapter.AEEnergyInfo;
import com.apocscode.byteblock.computer.peripheral.AE2PeripheralAdapter.AEFluidEntry;
import com.apocscode.byteblock.computer.peripheral.AE2PeripheralAdapter.AEItemEntry;
import com.apocscode.byteblock.network.BluetoothNetwork;
import com.apocscode.byteblock.network.BluetoothNetwork.LabeledChest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * ME Network Dashboard — built-in AE2 storage & crafting monitor.
 *
 * <p>Auto-detects an adjacent ME network on any of the 6 sides. Displays
 * four tabs on the 640×400 canvas:
 * <ul>
 *   <li><b>Storage</b>  — scrollable item list with craftable badges &amp; counts</li>
 *   <li><b>Fluids</b>   — fluid list with mB amounts</li>
 *   <li><b>Crafting</b> — active CPU jobs with progress bars</li>
 *   <li><b>Network</b>  — energy gauge, node count, powered status</li>
 * </ul>
 *
 * Refresh rate: every 40 ticks (2 s). Scroll and tab switching respond
 * to mouse click/scroll events forwarded by DesktopProgram.
 */
public class MEDashboardProgram extends OSProgram {

    // ── UI colours (ARGB) ─────────────────────────────────────────────────
    private static final int C_BG          = 0xFF0A0A1A;
    private static final int C_PANEL       = 0xFF0F1428;
    private static final int C_BORDER      = 0xFF2233AA;
    private static final int C_HEADER      = 0xFF1A2060;
    private static final int C_TITLE       = 0xFFAADDFF;
    private static final int C_TEXT        = 0xFFCCCCCC;
    private static final int C_DIM         = 0xFF888888;
    private static final int C_GOOD        = 0xFF44FF88;
    private static final int C_WARN        = 0xFFFFCC44;
    private static final int C_BAD         = 0xFFFF4444;
    private static final int C_CRAFT_BADGE = 0xFF2266FF;
    private static final int C_TAB_ACTIVE  = 0xFF1A3080;
    private static final int C_TAB_IDLE    = 0xFF0D1840;
    private static final int C_ENERGY_BAR  = 0xFF2299FF;
    private static final int C_SEARCH_BG   = 0xFF0C1030;
    private static final int C_HIGHLIGHT   = 0xFF172048;
    private static final int C_FLUID_BAR   = 0xFF44AAFF;
    private static final int C_PROGRESS    = 0xFF44FF66;
    // Export dialog colours
    private static final int C_SELECTED    = 0xFF1A3A6A;
    private static final int C_DLG_BG      = 0xFF080E24;
    private static final int C_DLG_BORDER  = 0xFF3355CC;
    private static final int C_BTN         = 0xFF162060;
    private static final int C_BTN_HOT     = 0xFF2244AA;
    private static final int C_CHEST_SEL   = 0xFF0F2850;
    private static final int C_MONITOR_SEL  = 0xFF10280A; // green tint for monitor rows

    // ── Layout constants ──────────────────────────────────────────────────
    private static final int W  = PixelBuffer.SCREEN_W; // 640
    private static final int H  = PixelBuffer.SCREEN_H; // 400
    private static final int HEADER_H  = 24;
    private static final int TAB_H     = 18;
    private static final int SEARCH_H  = 16;
    private static final int FOOTER_H  = 14;
    private static final int CONTENT_Y = HEADER_H + TAB_H + SEARCH_H;
    private static final int CONTENT_H = H - CONTENT_Y - FOOTER_H;
    private static final int ROW_H     = 20;
    private static final int DENSE_ROW_H = 10;
    private static final int GRID_CELL_H = 10;
    private static final int GRID_COLS   = 4;
    private static final int VISIBLE_ROWS = CONTENT_H / ROW_H;
    // Alternating row colours — blue spectrum for easy scanning
    private static final int C_ROW_EVEN   = 0xFF091628;  // deep blue
    private static final int C_ROW_ODD    = 0xFF112244;  // lighter blue

    // ── Customizable color palettes ───────────────────────────────────────
        private static final int[] BG_OPTIONS = {
            0xFF18263F, 0xFF22242B, 0xFF203A25, 0xFF3A2222, 0xFF3A3420, 0xFF2D2240,
            0xFF1F3A3A, 0xFF2F313A, 0xFF2A1E1E, 0xFF1D2F45, 0xFF223626, 0xFF31223A
        };
        private static final int[] ROW_E_OPTIONS = {
            0xFF213A5A, 0xFF294D2F, 0xFF5A2A2A, 0xFF5A5230, 0xFF2A5656, 0xFF3D3F48,
            0xFF2C2F63, 0xFF454B58, 0xFF4A3B2A, 0xFF2E435A, 0xFF3C5A2E, 0xFF55334A
        };
        private static final int[] ROW_O_OPTIONS = {
            0xFF2C4B74, 0xFF37663D, 0xFF753A3A, 0xFF746A3E, 0xFF3A7474, 0xFF4E5160,
            0xFF3B4182, 0xFF58606F, 0xFF665038, 0xFF3C5B7D, 0xFF4D7640, 0xFF6A4160
        };
        private static final int[] TEXT_OPTIONS = {
            0xFFE6ECFF, 0xFFFFFFFF, 0xFFB7E0FF, 0xFFBFFFD3, 0xFFFFE3A6, 0xFFFFC7FF,
            0xFFB8FFFF, 0xFFD2D6DF, 0xFFFFD0A8, 0xFFCBE7FF, 0xFFE8FFBF, 0xFFFFE8F8
        };
        private static final int[] HEADER_OPTIONS = {
            0xFF1A2060, 0xFF203048, 0xFF2D244F, 0xFF1F3A2A, 0xFF3A2620, 0xFF2F2F2F,
            0xFF3A213D, 0xFF1D3C46, 0xFF3C301A, 0xFF2A3F63
        };
        private static final int[] TITLE_OPTIONS = {
            0xFFAADDFF, 0xFFFFFFFF, 0xFF9BE8FF, 0xFFBFFFC8, 0xFFFFE9A8, 0xFFFFCCF2,
            0xFFC6E1FF, 0xFFFFD8C8, 0xFFE8FFCC, 0xFFD6CBFF
        };

        private static final String[] BG_NAMES = {
            "Steel Blue", "Graphite", "Forest", "Brick", "Olive", "Plum",
            "Teal", "Slate", "Mahogany", "Navy", "Pine", "Mulberry"
        };
        private static final String[] ROW_E_NAMES = {
            "Blue Mist", "Moss", "Rosewood", "Khaki", "Sea Glass", "Silver Gray",
            "Indigo Mist", "Stone", "Copper", "Denim", "Fern", "Berry"
        };
        private static final String[] ROW_O_NAMES = {
            "Blue Sky", "Green Leaf", "Coral", "Sandstone", "Aqua", "Cloud Gray",
            "Violet", "Smoke", "Bronze", "Ocean", "Clover", "Orchid"
        };
        private static final String[] TEXT_NAMES = {
            "Soft White", "Pure White", "Ice Blue", "Mint", "Warm Sand", "Pink Glow",
            "Aqua Light", "Cool Gray", "Apricot", "Sky", "Lime Cream", "Rose"
        };
        private static final String[] HEADER_NAMES = {
            "Royal", "Blue Gray", "Night Plum", "Evergreen", "Burnt Umber",
            "Charcoal", "Wine", "Deep Teal", "Mustard", "Cobalt"
        };
        private static final String[] TITLE_NAMES = {
            "Azure", "White", "Cyan", "Mint", "Gold", "Blush", "Frost", "Peach", "Lemon", "Lavender"
        };

    private int idxBg   = 0;
    private int idxRowE = 0;
    private int idxRowO = 0;
    private int idxText = 0;
    private int idxHeader = 0;
    private int idxTitle = 0;
    private int colorRowsVisible = COLOR_DEFAULT_VISIBLE_ROWS;
    private int colorSettingsScroll = 0;
    private boolean colorSettingsDirty = false;
    private boolean draggingDisplayDivider = false;

    private int cBg()      { return BG_OPTIONS[idxBg]; }
    private int cRowEven() { return ROW_E_OPTIONS[idxRowE]; }
    private int cRowOdd()  { return ROW_O_OPTIONS[idxRowO]; }
    private int cText()    { return TEXT_OPTIONS[idxText]; }
    private int cHeader()  { return HEADER_OPTIONS[idxHeader]; }
    private int cTitle()   { return TITLE_OPTIONS[idxTitle]; }

    private static final String[] TAB_NAMES = { "Storage", "Fluids", "Crafting", "Network", "Display" };
    private static final int TAB_W = W / TAB_NAMES.length;

    private enum StorageViewMode {
        NORMAL("Normal"),
        DENSE("Dense"),
        GRID("Grid");

        private final String label;

        StorageViewMode(String label) { this.label = label; }

        public String label() { return label; }

        public StorageViewMode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private static final String SETTINGS_FILE = "/Users/User/Documents/.me_dashboard_settings";
    private static final String SETTINGS_FILE_FALLBACK = "/Users/User/Documents/me_dashboard_settings";
    private static final String SETTINGS_DISK_FILE = "me_dashboard_settings.tsv";
    private static final String SETTINGS_PERSISTENT_KEY = "me_dashboard.settings.tsv";

    private static String settingsDigest(String data) {
        if (data == null) return "null";
        String trimmed = data.trim();
        if (trimmed.isEmpty()) return "empty";
        int lines = trimmed.split("\\R").length;
        return "len=" + data.length() + ",lines=" + lines + ",hash=" + Integer.toHexString(data.hashCode());
    }

    private String serializeSettings(boolean includeKnownItems) {
        StringBuilder data = new StringBuilder();
        data.append("view\t").append(storageView.name()).append('\n');
        data.append("color\tbg\t").append(idxBg).append('\n');
        data.append("color\trowE\t").append(idxRowE).append('\n');
        data.append("color\trowO\t").append(idxRowO).append('\n');
        data.append("color\ttext\t").append(idxText).append('\n');
        data.append("color\theader\t").append(idxHeader).append('\n');
        data.append("color\ttitle\t").append(idxTitle).append('\n');
        data.append("color\trows\t").append(colorRowsVisible).append('\n');

        java.util.List<String> keys = new java.util.ArrayList<>(itemThresholds.keySet());
        keys.sort(String::compareTo);
        for (String key : keys) {
            Long value = itemThresholds.get(key);
            if (value == null) continue;
            data.append("threshold\t")
                    .append(key)
                    .append('\t')
                    .append(value)
                    .append('\n');
        }

        if (!includeKnownItems) {
            return data.toString();
        }

        java.util.List<String> itemKeys = new java.util.ArrayList<>(knownItems.keySet());
        itemKeys.sort(String::compareTo);
        for (String key : itemKeys) {
            AEItemEntry item = knownItems.get(key);
            if (item == null) continue;
            data.append("known\t")
                    .append(item.name())
                    .append('\t')
                    .append(item.displayName().replace("\n", " ").replace("\t", " "))
                    .append('\t')
                    .append(item.craftable() ? '1' : '0')
                    .append('\n');
        }
        return data.toString();
    }
    private static final int STORAGE_VIEW_BTN_W  = 118;
    private static final int STORAGE_TRASH_BTN_W = 60;
    // Auto-scroll button — only shown in Dense mode (defined here for layout math)

    // ── Export dialog layout constants ───────────────────────────────────
    private static final int DLG_W          = 400;
    private static final int DLG_H          = 180;
    private static final int DLG_X          = (W - DLG_W) / 2;   // 120
    private static final int DLG_Y          = (H - DLG_H) / 2;   // 110
    private static final int DLG_CHEST_ROWS = 4;
    private static final int DLG_ROW_H      = 14;

    // ── State ─────────────────────────────────────────────────────────────
    private JavaOS os;
    private int   activeTab     = 0;
    private int   scrollItem    = 0;
    private int   scrollFluid   = 0;
    private int   scrollMonitor = 0;
    private StorageViewMode storageView = StorageViewMode.NORMAL;
    private boolean autoScrollDense   = false;
    private int     autoScrollCounter = 0;
    private boolean settingsLoaded    = false;
    private boolean settingsHydrated  = false;
    private boolean settingsMissingLogged = false;
    private int settingsLoadRetryTicks = 0;
    private static final int SETTINGS_RETRY_INTERVAL_TICKS = 10;
    private static final int AUTO_SCROLL_TICKS = 6; // ticks between each row advance (~300 ms)
    private static final int STORAGE_AUTO_BTN_W = 46;
    private final StringBuilder searchBuffer = new StringBuilder();
    // ── Export dialog state ──────────────────────────────────────────
    private AEItemEntry     selectedItem      = null;
    private boolean         showExportDialog  = false;
    private final StringBuilder exportQty     = new StringBuilder("64");
    private List<LabeledChest> exportChests   = new ArrayList<>();
    private int             exportChestScroll = 0;
    private LabeledChest    exportChestChoice = null;
    /** Short result message shown in dialog footer after an export attempt. */
    private String          exportResultMsg   = null;
    private long            exportResultTick  = -1;
    // ── Mirror dialog state ──────────────────────────────────────────
    private boolean         showMirrorDialog    = false;
    private List<MonitorEntry> mirrorMonitors   = new ArrayList<>();
    private int             mirrorMonitorScroll = 0;
    private MonitorEntry    mirrorMonitorChoice = null;
    private UUID            activeMirrorId      = null;  // UUID of monitor currently mirroring us
    private String          mirrorResultMsg     = null;
    private long            mirrorResultTick    = -1;

    private record MonitorEntry(UUID deviceId, BlockPos pos, String label, double dist) {}
    // ── Item analytics ────────────────────────────────────────────────────
    private final java.util.HashMap<String, Long> prevItemCounts  = new java.util.HashMap<>();
    private final java.util.HashMap<String, Long> itemDeltas      = new java.util.HashMap<>();
    /** Per-item minimum-stock thresholds (registry name → min count). */
    private final java.util.HashMap<String, Long> itemThresholds  = new java.util.HashMap<>();
    /** Items that should remain visible even when the network currently has zero of them. */
    private final java.util.LinkedHashMap<String, AEItemEntry> knownItems = new java.util.LinkedHashMap<>();
    /** Digits being typed into the threshold field for the selected item. */
    private final StringBuilder thresholdBuffer = new StringBuilder();

    // ── Data (refreshed every 40 ticks) ───────────────────────────────────
    private BlockEntity         meNode       = null;
    private AEEnergyInfo        energy       = null;
    private List<AEItemEntry>   items        = new ArrayList<>();
    private List<AEFluidEntry>  fluids       = new ArrayList<>();
    private List<AECraftingJob> craftingJobs = new ArrayList<>();
    private int                 nodeCount    = 0;
    private long                lastRefresh  = -1;
    private String              statusMsg    = "Searching for ME Network...";

    // ── Wireless (BT ch9200) data from a Universal Peripheral AE2 bridge ──
    /** UUID of this computer's OS device — used to register on BT ch9200. */
    private UUID btDeviceId = UUID.randomUUID();
    /** True when last data came from a remote Peripheral bridge over BT. */
    private boolean btSource = false;
    /** Tick of last BT message received — used to detect stale connections. */
    private long btLastReceived = -1;
    private static final int BT_STALE_TICKS = 120; // 6 s

    public MEDashboardProgram() { super("ME Dashboard"); }

    @Override
    public void init(JavaOS os) {
        this.os = os;
        // loadSettings() is deferred to first tick() so the VFS disk mirror has
        // time to be initialized (pullVfsFromDisk runs after os.tick() on tick 1).
        refresh();
    }

    @Override
    public boolean tick() {
        // Load settings on first tick — disk mirror is ready by then.
            // Defer to tick 2, then retry until data appears. On some startups,
            // client-side BE sync can arrive after the first dashboard tick.
            if (!settingsLoaded && os.getTickCount() >= 2) {
                if (settingsLoadRetryTicks > 0) {
                    settingsLoadRetryTicks--;
                } else {
                    settingsLoaded = loadSettings();
                    if (!settingsLoaded) settingsLoadRetryTicks = SETTINGS_RETRY_INTERVAL_TICKS;
                }
            }
            long tick = os.getTickCount();

        // Register on BT channel so peripheral broadcasts can reach us
        Level lvl = os.getLevel();
        BlockPos pos = os.getBlockPos();
        if (lvl != null && pos != null) {
            BluetoothNetwork.register(lvl, btDeviceId, pos, PeripheralBlockEntity.AE2_BT_CHANNEL,
                    BluetoothNetwork.DeviceType.COMPUTER);
            // Drain all BT messages on ch9200
            BluetoothNetwork.Message btMsg;
            while ((btMsg = BluetoothNetwork.receive(btDeviceId)) != null) {
                if (btMsg.channel() == PeripheralBlockEntity.AE2_BT_CHANNEL) {
                    handleAe2BtMessage(btMsg.content());
                }
            }
        }

        if (tick - lastRefresh >= 40 || lastRefresh < 0) {
            refresh();
            lastRefresh = tick;
        }

        // Auto-scroll dense mode
        if (activeTab == 0 && storageView == StorageViewMode.DENSE && autoScrollDense) {
            autoScrollCounter++;
            if (autoScrollCounter >= AUTO_SCROLL_TICKS) {
                autoScrollCounter = 0;
                int total = storageTotalRows();
                int visible = storageVisibleSlots();
                if (total > visible) {
                    scrollItem++;
                    if (scrollItem > total - visible) scrollItem = 0;
                }
            }
        } else {
            autoScrollCounter = 0;
        }

        return running;
    }

    private void refresh() {
        if (!ModList.get().isLoaded("ae2")) {
            statusMsg = "Applied Energistics 2 not installed.";
            meNode    = null;
            return;
        }
        Level level = os.getLevel();
        BlockPos pos = os.getBlockPos();
        if (level == null || pos == null) { statusMsg = "No world context."; return; }

        // Scan all 6 sides for an AE2 node
        meNode = null;
        for (Direction dir : Direction.values()) {
            BlockEntity be = level.getBlockEntity(pos.relative(dir));
            if (be != null && AE2PeripheralAdapter.isAvailableJava(be)) {
                meNode = be;
                break;
            }
        }
        if (meNode == null) {
            // If we have recent BT data, stay connected via wireless
            long tick = os.getTickCount();
            if (btSource && btLastReceived >= 0 && tick - btLastReceived < BT_STALE_TICKS) {
                statusMsg = null; // bt data is fresh — keep displaying
            } else {
                btSource = false;
                statusMsg = "No ME Network detected. Place adjacent to AE2 cable/controller,\nor place a Universal Peripheral adjacent to your AE2 network."
                        .replace(",\nor", "\n");
                items        = new ArrayList<>();
                fluids       = new ArrayList<>();
                craftingJobs = new ArrayList<>();
                energy       = null;
                nodeCount    = 0;
            }
            return;
        }
        btSource     = false; // direct connection takes priority
        statusMsg    = null;
        energy       = AE2PeripheralAdapter.queryEnergyJava(meNode);
        nodeCount    = AE2PeripheralAdapter.queryNodeCountJava(meNode);
        craftingJobs = AE2PeripheralAdapter.queryCraftingJobsJava(meNode);

        // Items — sort by count desc
        items = AE2PeripheralAdapter.queryItemsJava(meNode);
        if (mergeKnownItems(items) && settingsLoaded && settingsHydrated) saveSettings();
        items.sort(Comparator.comparingLong(AEItemEntry::count).reversed().thenComparing(AEItemEntry::displayName, String.CASE_INSENSITIVE_ORDER));
        updateItemDeltas(items);

        // Fluids — sort by amount desc
        fluids = AE2PeripheralAdapter.queryFluidsJava(meNode);
        fluids.sort(Comparator.comparingLong(AEFluidEntry::amountMb).reversed());
    }

    // ── Input handling ─────────────────────────────────────────────────────

    @Override
    public void handleEvent(OSEvent event) {
        switch (event.getType()) {
            case MOUSE_CLICK -> {
                // Ignored: ComputerScreen also pushes MOUSE_CLICK_PX for the same click,
                // and this UI is pixel-coordinate based. Handling both fired handleClick
                // twice per click, causing the View toggle to cycle by 2 (e.g. GRID was
                // unreachable / appeared stuck).
            }
            case MOUSE_CLICK_PX -> {
                int px = event.getInt(1);
                int py = event.getInt(2);
                handleClick(px, py);
            }
            case MOUSE_DRAG -> {
                if (activeTab == 4 && draggingDisplayDivider) {
                    int py = event.getInt(2) * PixelBuffer.CELL_H;
                    dragDisplayDividerTo(py);
                }
            }
            case MOUSE_DRAG_PX -> {
                if (activeTab == 4 && draggingDisplayDivider) {
                    int py = event.getInt(2);
                    dragDisplayDividerTo(py);
                }
            }
            case MOUSE_UP -> {
                if (draggingDisplayDivider) {
                    draggingDisplayDivider = false;
                    saveSettings();
                }
            }
            case MOUSE_SCROLL -> {
                int dir = event.getInt(0);
                if (activeTab == 0) {
                    autoScrollDense = false; // manual scroll cancels auto-scroll
                    scrollItem = Math.max(0, scrollItem - dir * storageScrollStep());
                    int max = Math.max(0, storageTotalRows() - storageVisibleSlots());
                    scrollItem = Math.min(scrollItem, max);
                } else if (activeTab == 1) {
                    scrollFluid = Math.max(0, scrollFluid - dir);
                    int max = Math.max(0, fluids.size() - VISIBLE_ROWS);
                    scrollFluid = Math.min(scrollFluid, max);
                } else if (activeTab == 4) {
                    int step = (dir < 0 ? -1 : 1);
                    int py = event.getInt(2);
                    // MOUSE_SCROLL uses cell coordinates in this UI path; convert to pixel Y.
                    if (py >= 0 && py <= PixelBuffer.TEXT_ROWS + 1) py *= PixelBuffer.CELL_H;
                    if (py >= colorSectionY()) {
                        int maxColorScroll = maxColorSettingsScroll();
                        colorSettingsScroll = Math.max(0, Math.min(maxColorScroll, colorSettingsScroll + step));
                    } else {
                        scrollMonitor = Math.max(0, scrollMonitor + step);
                        int max = Math.max(0, mirrorMonitors.size() - displayVisibleRows());
                        scrollMonitor = Math.min(scrollMonitor, max);
                    }
                }
            }
            case KEY -> {
                int key = event.getInt(0);
                if (showMirrorDialog) {
                    if (key == 256 || key == 1) showMirrorDialog = false;
                } else if (showExportDialog) {
                    if (key == 259 || key == 14) {
                        if (!exportQty.isEmpty()) exportQty.deleteCharAt(exportQty.length() - 1);
                    } else if (key == 256 || key == 1) {
                        showExportDialog = false;
                    }
                } else if (selectedItem != null && activeTab == 0) {
                    // Threshold editing mode — backspace removes last digit
                    if (key == 259 || key == 14) {
                        if (!thresholdBuffer.isEmpty()) {
                            thresholdBuffer.deleteCharAt(thresholdBuffer.length() - 1);
                            if (thresholdBuffer.isEmpty()) {
                                itemThresholds.remove(selectedItem.name());
                            } else {
                                try { itemThresholds.put(selectedItem.name(), Long.parseLong(thresholdBuffer.toString())); } catch (Exception ignored) {}
                            }
                            saveSettings();
                        }
                    } else if (key == 256 || key == 1) { // Esc — deselect
                        selectedItem = null;
                        thresholdBuffer.setLength(0);
                    }
                } else {
                    if (key == 259 || key == 14) {
                        if (!searchBuffer.isEmpty()) {
                            searchBuffer.deleteCharAt(searchBuffer.length() - 1);
                            scrollItem = 0;
                        }
                    } else if (key == 256 || key == 1) {
                        if (!searchBuffer.isEmpty()) {
                            searchBuffer.setLength(0);
                        } else {
                            selectedItem = null;
                        }
                        scrollItem = 0;
                    }
                }
            }
            case CHAR -> {
                char c = event.getString(0).charAt(0);
                if (showExportDialog) {
                    if (Character.isDigit(c) && exportQty.length() < 9) exportQty.append(c);
                } else if (selectedItem != null && activeTab == 0) {
                    // Route digits to threshold field for selected item
                    if (Character.isDigit(c) && thresholdBuffer.length() < 9) {
                        thresholdBuffer.append(c);
                        try { itemThresholds.put(selectedItem.name(), Long.parseLong(thresholdBuffer.toString())); } catch (Exception ignored) {}
                        saveSettings();
                    }
                } else if (activeTab == 0 || activeTab == 1) {
                    searchBuffer.append(c);
                    scrollItem = 0;
                }
            }
            default -> {}
        }
    }

    private void handleClick(int px, int py) {
        // Dialog routing — only one dialog open at a time
        if (showMirrorDialog) { handleMirrorDialogClick(px, py); return; }
        if (showExportDialog) { handleExportDialogClick(px, py); return; }
        if (activeTab == 0 && py >= HEADER_H + TAB_H && py < HEADER_H + TAB_H + SEARCH_H) {
            int toggleX = W - STORAGE_VIEW_BTN_W - 4;
            int trashX = toggleX - STORAGE_TRASH_BTN_W - 4;
            if (px >= trashX && px < trashX + STORAGE_TRASH_BTN_W && canDeleteSelectedItem()) {
                deleteSelectedZeroItem();
                return;
            }
            if (px >= toggleX && px < toggleX + STORAGE_VIEW_BTN_W) {
                storageView = storageView.next();
                scrollItem = 0;
                autoScrollDense = false;
                saveSettings();
                return;
            }
            // Auto-scroll button (dense mode only)
            if (storageView == StorageViewMode.DENSE) {
                int autoX = trashX - STORAGE_AUTO_BTN_W - 4;
                if (px >= autoX && px < autoX + STORAGE_AUTO_BTN_W) {
                    autoScrollDense = !autoScrollDense;
                    autoScrollCounter = 0;
                    if (autoScrollDense) scrollItem = 0;
                    return;
                }
            }
        }
        // Tab bar
        if (py >= HEADER_H && py < HEADER_H + TAB_H) {
            int tab = px / TAB_W;
            if (tab >= 0 && tab < TAB_NAMES.length) {
                activeTab = tab;
                scrollItem = 0;
                scrollFluid = 0;
                scrollMonitor = 0;
                selectedItem = null;
                if (tab == 4) refreshMonitors(); // scan on every entry
            }
            return;
        }
        // Footer buttons
        if (py >= H - FOOTER_H) {
            // Export button (only shown when item selected on Storage tab)
            int exportBtnW = 110;
            boolean itemSelected = (selectedItem != null && activeTab == 0);
            int exportBtnX = W - exportBtnW - 4;
            if (itemSelected && px >= exportBtnX && meNode != null) {
                openExportDialog();
            }
            return;
        }
        // Display tab: monitor row click
        if (activeTab == 4) {
            handleDisplayTabClick(px, py);
            return;
        }
        // Storage tab: item row click
        if (activeTab == 0 && py >= storageListTop()) {
            handleStorageClick(px, py);
        }
    }

    // ── BT AE2 message parser ─────────────────────────────────────────────

    /**
     * Handles incoming ae2:* messages from a Universal Peripheral bridge.
     * Format examples:
     * <pre>
     *   ae2:energy:{...}
     *   ae2:items:[{...},...]
     *   ae2:fluids:[{...},...]
     *   ae2:crafting:[{...},...]
     *   ae2:nodes:42
     * </pre>
     */
    private void handleAe2BtMessage(String msg) {
        if (!msg.startsWith("ae2:")) return;
        btLastReceived = os.getTickCount();
        btSource = (meNode == null); // only adopt BT data when not directly connected
        if (!btSource) return;
        statusMsg = null;

        if (msg.startsWith("ae2:nodes:")) {
            try { nodeCount = Integer.parseInt(msg.substring("ae2:nodes:".length()).trim()); } catch (Exception ignored) {}
            return;
        }
        if (msg.startsWith("ae2:energy:")) {
            energy = parseEnergy(msg.substring("ae2:energy:".length()));
            return;
        }
        if (msg.startsWith("ae2:items:")) {
            items = parseItems(msg.substring("ae2:items:".length()));
            if (mergeKnownItems(items) && settingsLoaded && settingsHydrated) saveSettings();
            items.sort(Comparator.comparingLong(AEItemEntry::count).reversed().thenComparing(AEItemEntry::displayName, String.CASE_INSENSITIVE_ORDER));
            updateItemDeltas(items);
            return;
        }
        if (msg.startsWith("ae2:fluids:")) {
            fluids = parseFluids(msg.substring("ae2:fluids:".length()));
            fluids.sort(Comparator.comparingLong(AEFluidEntry::amountMb).reversed());
            return;
        }
        if (msg.startsWith("ae2:crafting:")) {
            craftingJobs = parseCrafting(msg.substring("ae2:crafting:".length()));
        }
    }

    // ── Minimal JSON parsers (hand-rolled, no external deps) ─────────────

    private static AEEnergyInfo parseEnergy(String json) {
        try {
            double stored  = jsonDouble(json, "stored");
            double cap     = jsonDouble(json, "cap");
            double usage   = jsonDouble(json, "usage");
            double inject  = jsonDouble(json, "inject");
            boolean on     = json.contains("\"on\":true");
            return new AEEnergyInfo(stored, cap, usage, inject, on);
        } catch (Exception e) { return null; }
    }

    private static List<AEItemEntry> parseItems(String json) {
        List<AEItemEntry> out = new ArrayList<>();
        for (String obj : splitJsonArray(json)) {
            try {
                String name  = jsonStr(obj, "n");
                String disp  = jsonStr(obj, "d");
                long   count = jsonLong(obj, "c");
                boolean craft = obj.contains("\"craft\":true");
                out.add(new AEItemEntry(name, disp, count, craft));
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static List<AEFluidEntry> parseFluids(String json) {
        List<AEFluidEntry> out = new ArrayList<>();
        for (String obj : splitJsonArray(json)) {
            try {
                String name  = jsonStr(obj, "n");
                long   count = jsonLong(obj, "c");
                out.add(new AEFluidEntry(name, count));
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static List<AECraftingJob> parseCrafting(String json) {
        List<AECraftingJob> out = new ArrayList<>();
        for (String obj : splitJsonArray(json)) {
            try {
                String item  = jsonStr(obj, "item");
                String cpu   = jsonStr(obj, "cpu");
                long   done  = jsonLong(obj, "done");
                long   total = jsonLong(obj, "total");
                long   ns    = jsonLong(obj, "ns");
                out.add(new AECraftingJob(item, cpu, done, total, ns));
            } catch (Exception ignored) {}
        }
        return out;
    }

    /** Split a JSON array string like [{...},{...}] into individual object strings. */
    private static List<String> splitJsonArray(String json) {
        List<String> parts = new ArrayList<>();
        int depth = 0, start = -1;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') { if (depth++ == 0) start = i; }
            else if (c == '}') { if (--depth == 0 && start >= 0) parts.add(json.substring(start, i + 1)); }
        }
        return parts;
    }

    private static double jsonDouble(String json, String key) {
        String k = "\"" + key + "\":";
        int idx = json.indexOf(k);
        if (idx < 0) return 0;
        int start = idx + k.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.' || json.charAt(end) == '-')) end++;
        return Double.parseDouble(json.substring(start, end));
    }

    private static long jsonLong(String json, String key) {
        String k = "\"" + key + "\":";
        int idx = json.indexOf(k);
        if (idx < 0) return 0;
        int start = idx + k.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        return Long.parseLong(json.substring(start, end));
    }

    private static String jsonStr(String json, String key) {
        String k = "\"" + key + "\":";
        int idx = json.indexOf(k);
        if (idx < 0) return "";
        int open = json.indexOf('"', idx + k.length());
        if (open < 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = open + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) { sb.append(json.charAt(++i)); continue; }
            if (c == '"') break;
            sb.append(c);
        }
        return sb.toString();
    }

    /** Snapshot counts and compute signed deltas for the new item list. */
    private void updateItemDeltas(List<AEItemEntry> newItems) {
        for (AEItemEntry e : newItems) {
            Long prev = prevItemCounts.get(e.name());
            if (prev != null) {
                itemDeltas.put(e.name(), e.count() - prev);
            }
            prevItemCounts.put(e.name(), e.count());
        }
    }

    private boolean mergeKnownItems(List<AEItemEntry> liveItems) {
        boolean changed = false;
        java.util.HashSet<String> liveNames = new java.util.HashSet<>();
        for (AEItemEntry item : liveItems) {
            liveNames.add(item.name());
            AEItemEntry prev = knownItems.put(item.name(), new AEItemEntry(item.name(), item.displayName(), 0, item.craftable()));
            if (prev == null || !prev.displayName().equals(item.displayName()) || prev.craftable() != item.craftable()) {
                changed = true;
            }
        }
        for (AEItemEntry item : knownItems.values()) {
            if (!liveNames.contains(item.name())) {
                liveItems.add(new AEItemEntry(item.name(), item.displayName(), 0, item.craftable()));
            }
        }
        return changed;
    }

    private AEItemEntry currentSelectedItem() {
        if (selectedItem == null) return null;
        for (AEItemEntry item : items) {
            if (item.name().equals(selectedItem.name())) return item;
        }
        return selectedItem;
    }

    private boolean canDeleteSelectedItem() {
        AEItemEntry current = currentSelectedItem();
        return activeTab == 0 && current != null && current.count() == 0;
    }

    private void deleteSelectedZeroItem() {
        AEItemEntry current = currentSelectedItem();
        if (current == null || current.count() != 0) return;
        knownItems.remove(current.name());
        itemThresholds.remove(current.name());
        itemDeltas.remove(current.name());
        prevItemCounts.remove(current.name());
        items.removeIf(item -> item.name().equals(current.name()));
        selectedItem = null;
        thresholdBuffer.setLength(0);
        scrollItem = Math.min(scrollItem, Math.max(0, storageTotalRows() - storageVisibleSlots()));
        saveSettings();
    }

    private List<AEItemEntry> filteredItems() {
        String q = searchBuffer.toString().toLowerCase();
        if (q.isEmpty()) return items;
        List<AEItemEntry> out = new ArrayList<>();
        for (AEItemEntry e : items) {
            if (e.displayName().toLowerCase().contains(q) || e.name().toLowerCase().contains(q))
                out.add(e);
        }
        return out;
    }

    /** Clip a compact-text label to fit a pixel width, appending '~' when trimmed. */
    private static String clipSmallText(String text, int maxPx) {
        int stride = PixelBuffer.SMALL_CHAR_ADVANCE;
        int maxChars = Math.max(0, maxPx / stride);
        if (maxChars <= 0) return "";
        if (text.length() <= maxChars) return text;
        if (maxChars == 1) return "~";
        return text.substring(0, maxChars - 1) + "~";
    }

    // ── Rendering ─────────────────────────────────────────────────────────

    /** Not used — this program renders pixel-mode only. */
    @Override
    public void render(TerminalBuffer buffer) {}

    @Override
    public void renderGraphics(PixelBuffer pb) {
        pb.fillRect(0, 0, W, H, cBg());
        drawHeader(pb);
        drawTabs(pb);
        if (statusMsg != null) {
            drawCenteredMessage(pb, statusMsg, C_WARN);
            drawFooter(pb);
            if (showExportDialog) drawExportDialog(pb);
            if (showMirrorDialog)  drawMirrorDialog(pb);
            return;
        }
        drawSearch(pb);
        switch (activeTab) {
            case 0 -> drawStorageTab(pb);
            case 1 -> drawFluidsTab(pb);
            case 2 -> drawCraftingTab(pb);
            case 3 -> drawNetworkTab(pb);
            case 4 -> drawDisplayTab(pb);
        }
        drawFooter(pb);
        if (showExportDialog) drawExportDialog(pb);
        if (showMirrorDialog)  drawMirrorDialog(pb);
    }

    // ── Header ────────────────────────────────────────────────────────────

    private void drawHeader(PixelBuffer pb) {
        pb.fillRect(0, 0, W, HEADER_H, cHeader());
        pb.drawRect(0, 0, W, HEADER_H, C_BORDER);
        pb.drawString(8, 4, "ME Network Dashboard", cTitle());

        // Energy indicator (top-right)
        if (energy != null) {
            double pct    = energy.capacity() > 0 ? energy.stored() / energy.capacity() : 0;
            int    barW   = 160;
            int    barX   = W - barW - 8;
            int    barY   = 4;
            int    barH   = 10;
            int    filled = (int)(barW * pct);
            int    col    = pct > 0.4 ? C_GOOD : pct > 0.15 ? C_WARN : C_BAD;
            pb.fillRect(barX, barY, barW, barH, 0xFF111122);
            pb.fillRect(barX, barY, filled, barH, col);
            pb.drawRect(barX, barY, barW, barH, C_BORDER);
            String label = String.format("%.0f / %.0f AE", energy.stored(), energy.capacity());
            pb.drawString(barX + 2, barY + 1, label, cTitle());

            // Powered dot
            int dotCol = energy.powered() ? C_GOOD : C_BAD;
            pb.fillRect(W - barW - 20, barY + 2, 6, 6, dotCol);
        } else if (meNode == null) {
            pb.drawString(W - 120, 4, "OFFLINE", C_BAD);
        }
    }

    // ── Tabs ──────────────────────────────────────────────────────────────

    private void drawTabs(PixelBuffer pb) {
        int y = HEADER_H;
        for (int i = 0; i < TAB_NAMES.length; i++) {
            int x   = i * TAB_W;
            int col = (i == activeTab) ? C_TAB_ACTIVE : C_TAB_IDLE;
            pb.fillRect(x, y, TAB_W, TAB_H, col);
            pb.drawRect(x, y, TAB_W, TAB_H, C_BORDER);
            pb.drawStringCentered(x, TAB_W, y + 4, TAB_NAMES[i], i == activeTab ? cTitle() : C_DIM);
        }
    }

    // ── Search bar ────────────────────────────────────────────────────────

    private void drawSearch(PixelBuffer pb) {
        int y = HEADER_H + TAB_H;
        pb.fillRect(0, y, W, SEARCH_H, C_SEARCH_BG);
        pb.drawRect(0, y, W, SEARCH_H, C_BORDER);
        int toggleX = W - STORAGE_VIEW_BTN_W - 4;
        int trashX = toggleX - STORAGE_TRASH_BTN_W - 4;
        if (activeTab == 0 && selectedItem != null) {
            String name = selectedItem.displayName();
            if (name.length() > 28) name = name.substring(0, 27) + "~";
            pb.drawString(4, y + 2, "Min stock \"" + name + "\": type digits  \u2022  Esc = deselect", C_DIM);
        } else if (activeTab == 0 || activeTab == 1) {
            String q = searchBuffer.toString();
              pb.drawString(4, y + 2, "Search: " + q + "_", cText());
        } else {
            pb.drawString(4, y + 2, "ME Network Monitor", C_DIM);
        }
        if (activeTab == 0) {
            boolean canTrash = canDeleteSelectedItem();
            pb.fillRect(trashX, y + 1, STORAGE_TRASH_BTN_W, SEARCH_H - 2, canTrash ? 0xFF3A1010 : 0xFF1A1A22);
            pb.drawRect(trashX, y + 1, STORAGE_TRASH_BTN_W, SEARCH_H - 2, canTrash ? C_BAD : C_DIM);
            pb.drawStringCentered(trashX, STORAGE_TRASH_BTN_W, y + 3, "Trash", canTrash ? C_BAD : C_DIM);

            pb.fillRect(toggleX, y + 1, STORAGE_VIEW_BTN_W, SEARCH_H - 2, 0xFF102246);
            pb.drawRect(toggleX, y + 1, STORAGE_VIEW_BTN_W, SEARCH_H - 2, C_DLG_BORDER);
            pb.drawStringCentered(toggleX, STORAGE_VIEW_BTN_W, y + 3, "View: " + storageView.label(), C_TITLE);

            // Auto-scroll button (dense only)
            if (storageView == StorageViewMode.DENSE) {
                int autoX = trashX - STORAGE_AUTO_BTN_W - 4;
                int autoBg   = autoScrollDense ? 0xFF103010 : 0xFF1A1A22;
                int autoBord = autoScrollDense ? 0xFF33CC33 : C_DIM;
                int autoFg   = autoScrollDense ? 0xFF88FF88 : C_DIM;
                pb.fillRect(autoX, y + 1, STORAGE_AUTO_BTN_W, SEARCH_H - 2, autoBg);
                pb.drawRect(autoX, y + 1, STORAGE_AUTO_BTN_W, SEARCH_H - 2, autoBord);
                pb.drawStringCentered(autoX, STORAGE_AUTO_BTN_W, y + 3, autoScrollDense ? "# Stop" : "> Auto", autoFg);
            }
        }
    }

    // ── Centred message ────────────────────────────────────────────────────

    private void drawCenteredMessage(PixelBuffer pb, String msg, int color) {
        int midY = HEADER_H + TAB_H + H / 3;
        pb.drawStringCentered(0, W, midY, msg, color);
    }

    // ── Storage tab ───────────────────────────────────────────────────────

    private void drawStorageTab(PixelBuffer pb) {
        switch (storageView) {
            case NORMAL -> drawStorageTabNormal(pb);
            case DENSE -> drawStorageTabDense(pb);
            case GRID -> drawStorageTabGrid(pb);
        }
    }

    private void drawStorageTabNormal(PixelBuffer pb) {
        List<Object> dlist = buildDisplayList();
        int y = CONTENT_Y;

        pb.fillRect(0, y, W, ROW_H, C_PANEL);
        pb.drawString(10, y + 6, "Item", C_DIM);
        pb.drawStringRight(W - 210, y + 6, "Flow/2s", C_DIM);
        pb.drawStringRight(W - 140, y + 6, "Count", C_DIM);
        pb.drawString(W - 136, y + 6, "Min", C_DIM);
        pb.drawStringRight(W - 4, y + 6, "Craft", C_DIM);
        y += ROW_H;
        pb.drawHLine(0, W, y, C_BORDER);

        int visEnd = Math.min(dlist.size(), scrollItem + storageVisibleSlots());
        int itemColIdx = 0;
        for (int i = scrollItem; i < visEnd; i++) {
            Object row = dlist.get(i);
            int rowY = y + (i - scrollItem) * ROW_H;
            if (row instanceof String catName) {
                pb.fillRect(0, rowY, W, ROW_H, 0xFF0D1A3A);
                pb.drawHLine(0, W, rowY, C_BORDER);
                pb.drawHLine(0, W, rowY + ROW_H - 1, C_BORDER);
                pb.fillRect(0, rowY, 4, ROW_H, C_BORDER);
                pb.drawString(10, rowY + 6, catName, C_TITLE);
                itemColIdx = 0;
                continue;
            }
            drawStorageItemRowNormal(pb, (AEItemEntry) row, rowY, itemColIdx++);
        }

        drawScrollbar(pb, CONTENT_Y + ROW_H, CONTENT_H - ROW_H, dlist.size(), storageVisibleSlots(), scrollItem);
        if (dlist.isEmpty()) {
            String msg = items.isEmpty() ? "ME Network is empty." : "No items match search.";
            pb.drawStringCentered(0, W, CONTENT_Y + CONTENT_H / 2, msg, C_DIM);
        }
    }

    private void drawStorageItemRowNormal(PixelBuffer pb, AEItemEntry e, int rowY, int itemColIdx) {
        boolean isSelected = (selectedItem != null && selectedItem.name().equals(e.name()));
            int rowBg = isSelected ? C_SELECTED : (itemColIdx % 2 == 0) ? cRowEven() : cRowOdd();
        pb.fillRect(0, rowY, W, ROW_H, rowBg);
        if (isSelected) pb.drawRect(0, rowY, W, ROW_H, C_DLG_BORDER);

        Long threshold = itemThresholds.get(e.name());
        int stripeCol = e.count() == 0 ? C_BAD
                : threshold != null && e.count() < threshold ? C_WARN
                : threshold != null ? C_GOOD : 0xFF223355;
        pb.fillRect(0, rowY, 4, ROW_H, stripeCol);

        String label = e.displayName();
        if (label.length() > 38) label = label.substring(0, 37) + "~";
            pb.drawString(10, rowY + 6, label, cText());

        Long delta = itemDeltas.get(e.name());
        if (delta != null && delta != 0) {
            boolean inflow = delta > 0;
            pb.drawStringRight(W - 210, rowY + 6, (inflow ? "+" : "") + formatCount(delta), inflow ? C_GOOD : C_BAD);
        }

        pb.drawStringRight(W - 140, rowY + 6, formatCount(e.count()), C_TITLE);

        int boxX = W - 136, boxW = 62;
        pb.fillRect(boxX, rowY + 3, boxW, ROW_H - 6, isSelected ? 0xFF0D1E3A : 0xFF0A1628);
        pb.drawRect(boxX, rowY + 3, boxW, ROW_H - 6, isSelected ? C_DLG_BORDER : 0xFF1E3060);
        String threshStr;
        int threshColor;
        if (isSelected) {
            threshStr = thresholdBuffer.toString() + "|";
            threshColor = C_TITLE;
        } else if (threshold != null) {
            threshStr = formatCount(threshold);
            threshColor = stripeCol;
        } else {
            threshStr = "--";
            threshColor = C_DIM;
        }
        pb.drawStringCentered(boxX, boxW, rowY + 5, threshStr, threshColor);

        if (e.craftable()) {
            pb.fillRect(W - 70, rowY + 3, 66, ROW_H - 6, C_CRAFT_BADGE);
            pb.drawStringCentered(W - 70, 66, rowY + 5, "CRAFT", 0xFFFFFFFF);
        }
    }

    private void drawStorageTabDense(PixelBuffer pb) {
        List<Object> dlist = buildDisplayList();
        int y = CONTENT_Y;

        pb.fillRect(0, y, W, DENSE_ROW_H, C_PANEL);
        pb.drawSmallString(8, y + 1, "Item", C_DIM);
        pb.drawSmallStringRight(W - 18, y + 1, "Count", C_DIM);
        pb.drawSmallStringRight(W - 4, y + 1, "C", C_DIM);
        y += DENSE_ROW_H;
        pb.drawHLine(0, W, y, C_BORDER);

        int visEnd = Math.min(dlist.size(), scrollItem + storageVisibleSlots());
        int itemColIdx = 0;
        for (int i = scrollItem; i < visEnd; i++) {
            Object row = dlist.get(i);
            int rowY = y + (i - scrollItem) * DENSE_ROW_H;
            if (row instanceof String catName) {
                pb.fillRect(0, rowY, W, DENSE_ROW_H, 0xFF0A1734);
                pb.fillRect(0, rowY, 3, DENSE_ROW_H, C_BORDER);
                pb.drawSmallString(6, rowY + 1, catName, C_TITLE);
                itemColIdx = 0;
                continue;
            }

            AEItemEntry e = (AEItemEntry) row;
            boolean isSelected = (selectedItem != null && selectedItem.name().equals(e.name()));
                int rowBg = isSelected ? C_SELECTED : (itemColIdx % 2 == 0) ? cRowEven() : cRowOdd();
            pb.fillRect(0, rowY, W, DENSE_ROW_H, rowBg);
            if (isSelected) pb.drawRect(0, rowY, W, DENSE_ROW_H, C_DLG_BORDER);
            itemColIdx++;

            Long threshold = itemThresholds.get(e.name());
            int stripeCol = e.count() == 0 ? C_BAD
                    : threshold != null && e.count() < threshold ? C_WARN
                    : threshold != null ? C_GOOD : 0xFF223355;
            pb.fillRect(0, rowY, 3, DENSE_ROW_H, stripeCol);

            String label = clipSmallText(e.displayName(), (W - 28) - 6);
            pb.drawSmallString(6, rowY + 1, label, cText());
            pb.drawSmallStringRight(W - 18, rowY + 1, formatCount(e.count()), C_TITLE);
            if (e.craftable()) pb.drawSmallStringRight(W - 4, rowY + 1, "C", 0xFFFFFFFF);
        }

        drawScrollbar(pb, CONTENT_Y + DENSE_ROW_H, CONTENT_H - DENSE_ROW_H, dlist.size(), storageVisibleSlots(), scrollItem);
        if (dlist.isEmpty()) {
            String msg = items.isEmpty() ? "ME Network is empty." : "No items match search.";
            pb.drawStringCentered(0, W, CONTENT_Y + CONTENT_H / 2, msg, C_DIM);
        }
    }

    private void drawStorageTabGrid(PixelBuffer pb) {
        List<AEItemEntry> itemsOnly = buildGroupedItems();
        int y = CONTENT_Y;
        int cellW = W / GRID_COLS;

        pb.fillRect(0, y, W, storageHeaderHeight(), C_PANEL);
        pb.drawSmallString(8, y + 3, "Grid View", C_DIM);
        pb.drawSmallStringRight(W - 8, y + 3, GRID_COLS + " cols  compact", C_DIM);
        y += storageHeaderHeight();
        pb.drawHLine(0, W, y, C_BORDER);

        int end = Math.min(itemsOnly.size(), scrollItem + storageVisibleSlots());
        for (int i = scrollItem; i < end; i++) {
            AEItemEntry e = itemsOnly.get(i);
            int rel = i - scrollItem;
            int col = rel % GRID_COLS;
            int row = rel / GRID_COLS;
            int x = col * cellW;
            int rowY = y + row * GRID_CELL_H;
            boolean isSelected = selectedItem != null && selectedItem.name().equals(e.name());
            int rowBg = isSelected ? C_SELECTED : ((row + col) % 2 == 0 ? cRowEven() : cRowOdd());
            pb.fillRect(x, rowY, cellW - 1, GRID_CELL_H, rowBg);
            if (isSelected) pb.drawRect(x, rowY, cellW - 1, GRID_CELL_H, C_DLG_BORDER);

            Long threshold = itemThresholds.get(e.name());
            int stripeCol = e.count() == 0 ? C_BAD
                    : threshold != null && e.count() < threshold ? C_WARN
                    : threshold != null ? C_GOOD : 0xFF223355;
            pb.fillRect(x, rowY, 3, GRID_CELL_H, stripeCol);

            String label = clipSmallText(e.displayName(), (cellW - 52) - 5);
            pb.drawSmallString(x + 5, rowY + 1, label, cText());
            pb.drawSmallStringRight(x + cellW - 12, rowY + 1, formatCount(e.count()), C_TITLE);
            if (e.craftable()) pb.drawSmallStringRight(x + cellW - 2, rowY + 1, "C", 0xFFFFFFFF);
        }

        int visibleRows = Math.max(1, (storageVisibleSlots() + GRID_COLS - 1) / GRID_COLS);
        int totalRows = Math.max(1, (itemsOnly.size() + GRID_COLS - 1) / GRID_COLS);
        drawScrollbar(pb, storageListTop(), CONTENT_H - storageHeaderHeight(), totalRows, visibleRows, scrollItem / GRID_COLS);
        if (itemsOnly.isEmpty()) {
            String msg = items.isEmpty() ? "ME Network is empty." : "No items match search.";
            pb.drawStringCentered(0, W, CONTENT_Y + CONTENT_H / 2, msg, C_DIM);
        }
    }

    // ── Fluids tab ────────────────────────────────────────────────────────

    private void drawFluidsTab(PixelBuffer pb) {
        int y = CONTENT_Y;

        pb.fillRect(0, y, W, ROW_H, C_PANEL);
        pb.drawString(4, y + 2, "Fluid", C_DIM);
        pb.drawStringRight(W - 4, y + 2, "Amount (mB)", C_DIM);
        y += ROW_H;
        pb.drawHLine(0, W, y, C_BORDER);

        int visStart = scrollFluid;
        int visEnd   = Math.min(fluids.size(), visStart + VISIBLE_ROWS - 1);
        for (int i = visStart; i < visEnd; i++) {
            AEFluidEntry e = fluids.get(i);
            int rowY       = y + (i - visStart) * ROW_H;
                int rowBg      = (i % 2 == 0) ? cRowEven() : cRowOdd();
            pb.fillRect(0, rowY, W, ROW_H, rowBg);

            // Fluid fill bar (proportion of max in list)
            long maxAmt = fluids.isEmpty() ? 1 : fluids.get(0).amountMb();
            int barW = maxAmt > 0 ? (int)((W - 150) * e.amountMb() / maxAmt) : 0;
            pb.fillRect(4, rowY + 2, barW, ROW_H - 4, 0xFF114466);

            // Fluid name (trim namespace prefix for readability)
            String name = trimNamespace(e.name());
            pb.drawString(8, rowY + 2, name, C_FLUID_BAR);

            // Amount right-aligned
            pb.drawStringRight(W - 4, rowY + 2, formatCount(e.amountMb()) + " mB", C_TITLE);
        }
        drawScrollbar(pb, CONTENT_Y + ROW_H, CONTENT_H - ROW_H, fluids.size(), VISIBLE_ROWS - 1, scrollFluid);
        if (fluids.isEmpty()) {
            pb.drawStringCentered(0, W, CONTENT_Y + CONTENT_H / 2, "No fluids in ME network.", C_DIM);
        }
    }

    // ── Crafting tab ──────────────────────────────────────────────────────

    private void drawCraftingTab(PixelBuffer pb) {
        int y = CONTENT_Y;

        if (craftingJobs.isEmpty()) {
            pb.drawStringCentered(0, W, y + CONTENT_H / 2, "No active crafting jobs.", C_DIM);
            return;
        }

        pb.fillRect(0, y, W, ROW_H, C_PANEL);
        pb.drawString(4,       y + 2, "Item",     C_DIM);
        pb.drawString(250,     y + 2, "CPU",      C_DIM);
        pb.drawString(380,     y + 2, "Progress", C_DIM);
        pb.drawStringRight(W - 4, y + 2, "Elapsed", C_DIM);
        y += ROW_H;
        pb.drawHLine(0, W, y, C_BORDER);

        int jobH = 30;
        for (int i = 0; i < craftingJobs.size() && y + jobH < H - FOOTER_H; i++) {
            AECraftingJob job = craftingJobs.get(i);
            int rowBg = (i % 2 == 0) ? C_PANEL : C_BG;
            pb.fillRect(0, y, W, jobH, rowBg);

            // Item name
            pb.drawString(4, y + 3, job.itemName(), C_TEXT);
            // CPU name
            pb.drawString(250, y + 3, job.cpuName(), C_DIM);

            // Progress bar
            double pct   = job.totalItems() > 0 ? (double) job.doneItems() / job.totalItems() : 0;
            int barX = 380, barW = 200, barH = 10;
            pb.fillRect(barX, y + 3, barW, barH, 0xFF111111);
            pb.fillRect(barX, y + 3, (int)(barW * pct), barH, C_PROGRESS);
            pb.drawRect(barX, y + 3, barW, barH, C_BORDER);
            String pctLabel = String.format("%.0f%%  %d/%d", pct * 100, job.doneItems(), job.totalItems());
            pb.drawString(barX + 2, y + 4, pctLabel, C_TITLE);

            // Elapsed time
            long secs = job.elapsedNanos() / 1_000_000_000L;
            String elapsed = secs < 60 ? secs + "s" : (secs / 60) + "m " + (secs % 60) + "s";
            pb.drawStringRight(W - 4, y + 3, elapsed, C_DIM);

            y += jobH;
        }
    }

    // ── Network tab ───────────────────────────────────────────────────────

    private void drawNetworkTab(PixelBuffer pb) {
        int y = CONTENT_Y + 8;

        // ── Energy section ───────────────────────────────────────────────
        pb.fillRect(8, y, W - 16, 80, C_PANEL);
        pb.drawRect(8, y, W - 16, 80, C_BORDER);
        pb.drawString(16, y + 4, "Energy", C_TITLE);

        if (energy != null) {
            double pct    = energy.capacity() > 0 ? energy.stored() / energy.capacity() : 0;
            int barX = 16, barY = y + 20, barW = W - 32, barH = 18;
            int filled    = (int)(barW * pct);
            int barCol    = pct > 0.4 ? C_ENERGY_BAR : pct > 0.15 ? C_WARN : C_BAD;
            pb.fillRect(barX, barY, barW, barH, 0xFF111122);
            pb.fillRect(barX, barY, filled, barH, barCol);
            pb.drawRect(barX, barY, barW, barH, C_BORDER);

            String stored = String.format("%.1f AE", energy.stored());
            String cap    = String.format("%.1f AE", energy.capacity());
            pb.drawStringCentered(barX, barW, barY + 2, stored + " / " + cap, C_TITLE);

            pb.drawString(16, y + 44, String.format("Avg Usage:    %.2f AE/t", energy.avgUsage()),    C_TEXT);
            pb.drawString(16, y + 56, String.format("Avg Injection: %.2f AE/t", energy.avgInjection()), C_TEXT);

            int powX = W - 120, powY = y + 4;
            String powLabel = energy.powered() ? "POWERED" : "OFFLINE";
            int    powCol   = energy.powered() ? C_GOOD : C_BAD;
            pb.fillRect(powX - 4, powY - 2, 100, 14, 0xFF111122);
            pb.drawString(powX, powY, powLabel, powCol);
        } else {
            pb.drawString(16, y + 28, "Energy data unavailable.", C_DIM);
        }

        // ── Network stats ────────────────────────────────────────────────
        y += 96;
        pb.fillRect(8, y, W / 2 - 12, 60, C_PANEL);
        pb.drawRect(8, y, W / 2 - 12, 60, C_BORDER);
        pb.drawString(16, y + 4, "Network", C_TITLE);
        pb.drawString(16, y + 20, "Grid Nodes: " + nodeCount,       C_TEXT);
        pb.drawString(16, y + 32, "Item Types: " + items.size(),    C_TEXT);
        pb.drawString(16, y + 44, "Fluid Types: " + fluids.size(),  C_TEXT);

        // ── Storage summary ───────────────────────────────────────────────
        int sx = W / 2 + 4;
        pb.fillRect(sx, y, W / 2 - 12, 60, C_PANEL);
        pb.drawRect(sx, y, W / 2 - 12, 60, C_BORDER);
        pb.drawString(sx + 8, y + 4, "Storage", C_TITLE);
        long totalItems = items.stream().mapToLong(AEItemEntry::count).sum();
        long totalFluids = fluids.stream().mapToLong(AEFluidEntry::amountMb).sum();
        pb.drawString(sx + 8, y + 20, "Total Items:  " + formatCount(totalItems), C_TEXT);
        pb.drawString(sx + 8, y + 32, "Total Fluids: " + formatCount(totalFluids) + " mB", C_TEXT);
        pb.drawString(sx + 8, y + 44, "Crafting Jobs: " + craftingJobs.size(),    C_TEXT);

        // ── Active crafting badge ─────────────────────────────────────────
        y += 72;
        if (!craftingJobs.isEmpty()) {
            pb.fillRect(8, y, W - 16, 14, 0xFF0A2A0A);
            pb.drawRect(8, y, W - 16, 14, C_GOOD);
            pb.drawStringCentered(8, W - 16, y + 2,
                craftingJobs.size() + " active crafting job(s) — see Crafting tab", C_GOOD);
        }
    }

    // ── Footer ────────────────────────────────────────────────────────────

    private void drawFooter(PixelBuffer pb) {
        int y = H - FOOTER_H;
        pb.fillRect(0, y, W, FOOTER_H, cHeader());
        pb.drawRect(0, y, W, FOOTER_H, C_BORDER);
        String info;
        if (meNode != null) {
            info = "Direct  |  " + items.size() + " item types  |  Refresh: 2s";
        } else if (btSource) {
            long staleness = os.getTickCount() - btLastReceived;
            info = "Wireless (BT ch9200)  |  " + items.size() + " item types  |  last: " + (staleness / 20) + "s ago";
        } else {
            info = "Disconnected";
        }
        pb.drawString(4, y + 3, info, meNode != null ? C_DIM : (btSource ? C_GOOD : C_BAD));

        // Mirror status indicator (right side of footer)
        if (activeMirrorId != null) {
            String activeLbl = mirrorMonitors.stream()
                    .filter(m -> m.deviceId().equals(activeMirrorId))
                    .map(m -> m.label().isEmpty() ? "monitor" : m.label())
                    .findFirst().orElse("monitor");
            pb.drawStringRight(W - 4, y + 3, "\u25CF Mirroring: " + activeLbl, C_GOOD);
        } else {
            pb.drawStringRight(W - 4, y + 3, "ME Dashboard", cTitle());
        }

        // Export button — shown when an item is selected on Storage tab
        boolean itemSelected = (selectedItem != null && activeTab == 0);
        if (itemSelected) {
            boolean canExport = (meNode != null);
            int exportBtnW = 110, btnH = FOOTER_H - 2;
            int exportBtnX = W - exportBtnW - 4;
            pb.fillRect(exportBtnX, y + 1, exportBtnW, btnH, canExport ? C_BTN_HOT : 0xFF1A1A22);
            pb.drawRect(exportBtnX, y + 1, exportBtnW, btnH, canExport ? C_DLG_BORDER : C_DIM);
            String exportLabel = canExport ? "[ Export... ]" : "[ No direct ]";
            pb.drawStringCentered(exportBtnX, exportBtnW, y + 2, exportLabel, canExport ? C_TITLE : C_DIM);
        }
    }

    // ── Scrollbar helper ──────────────────────────────────────────────────

    private void drawScrollbar(PixelBuffer pb, int y, int h, int total, int visible, int scroll) {
        if (total <= visible) return;
        int sbW  = 4;
        int sbX  = W - sbW - 1;
        pb.fillRect(sbX, y, sbW, h, 0xFF111122);
        float thumbH   = Math.max(16f, (float) visible / total * h);
        float thumbTop = (float) scroll / total * h;
        pb.fillRect(sbX, y + (int) thumbTop, sbW, (int) thumbH, C_BORDER);
    }

    // ── Util ──────────────────────────────────────────────────────────────

    private static String formatCount(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format("%.1fk", n / 1_000.0);
        return Long.toString(n);
    }

    private static String trimNamespace(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    /** Returns the namespace (mod id) portion of a registry name. */
    private static String namespace(String id) {
        int c = id.indexOf(':');
        return c >= 0 ? id.substring(0, c) : "minecraft";
    }

    /** Returns a friendly display name for a mod namespace. */
    private static String modDisplayName(String ns) {
        return ModList.get().getModContainerById(ns)
                .map(c -> c.getModInfo().getDisplayName())
                .orElseGet(() -> ns.substring(0, 1).toUpperCase() + ns.substring(1));
    }

    /**
     * Builds a flat display list for the Storage tab.
     * Each entry is either a {@code String} (category header) or an {@link AEItemEntry}.
     * Minecraft items appear first, then mods sorted alphabetically by display name.
     * Within each group items remain sorted by count descending.
     */
    private List<Object> buildDisplayList() {
        List<AEItemEntry> filtered = filteredItems();
        // Group by namespace preserving count-desc order within each group
        java.util.LinkedHashMap<String, List<AEItemEntry>> groups = new java.util.LinkedHashMap<>();
        for (AEItemEntry e : filtered) {
            String ns = namespace(e.name());
            groups.computeIfAbsent(ns, k -> new ArrayList<>()).add(e);
        }
        // Sort keys: minecraft first, rest by mod display name
        List<String> keys = new ArrayList<>(groups.keySet());
        keys.sort((a, b) -> {
            if (a.equals("minecraft")) return -1;
            if (b.equals("minecraft")) return  1;
            return modDisplayName(a).compareToIgnoreCase(modDisplayName(b));
        });
        List<Object> out = new ArrayList<>(filtered.size() + keys.size());
        for (String key : keys) {
            out.add(modDisplayName(key));
            out.addAll(groups.get(key));
        }
        return out;
    }

    // ── Mirror dialog ─────────────────────────────────────────────────────


    private List<AEItemEntry> buildGroupedItems() {
        List<AEItemEntry> out = new ArrayList<>();
        for (Object row : buildDisplayList()) {
            if (row instanceof AEItemEntry item) out.add(item);
        }
        return out;
    }

    private int storageHeaderHeight() {
        return switch (storageView) {
            case NORMAL -> ROW_H;
            case DENSE -> DENSE_ROW_H;
            case GRID -> 14;
        };
    }

    private int storageListTop() {
        return CONTENT_Y + storageHeaderHeight();
    }

    private int storageVisibleSlots() {
        return switch (storageView) {
            case NORMAL -> Math.max(1, CONTENT_H / ROW_H - 1);
            case DENSE -> Math.max(1, CONTENT_H / DENSE_ROW_H - 1);
            case GRID -> Math.max(1, ((CONTENT_H - storageHeaderHeight()) / GRID_CELL_H) * GRID_COLS);
        };
    }

    private int storageTotalRows() {
        return switch (storageView) {
            case NORMAL, DENSE -> buildDisplayList().size();
            case GRID -> buildGroupedItems().size();
        };
    }

    private int storageScrollStep() {
        return storageView == StorageViewMode.GRID ? GRID_COLS : 1;
    }

    private void applySelectedStorageItem(AEItemEntry clicked) {
        boolean wasSelected = clicked.name().equals(selectedItem != null ? selectedItem.name() : "");
        selectedItem = wasSelected ? null : clicked;
        thresholdBuffer.setLength(0);
        if (selectedItem != null) {
            Long t = itemThresholds.get(selectedItem.name());
            if (t != null) thresholdBuffer.append(t);
        }
    }

    private void handleStorageClick(int px, int py) {
        if (storageView == StorageViewMode.GRID) {
            List<AEItemEntry> itemsOnly = buildGroupedItems();
            int cellW = W / GRID_COLS;
            int row = (py - storageListTop()) / GRID_CELL_H;
            int col = Math.max(0, Math.min(GRID_COLS - 1, px / cellW));
            int idx = scrollItem + row * GRID_COLS + col;
            if (idx >= 0 && idx < itemsOnly.size()) applySelectedStorageItem(itemsOnly.get(idx));
            return;
        }

        List<Object> dlist = buildDisplayList();
        int rowH = storageView == StorageViewMode.DENSE ? DENSE_ROW_H : ROW_H;
        int rowIdx = (py - storageListTop()) / rowH + scrollItem;
        if (rowIdx >= 0 && rowIdx < dlist.size() && dlist.get(rowIdx) instanceof AEItemEntry clicked) {
            applySelectedStorageItem(clicked);
        }
    }

    private boolean loadSettings() {
        if (os == null || os.getFileSystem() == null) return false;
        String source = "persistent";
        String data = os.getPersistentValue(SETTINGS_PERSISTENT_KEY);
        if (data == null || data.isBlank()) {
            source = "disk";
            data = readSettingsFromDisk();
        }
        if (data == null || data.isBlank()) {
            source = "vfs-hidden";
            data = os.getFileSystem().readFile(SETTINGS_FILE);
        }
        if (data == null || data.isBlank()) {
            source = "vfs-visible";
            data = os.getFileSystem().readFile(SETTINGS_FILE_FALLBACK);
        }
        if (data == null || data.isBlank()) {
            if (!settingsMissingLogged) {
                ByteBlock.LOGGER.warn("[ME Dashboard] loadSettings: no data source found yet; comp={} (will retry)", os.getComputerId());
                settingsMissingLogged = true;
            }
            settingsHydrated = false;
            return false;
        }

        itemThresholds.clear();
        knownItems.clear();
        for (String line : data.split("\\R")) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\\t", 4);
            if (parts.length < 2) continue;

            if ("view".equals(parts[0])) {
                try {
                    storageView = StorageViewMode.valueOf(parts[1]);
                } catch (IllegalArgumentException ignored) {}
                continue;
            }

                if ("color".equals(parts[0]) && parts.length == 3) {
                    try {
                        int idx = Integer.parseInt(parts[2]);
                        switch (parts[1]) {
                            case "bg"    -> idxBg   = Math.max(0, Math.min(BG_OPTIONS.length - 1, idx));
                            case "rowE"  -> idxRowE = Math.max(0, Math.min(ROW_E_OPTIONS.length - 1, idx));
                            case "rowO"  -> idxRowO = Math.max(0, Math.min(ROW_O_OPTIONS.length - 1, idx));
                            case "text"  -> idxText = Math.max(0, Math.min(TEXT_OPTIONS.length - 1, idx));
                            case "header"-> idxHeader = Math.max(0, Math.min(HEADER_OPTIONS.length - 1, idx));
                            case "title" -> idxTitle = Math.max(0, Math.min(TITLE_OPTIONS.length - 1, idx));
                            case "rows"  -> colorRowsVisible = Math.max(1, Math.min(COLOR_MAX_VISIBLE_ROWS, idx));
                        }
                    } catch (NumberFormatException ignored) {}
                    continue;
                }

            if ("threshold".equals(parts[0]) && parts.length == 3) {
                try {
                    itemThresholds.put(parts[1], Long.parseLong(parts[2]));
                } catch (NumberFormatException ignored) {}
                continue;
            }

            if ("known".equals(parts[0]) && parts.length == 4) {
                boolean craftable = "1".equals(parts[3]);
                knownItems.put(parts[1], new AEItemEntry(parts[1], parts[2], 0, craftable));
            }
        }

        // Keep compact NBT settings in sync even when loading legacy/full formats.
        os.setPersistentValue(SETTINGS_PERSISTENT_KEY, serializeSettings(false));
        writeSettingsToDisk(data);
        settingsMissingLogged = false;
        settingsHydrated = true;
        return true;
    }

    private void saveSettings() {
        if (os == null || os.getFileSystem() == null) return;
        if (!settingsLoaded) settingsLoaded = true;
        String compactData = serializeSettings(false);
        String fullData = serializeSettings(true);
        // Write both hidden and visible filenames so persistence survives
        // environments that treat dotfiles differently.
        os.setPersistentValue(SETTINGS_PERSISTENT_KEY, compactData);
        os.getFileSystem().writeFile(SETTINGS_FILE, fullData);
        os.getFileSystem().writeFile(SETTINGS_FILE_FALLBACK, fullData);
        writeSettingsToDisk(fullData);
        Level level = os.getLevel();
        boolean sentPacket = false;
        if (level != null && level.isClientSide() && os.getBlockPos() != null) {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.apocscode.byteblock.network.SaveMeDashboardSettingsPayload(
                    os.getBlockPos(), compactData, fullData));
            sentPacket = true;
        }
        settingsHydrated = true;
    }

    @Override
    public void shutdown() {
        if (settingsLoaded) {
            saveSettings();
        }
        super.shutdown();
    }

    private Path settingsDiskPath() {
        if (os == null) return null;
        Level lvl = os.getLevel();
        if (!(lvl instanceof ServerLevel sl)) return null;
        return sl.getServer().getWorldPath(LevelResource.ROOT)
                .resolve("byteblock")
                .resolve("computer")
                .resolve(os.getComputerId().toString())
                .resolve(SETTINGS_DISK_FILE);
    }

    private String readSettingsFromDisk() {
        Path p = settingsDiskPath();
        if (p == null || !Files.exists(p)) return null;
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return null;
        }
    }

    private void writeSettingsToDisk(String data) {
        if (data == null) return;
        Path p = settingsDiskPath();
        if (p == null) return;
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, data, StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
    }

    // ── Display tab ───────────────────────────────────────────────────────

    private static final int DISP_ROW_H = 22;
    private static final int DISP_BTN_W = 80;
    private static final int DISP_SCROLL_CTRL_H = 16;

    // Color settings panel (fixed at bottom of Display tab content area)
    private static final int COLOR_ROW_H      = 16;
    private static final int COLOR_TOTAL_ROWS = 6;
    private static final int COLOR_MAX_VISIBLE_ROWS = 8;
    private static final int COLOR_DEFAULT_VISIBLE_ROWS = 2;
    private static final int CS_LABEL_W       = 106;
    private static final int CS_SWATCH_X      = CS_LABEL_W + 8;
    private static final int CS_SWATCH_W      = 20;
    private static final int CS_PREV_X        = CS_SWATCH_X + CS_SWATCH_W + 4;
    private static final int CS_BTN_W         = 18;
    private static final int CS_NEXT_X        = CS_PREV_X + CS_BTN_W + 2;
    private static final int CS_NAME_X        = CS_NEXT_X + CS_BTN_W + 8;
    private static final int CS_APPLY_W       = 72;
    private static final int CS_APPLY_H       = 14;
    private static final int CS_APPLY_X       = W - CS_APPLY_W - 8;
    private static final int CS_RESET_W       = 72;
    private static final int CS_RESET_H       = 14;
    private static final int CS_RESET_X       = CS_APPLY_X - CS_RESET_W - 6;

    /** Scans BT for monitors and refreshes mirrorMonitors list. */
    private void refreshMonitors() {
        mirrorMonitors.clear();
        Level lvl = os.getLevel();
        BlockPos myPos = os.getBlockPos();
        if (lvl == null || myPos == null) return;
        for (BluetoothNetwork.DeviceEntry d : BluetoothNetwork.getDevicesInRange(lvl, myPos, 64)) {
            if (d.type() != BluetoothNetwork.DeviceType.MONITOR) continue;
            String lbl = "";
            if (lvl.getBlockEntity(d.pos()) instanceof MonitorBlockEntity mbe) {
                String ml = mbe.getLabel();
                lbl = ml != null ? ml : "";
            }
            double dist = Math.sqrt(myPos.distSqr(d.pos()));
            mirrorMonitors.add(new MonitorEntry(d.deviceId(), d.pos(), lbl, dist));
        }
        mirrorMonitors.sort(Comparator.comparingDouble(MonitorEntry::dist));
    }

    private int displayListStartY() {
        int y = CONTENT_Y + 20 + 2;
        if (activeMirrorId != null) y += 20;
        y += ROW_H + 1;
        return y;
    }

    private int displayVisibleRows() {
        return Math.max(1, (colorSectionY() - DISP_SCROLL_CTRL_H - 2 - displayListStartY()) / DISP_ROW_H);
    }

    private int maxColorSettingsScroll() {
        return Math.max(0, COLOR_TOTAL_ROWS - Math.min(COLOR_TOTAL_ROWS, colorVisibleRows()));
    }

    private int colorVisibleRows() {
        return Math.max(1, Math.min(COLOR_MAX_VISIBLE_ROWS, colorRowsVisible));
    }

    private void setColorVisibleRows(int rows, boolean persist) {
        int clamped = Math.max(1, Math.min(COLOR_MAX_VISIBLE_ROWS, rows));
        if (clamped == colorRowsVisible) return;
        colorRowsVisible = clamped;
        colorSettingsScroll = Math.min(colorSettingsScroll, maxColorSettingsScroll());
        if (persist) saveSettings();
    }

    private int minColorSectionY() {
        int minListRows = 2;
        return displayListStartY() + (minListRows * DISP_ROW_H) + DISP_SCROLL_CTRL_H + 4;
    }

    private int maxColorSectionY() {
        int minPanelH = 2 + 14 + COLOR_ROW_H + 18 + 4;
        return H - FOOTER_H - minPanelH;
    }

    private void dragDisplayDividerTo(int dividerY) {
        int y = Math.max(minColorSectionY(), Math.min(maxColorSectionY(), dividerY));
        int fixed = 2 + 14 + 18 + 4;
        int rows = Math.round((H - FOOTER_H - y - fixed) / (float) COLOR_ROW_H);
        setColorVisibleRows(rows, false);
    }

    private int colorSectionH() {
        return 2 + 14 + colorVisibleRows() * COLOR_ROW_H + 18 + 4;
    }

    private int colorSectionY() {
        return H - FOOTER_H - colorSectionH();
    }

    private void drawDisplayTab(PixelBuffer pb) {
        int y = CONTENT_Y + 4;

        // Title + refresh hint
        pb.fillRect(0, CONTENT_Y, W, 18, C_PANEL);
        pb.drawString(8, CONTENT_Y + 4, "Monitor Output", C_TITLE);
        pb.drawString(180, CONTENT_Y + 4, "Click a monitor row to mirror this terminal to it.", C_DIM);
        pb.drawStringRight(W - 8, CONTENT_Y + 4, "[ Refresh ]", C_BTN_HOT);
        y = CONTENT_Y + 20;
        pb.drawHLine(0, W, y, C_BORDER);
        y += 2;

        // Active mirror banner
        if (activeMirrorId != null) {
            String lbl = mirrorMonitors.stream()
                    .filter(m -> m.deviceId().equals(activeMirrorId))
                    .map(m -> m.label().isEmpty() ? "(unlabelled)" : m.label())
                    .findFirst().orElse("unknown");
            pb.fillRect(0, y, W, 16, 0xFF0A2810);
            pb.drawRect(0, y, W, 16, C_GOOD);
            pb.drawStringCentered(0, W - DISP_BTN_W - 16, y + 3, "\u25CF  LIVE — Mirroring to: " + lbl, C_GOOD);
            // Stop button
            pb.fillRect(W - DISP_BTN_W - 8, y + 1, DISP_BTN_W, 14, 0xFF3A1010);
            pb.drawRect(W - DISP_BTN_W - 8, y + 1, DISP_BTN_W, 14, C_BAD);
            pb.drawStringCentered(W - DISP_BTN_W - 8, DISP_BTN_W, y + 3, "Stop", C_BAD);
            y += 20;
        }

        if (mirrorMonitors.isEmpty()) {
            pb.drawStringCentered(0, W, y + 40, "No monitors found within 64 blocks.", C_DIM);
            pb.drawStringCentered(0, W, y + 54, "Place a ByteBlock Monitor and give it a label.", C_DIM);
                drawColorSettingsPanel(pb);
                return;
        }

        // Column headers
        pb.fillRect(0, y, W, ROW_H, C_PANEL);
        pb.drawString(8,       y + 4, "Label",    C_DIM);
        pb.drawString(320,     y + 4, "Position", C_DIM);
        pb.drawStringRight(W - DISP_BTN_W - 16, y + 4, "Dist", C_DIM);
        y += ROW_H;
        pb.drawHLine(0, W, y, C_BORDER);

        int start = scrollMonitor;
        int maxRows = displayVisibleRows();
        int end = Math.min(mirrorMonitors.size(), start + maxRows);

        for (int i = start; i < end; i++) {
            MonitorEntry m = mirrorMonitors.get(i);
            boolean isActive = m.deviceId().equals(activeMirrorId);
                int rowBg = isActive ? 0xFF0A2010 : (i % 2 == 0 ? cRowEven() : cRowOdd());
            int rowY  = y + (i - start) * DISP_ROW_H;

            pb.fillRect(0, rowY, W, DISP_ROW_H, rowBg);
            if (isActive) pb.drawRect(0, rowY, W, DISP_ROW_H, C_GOOD);

            String lbl = m.label().isEmpty() ? "(unlabelled)" : m.label();
            pb.drawString(8, rowY + 6, lbl, isActive ? C_GOOD : C_TITLE);
            if (isActive) pb.drawString(lbl.length() * 6 + 16, rowY + 6, "\u25CF LIVE", C_GOOD);

            BlockPos mp = m.pos();
            pb.drawString(320, rowY + 6, mp.getX() + ", " + mp.getY() + ", " + mp.getZ(), C_DIM);
            pb.drawStringRight(W - DISP_BTN_W - 16, rowY + 6, (int) m.dist() + "m", C_DIM);

            // Action button
            int btnX = W - DISP_BTN_W - 8;
            int btnY = rowY + (DISP_ROW_H - 14) / 2;
            if (isActive) {
                pb.fillRect(btnX, btnY, DISP_BTN_W, 14, 0xFF3A1010);
                pb.drawRect(btnX, btnY, DISP_BTN_W, 14, C_BAD);
                pb.drawStringCentered(btnX, DISP_BTN_W, btnY + 3, "Stop", C_BAD);
            } else {
                pb.fillRect(btnX, btnY, DISP_BTN_W, 14, C_BTN_HOT);
                pb.drawRect(btnX, btnY, DISP_BTN_W, 14, C_DLG_BORDER);
                pb.drawStringCentered(btnX, DISP_BTN_W, btnY + 3, "Mirror", C_TITLE);
            }
        }

        // Scrollbar
        int listH = maxRows * DISP_ROW_H;
        drawScrollbar(pb, y, listH, mirrorMonitors.size(), maxRows, scrollMonitor);

        // Bottom paging controls (reliable fallback when wheel is unavailable)
        int ctrlY = colorSectionY() - DISP_SCROLL_CTRL_H;
        pb.fillRect(0, ctrlY, W, DISP_SCROLL_CTRL_H, 0xFF101634);
        pb.drawHLine(0, W, ctrlY, C_BORDER);
        int maxScroll = Math.max(0, mirrorMonitors.size() - maxRows);
        boolean canPrev = scrollMonitor > 0;
        boolean canNext = scrollMonitor < maxScroll;
        int navW = 56;
        int prevX = 8;
        int nextX = prevX + navW + 6;
        pb.fillRect(prevX, ctrlY + 1, navW, DISP_SCROLL_CTRL_H - 2, canPrev ? C_BTN_HOT : C_BTN);
        pb.drawRect(prevX, ctrlY + 1, navW, DISP_SCROLL_CTRL_H - 2, canPrev ? C_DLG_BORDER : C_DIM);
        pb.drawStringCentered(prevX, navW, ctrlY + 4, "< Prev", canPrev ? C_TITLE : C_DIM);
        pb.fillRect(nextX, ctrlY + 1, navW, DISP_SCROLL_CTRL_H - 2, canNext ? C_BTN_HOT : C_BTN);
        pb.drawRect(nextX, ctrlY + 1, navW, DISP_SCROLL_CTRL_H - 2, canNext ? C_DLG_BORDER : C_DIM);
        pb.drawStringCentered(nextX, navW, ctrlY + 4, "Next >", canNext ? C_TITLE : C_DIM);
        if (!mirrorMonitors.isEmpty()) {
            int from = scrollMonitor + 1;
            int to = Math.min(mirrorMonitors.size(), scrollMonitor + maxRows);
            pb.drawStringRight(W - 8, ctrlY + 4, "Rows " + from + "-" + to + " / " + mirrorMonitors.size(), C_DIM);
        }
            drawColorSettingsPanel(pb);
    }

    private void drawColorSettingsPanel(PixelBuffer pb) {
        int y = colorSectionY();
        pb.drawHLine(0, W, y, C_BORDER);
        y += 2;
        pb.fillRect(0, y, W, colorSectionH() - 2, 0xFF141A3C);
        pb.drawString(8, y + 1, "Display Colors", C_TITLE);

        int scBtnW = 16;
        int scBtnH = 12;
        int scUpX = W - 42;
        int scDownX = W - 22;
        int szMinusX = W - 86;
        int szPlusX = W - 66;
        int scY = y + 1;
        int maxColorScroll = maxColorSettingsScroll();
        boolean canUp = colorSettingsScroll > 0;
        boolean canDown = colorSettingsScroll < maxColorScroll;
        boolean canShrink = colorVisibleRows() > 1;
        boolean canGrow = colorVisibleRows() < COLOR_MAX_VISIBLE_ROWS;

        pb.fillRect(szMinusX, scY, scBtnW, scBtnH, canShrink ? C_BTN_HOT : C_BTN);
        pb.drawRect(szMinusX, scY, scBtnW, scBtnH, canShrink ? C_DLG_BORDER : C_DIM);
        pb.drawStringCentered(szMinusX, scBtnW, scY + 2, "-", canShrink ? C_TITLE : C_DIM);

        pb.fillRect(szPlusX, scY, scBtnW, scBtnH, canGrow ? C_BTN_HOT : C_BTN);
        pb.drawRect(szPlusX, scY, scBtnW, scBtnH, canGrow ? C_DLG_BORDER : C_DIM);
        pb.drawStringCentered(szPlusX, scBtnW, scY + 2, "+", canGrow ? C_TITLE : C_DIM);

        pb.fillRect(scUpX, scY, scBtnW, scBtnH, canUp ? C_BTN_HOT : C_BTN);
        pb.drawRect(scUpX, scY, scBtnW, scBtnH, canUp ? C_DLG_BORDER : C_DIM);
        pb.drawStringCentered(scUpX, scBtnW, scY + 2, "\u25B2", canUp ? C_TITLE : C_DIM);

        pb.fillRect(scDownX, scY, scBtnW, scBtnH, canDown ? C_BTN_HOT : C_BTN);
        pb.drawRect(scDownX, scY, scBtnW, scBtnH, canDown ? C_DLG_BORDER : C_DIM);
        pb.drawStringCentered(scDownX, scBtnW, scY + 2, "\u25BC", canDown ? C_TITLE : C_DIM);

        pb.drawStringRight(szMinusX - 6, y + 1, "Rows " + colorVisibleRows() + "  View " + (colorSettingsScroll + 1) + "/" + COLOR_TOTAL_ROWS, C_DIM);
        y += 14;

        String[] labels = {"Background :", "Row Even :", "Row Odd :", "Text Color :", "Header Bar :", "Title Text :"};
        int[]    indices = {idxBg, idxRowE, idxRowO, idxText, idxHeader, idxTitle};
        String[][] names = {BG_NAMES, ROW_E_NAMES, ROW_O_NAMES, TEXT_NAMES, HEADER_NAMES, TITLE_NAMES};
        int[][]  opts   = {BG_OPTIONS, ROW_E_OPTIONS, ROW_O_OPTIONS, TEXT_OPTIONS, HEADER_OPTIONS, TITLE_OPTIONS};

        int start = colorSettingsScroll;
        int visibleRows = colorVisibleRows();
        int end = Math.min(COLOR_TOTAL_ROWS, start + visibleRows);
        for (int i = start; i < end; i++) {
            int rowY = y + (i - start) * COLOR_ROW_H;
            pb.drawString(8, rowY + 3, labels[i], C_DIM);
            pb.fillRect(CS_SWATCH_X, rowY + 2, CS_SWATCH_W, COLOR_ROW_H - 4, opts[i][indices[i]]);
            pb.drawRect(CS_SWATCH_X, rowY + 2, CS_SWATCH_W, COLOR_ROW_H - 4, C_BORDER);

            pb.fillRect(CS_PREV_X, rowY + 2, CS_BTN_W, COLOR_ROW_H - 4, 0xFF243A74);
            pb.drawRect(CS_PREV_X, rowY + 2, CS_BTN_W, COLOR_ROW_H - 4, C_DLG_BORDER);
            pb.drawStringCentered(CS_PREV_X, CS_BTN_W, rowY + 3, "\u25C4", C_TITLE);

            pb.fillRect(CS_NEXT_X, rowY + 2, CS_BTN_W, COLOR_ROW_H - 4, 0xFF243A74);
            pb.drawRect(CS_NEXT_X, rowY + 2, CS_BTN_W, COLOR_ROW_H - 4, C_DLG_BORDER);
            pb.drawStringCentered(CS_NEXT_X, CS_BTN_W, rowY + 3, "\u25BA", C_TITLE);

            pb.drawString(CS_NAME_X, rowY + 3, names[i][indices[i]], cText());
        }

        int applyY = y + visibleRows * COLOR_ROW_H + 2;
        pb.fillRect(CS_RESET_X, applyY, CS_RESET_W, CS_RESET_H, 0xFF3E2E1E);
        pb.drawRect(CS_RESET_X, applyY, CS_RESET_W, CS_RESET_H, C_DLG_BORDER);
        pb.drawStringCentered(CS_RESET_X, CS_RESET_W, applyY + 3, "Reset", C_TITLE);

        int applyBg = colorSettingsDirty ? 0xFF2E5CCC : 0xFF20325E;
        pb.fillRect(CS_APPLY_X, applyY, CS_APPLY_W, CS_APPLY_H, applyBg);
        pb.drawRect(CS_APPLY_X, applyY, CS_APPLY_W, CS_APPLY_H, C_DLG_BORDER);
        pb.drawStringCentered(CS_APPLY_X, CS_APPLY_W, applyY + 3, "Apply", C_TITLE);
        pb.drawString(8, applyY + 3, colorSettingsDirty ? "Pending changes" : "Saved", colorSettingsDirty ? C_WARN : C_GOOD);
    }

    private void handleColorSettingsClick(int px, int py) {
        int headerY = colorSectionY() + 2;
        int scBtnW = 16;
        int scBtnH = 12;
        int scUpX = W - 42;
        int scDownX = W - 22;
        int szMinusX = W - 86;
        int szPlusX = W - 66;
        int maxColorScroll = maxColorSettingsScroll();

        if (py >= headerY + 1 && py < headerY + 1 + scBtnH) {
            if (px >= szMinusX && px < szMinusX + scBtnW) {
                setColorVisibleRows(colorRowsVisible - 1, true);
                return;
            }
            if (px >= szPlusX && px < szPlusX + scBtnW) {
                setColorVisibleRows(colorRowsVisible + 1, true);
                return;
            }
            if (px >= scUpX && px < scUpX + scBtnW) {
                colorSettingsScroll = Math.max(0, colorSettingsScroll - 1);
                return;
            }
            if (px >= scDownX && px < scDownX + scBtnW) {
                colorSettingsScroll = Math.min(maxColorScroll, colorSettingsScroll + 1);
                return;
            }
        }

        int rowY0 = colorSectionY() + 2 + 14;
        int visibleRows = colorVisibleRows();
        int applyY = rowY0 + visibleRows * COLOR_ROW_H + 2;

        if (py >= applyY && py < applyY + CS_RESET_H && px >= CS_RESET_X && px < CS_RESET_X + CS_RESET_W) {
            idxBg = 0;
            idxRowE = 0;
            idxRowO = 0;
            idxText = 0;
            idxHeader = 0;
            idxTitle = 0;
            saveSettings();
            colorSettingsDirty = false;
            return;
        }

        if (py >= applyY && py < applyY + CS_APPLY_H && px >= CS_APPLY_X && px < CS_APPLY_X + CS_APPLY_W) {
            if (colorSettingsDirty) {
                saveSettings();
                colorSettingsDirty = false;
            }
            return;
        }

        int localRow = (py - rowY0) / COLOR_ROW_H;
        if (localRow < 0 || localRow >= visibleRows) return;
        int row = colorSettingsScroll + localRow;
        if (row < 0 || row >= COLOR_TOTAL_ROWS) return;

        boolean isPrev = px >= CS_PREV_X && px < CS_PREV_X + CS_BTN_W;
        boolean isNext = px >= CS_NEXT_X && px < CS_NEXT_X + CS_BTN_W;
        if (!isPrev && !isNext) return;

        int delta = isPrev ? -1 : 1;
        switch (row) {
            case 0 -> idxBg   = (idxBg + delta + BG_OPTIONS.length) % BG_OPTIONS.length;
            case 1 -> idxRowE = (idxRowE + delta + ROW_E_OPTIONS.length) % ROW_E_OPTIONS.length;
            case 2 -> idxRowO = (idxRowO + delta + ROW_O_OPTIONS.length) % ROW_O_OPTIONS.length;
            case 3 -> idxText = (idxText + delta + TEXT_OPTIONS.length) % TEXT_OPTIONS.length;
            case 4 -> idxHeader = (idxHeader + delta + HEADER_OPTIONS.length) % HEADER_OPTIONS.length;
            case 5 -> idxTitle  = (idxTitle + delta + TITLE_OPTIONS.length) % TITLE_OPTIONS.length;
        }
        saveSettings();
        colorSettingsDirty = false;
    }

        private void handleDisplayTabClick(int px, int py) {
            int y = CONTENT_Y + 20 + 2; // after title bar + separator

            // Split-line drag handle between monitor list and color settings panel.
            if (Math.abs(py - colorSectionY()) <= 4) {
                draggingDisplayDivider = true;
                dragDisplayDividerTo(py);
                return;
            }

            // Color settings panel (always at bottom)
            if (py >= colorSectionY()) {
                handleColorSettingsClick(px, py);
                return;
            }

            // Refresh button in title bar
        if (py >= CONTENT_Y && py < CONTENT_Y + 20 && px >= W - 80) {
            refreshMonitors();
            return;
        }

        boolean hasActiveBanner = (activeMirrorId != null);
        if (hasActiveBanner) {
            // Stop button in active banner
            if (py >= y && py < y + 20 && px >= W - DISP_BTN_W - 8) {
                stopMirror();
                return;
            }
            y += 20;
        }

        // Skip header row
        y += ROW_H + 1;

        int maxRows = displayVisibleRows();
        int maxScroll = Math.max(0, mirrorMonitors.size() - maxRows);

        // Bottom paging controls
        int ctrlY = colorSectionY() - DISP_SCROLL_CTRL_H;
        int navW = 56;
        int prevX = 8;
        int nextX = prevX + navW + 6;
        if (py >= ctrlY && py < ctrlY + DISP_SCROLL_CTRL_H) {
            if (px >= prevX && px < prevX + navW) {
                scrollMonitor = Math.max(0, scrollMonitor - 1);
                return;
            }
            if (px >= nextX && px < nextX + navW) {
                scrollMonitor = Math.min(maxScroll, scrollMonitor + 1);
                return;
            }
        }

        for (int i = scrollMonitor; i < Math.min(mirrorMonitors.size(), scrollMonitor + maxRows); i++) {
            int rowY = y + (i - scrollMonitor) * DISP_ROW_H;
            if (py >= rowY && py < rowY + DISP_ROW_H) {
                MonitorEntry m = mirrorMonitors.get(i);
                if (m.deviceId().equals(activeMirrorId)) {
                    stopMirror();
                } else {
                    mirrorMonitorChoice = m;
                    executeMirror();
                }
                return;
            }
        }
    }

    private void openMirrorDialog() {
        mirrorMonitors.clear();
        Level lvl = os.getLevel();
        BlockPos myPos = os.getBlockPos();
        if (lvl != null && myPos != null) {
            List<BluetoothNetwork.DeviceEntry> inRange =
                    BluetoothNetwork.getDevicesInRange(lvl, myPos, 64);
            for (BluetoothNetwork.DeviceEntry d : inRange) {
                if (d.type() != BluetoothNetwork.DeviceType.MONITOR) continue;
                String lbl = "";
                if (lvl.getBlockEntity(d.pos()) instanceof MonitorBlockEntity mbe) {
                    String ml = mbe.getLabel();
                    lbl = ml != null ? ml : "";
                }
                double dist = Math.sqrt(myPos.distSqr(d.pos()));
                mirrorMonitors.add(new MonitorEntry(d.deviceId(), d.pos(), lbl, dist));
            }
            mirrorMonitors.sort(Comparator.comparingDouble(MonitorEntry::dist));
        }
        // Auto-select the currently-active mirror if still in range
        mirrorMonitorChoice = mirrorMonitors.stream()
                .filter(m -> m.deviceId().equals(activeMirrorId))
                .findFirst()
                .orElse(mirrorMonitors.isEmpty() ? null : mirrorMonitors.get(0));
        mirrorMonitorScroll = 0;
        mirrorResultMsg     = null;
        mirrorResultTick    = -1;
        showMirrorDialog    = true;
    }

    private static final int MDG_W    = 380;
    private static final int MDG_H    = 160;
    private static final int MDG_X    = (W - MDG_W) / 2;
    private static final int MDG_Y    = (H - MDG_H) / 2;
    private static final int MDG_ROWS = 4;
    private static final int MDG_ROW_H = 14;

    private void drawMirrorDialog(PixelBuffer pb) {
        // Dim background
        for (int x = MDG_X - 6; x < MDG_X + MDG_W + 6; x++)
            for (int y = MDG_Y - 6; y < MDG_Y + MDG_H + 6; y++)
                pb.setPixel(x, y, 0xAA000010);

        pb.fillRect(MDG_X, MDG_Y, MDG_W, MDG_H, C_DLG_BG);
        pb.drawRect(MDG_X, MDG_Y, MDG_W, MDG_H, C_GOOD);

        int lx = MDG_X + 8;
        int y  = MDG_Y + 4;

        // Title bar
        pb.fillRect(MDG_X, MDG_Y, MDG_W, 16, 0xFF0A2010);
        pb.drawString(lx, y, "Mirror to Monitor", C_GOOD);
        pb.fillRect(MDG_X + MDG_W - 18, MDG_Y + 1, 16, 14, 0xFF3A1020);
        pb.drawString(MDG_X + MDG_W - 14, MDG_Y + 3, "X", C_BAD);
        y += 18;

        // Currently mirroring banner
        if (activeMirrorId != null) {
            String activeLbl = mirrorMonitors.stream()
                    .filter(m -> m.deviceId().equals(activeMirrorId))
                    .map(m -> m.label().isEmpty() ? "(unlabelled)" : m.label())
                    .findFirst().orElse(activeMirrorId.toString().substring(0, 8));
            pb.fillRect(MDG_X + 2, y, MDG_W - 4, 12, 0xFF08200C);
            pb.drawStringCentered(MDG_X, MDG_W, y + 1, "Mirroring: " + activeLbl, C_GOOD);
            y += 14;
        }

        // Monitor list
        pb.drawString(lx, y, "Monitors in range:", C_DIM);
        y += 12;

        if (mirrorMonitors.isEmpty()) {
            pb.drawString(lx, y, "No labelled monitors within 64 blocks.", C_WARN);
            y += MDG_ROWS * MDG_ROW_H;
        } else {
            int start = mirrorMonitorScroll;
            int end   = Math.min(mirrorMonitors.size(), start + MDG_ROWS);
            for (int i = start; i < end; i++) {
                MonitorEntry m = mirrorMonitors.get(i);
                boolean sel = (mirrorMonitorChoice != null && m.deviceId().equals(mirrorMonitorChoice.deviceId()));
                boolean active = m.deviceId().equals(activeMirrorId);
                int rowBg = active ? 0xFF0A2810 : (sel ? C_MONITOR_SEL : (i % 2 == 0 ? C_PANEL : C_DLG_BG));
                pb.fillRect(MDG_X + 2, y, MDG_W - 4, MDG_ROW_H, rowBg);
                if (sel) pb.drawRect(MDG_X + 2, y, MDG_W - 4, MDG_ROW_H, active ? C_GOOD : C_DLG_BORDER);

                String lbl = m.label().isEmpty() ? "(unlabelled)" : m.label();
                pb.drawString(lx, y + 2, lbl, sel ? C_TITLE : C_TEXT);
                if (active) pb.drawString(lx + 130, y + 2, "\u25CF LIVE", C_GOOD);
                pb.drawStringRight(MDG_X + MDG_W - 8, y + 2, (int) m.dist() + "m", C_DIM);
                y += MDG_ROW_H;
            }
            if (mirrorMonitors.size() > MDG_ROWS) {
                int sbH = MDG_ROWS * MDG_ROW_H;
                int sbY = y - sbH;
                int sbX = MDG_X + MDG_W - 5;
                pb.fillRect(sbX, sbY, 3, sbH, 0xFF111122);
                float th  = Math.max(6f, (float) MDG_ROWS / mirrorMonitors.size() * sbH);
                float top = (float) mirrorMonitorScroll / mirrorMonitors.size() * sbH;
                pb.fillRect(sbX, sbY + (int) top, 3, (int) th, C_BORDER);
            }
        }

        pb.drawHLine(MDG_X, MDG_X + MDG_W, y, C_BORDER);
        y += 4;

        // Result message
        if (mirrorResultMsg != null) {
            long age = os.getTickCount() - mirrorResultTick;
            if (age < 60) {
                boolean isErr = mirrorResultMsg.startsWith("!");
                pb.drawString(lx, y, isErr ? mirrorResultMsg.substring(1) : mirrorResultMsg,
                        isErr ? C_BAD : C_GOOD);
            } else {
                mirrorResultMsg = null;
            }
        }
        y += 12;

        // Buttons
        int btnW = 90, btnH = 18;
        int cancelX = MDG_X + 16;
        int actionX = MDG_X + MDG_W - btnW - 16;

        pb.fillRect(cancelX, y, btnW, btnH, C_BTN);
        pb.drawRect(cancelX, y, btnW, btnH, C_DLG_BORDER);
        pb.drawStringCentered(cancelX, btnW, y + 4, "Cancel", C_DIM);

        boolean canMirror  = (mirrorMonitorChoice != null);
        boolean isActiveChosen = canMirror && mirrorMonitorChoice.deviceId().equals(activeMirrorId);
        if (isActiveChosen) {
            pb.fillRect(actionX, y, btnW, btnH, 0xFF3A1010);
            pb.drawRect(actionX, y, btnW, btnH, C_BAD);
            pb.drawStringCentered(actionX, btnW, y + 4, "Stop Mirror", C_BAD);
        } else {
            pb.fillRect(actionX, y, btnW, btnH, canMirror ? 0xFF0A3A14 : C_BTN);
            pb.drawRect(actionX, y, btnW, btnH, canMirror ? C_GOOD : C_DIM);
            pb.drawStringCentered(actionX, btnW, y + 4, "Mirror", canMirror ? C_GOOD : C_DIM);
        }
    }

    private void handleMirrorDialogClick(int px, int py) {
        // [×] close
        if (px >= MDG_X + MDG_W - 18 && px <= MDG_X + MDG_W - 2
                && py >= MDG_Y + 1 && py <= MDG_Y + 15) {
            showMirrorDialog = false;
            return;
        }

        // Monitor list rows
        boolean hasActiveBanner = (activeMirrorId != null);
        int listY = MDG_Y + 4 + 18 + (hasActiveBanner ? 14 : 0) + 12;
        for (int i = mirrorMonitorScroll; i < Math.min(mirrorMonitors.size(), mirrorMonitorScroll + MDG_ROWS); i++) {
            int rowY = listY + (i - mirrorMonitorScroll) * MDG_ROW_H;
            if (py >= rowY && py < rowY + MDG_ROW_H && px >= MDG_X + 2 && px < MDG_X + MDG_W - 4) {
                mirrorMonitorChoice = mirrorMonitors.get(i);
                return;
            }
        }

        // Buttons
        int btnY  = MDG_Y + 4 + 18 + (hasActiveBanner ? 14 : 0) + 12 + MDG_ROWS * MDG_ROW_H + 4 + 12;
        int btnW  = 90, btnH = 18;
        int cancelX = MDG_X + 16;
        int actionX = MDG_X + MDG_W - btnW - 16;

        if (px >= cancelX && px < cancelX + btnW && py >= btnY && py < btnY + btnH) {
            showMirrorDialog = false;
            return;
        }
        if (px >= actionX && px < actionX + btnW && py >= btnY && py < btnY + btnH) {
            if (mirrorMonitorChoice != null && mirrorMonitorChoice.deviceId().equals(activeMirrorId)) {
                stopMirror();
            } else {
                executeMirror();
            }
        }
    }

    private void executeMirror() {
        if (mirrorMonitorChoice == null) return;
        Level lvl    = os.getLevel();
        BlockPos pos = os.getBlockPos();
        UUID myId    = os.getComputerId();
        if (lvl == null || pos == null || myId == null) return;

        // Stop previously mirrored monitor first
        if (activeMirrorId != null && !activeMirrorId.equals(mirrorMonitorChoice.deviceId())) {
            BluetoothNetwork.send(lvl, pos, myId, activeMirrorId, 1, "display_mode:blank");
        }

        // Link the chosen monitor to us and set mirror mode
        String linkMsg = "link:" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        BluetoothNetwork.send(lvl, pos, myId, mirrorMonitorChoice.deviceId(), 1, linkMsg);
        BluetoothNetwork.send(lvl, pos, myId, mirrorMonitorChoice.deviceId(), 1, "display_mode:mirror");

        activeMirrorId  = mirrorMonitorChoice.deviceId();
        String lbl = mirrorMonitorChoice.label().isEmpty() ? "monitor" : mirrorMonitorChoice.label();
        mirrorResultMsg  = "Now mirroring to: " + lbl;
        mirrorResultTick = os.getTickCount();
    }

    private void stopMirror() {
        if (activeMirrorId == null) return;
        Level lvl = os.getLevel();
        BlockPos pos = os.getBlockPos();
        UUID myId = os.getComputerId();
        if (lvl != null && pos != null && myId != null) {
            BluetoothNetwork.send(lvl, pos, myId, activeMirrorId, 1, "display_mode:blank");
        }
        mirrorResultMsg  = "Mirror stopped.";
        mirrorResultTick = os.getTickCount();
        activeMirrorId   = null;
    }

    // ── Export dialog ─────────────────────────────────────────────────────

    private void openExportDialog() {
        // Refresh chest list
        Level lvl = os.getLevel();
        BlockPos pos = os.getBlockPos();
        if (lvl != null && pos != null) {
            exportChests = BluetoothNetwork.listLabeledChests(lvl, pos, BluetoothNetwork.BLOCK_RANGE);
        } else {
            exportChests = new ArrayList<>();
        }
        exportChestChoice  = exportChests.isEmpty() ? null : exportChests.get(0);
        exportChestScroll  = 0;
        exportResultMsg    = null;
        exportResultTick   = -1;
        showExportDialog   = true;
    }

    private void drawExportDialog(PixelBuffer pb) {
        // Dim overlay
        for (int x = DLG_X - 6; x < DLG_X + DLG_W + 6; x++)
            for (int y = DLG_Y - 6; y < DLG_Y + DLG_H + 6; y++)
                pb.setPixel(x, y, 0xAA000010);

        // Dialog box
        pb.fillRect(DLG_X, DLG_Y, DLG_W, DLG_H, C_DLG_BG);
        pb.drawRect(DLG_X, DLG_Y, DLG_W, DLG_H, C_DLG_BORDER);

        int lx = DLG_X + 8;   // left margin inside dialog
        int y  = DLG_Y + 4;

        // Title bar
        pb.fillRect(DLG_X, DLG_Y, DLG_W, 16, 0xFF0F1E50);
        pb.drawString(lx, y, "Export to ByteChest", C_TITLE);
        // [×] close button
        pb.fillRect(DLG_X + DLG_W - 18, DLG_Y + 1, 16, 14, 0xFF3A1020);
        pb.drawString(DLG_X + DLG_W - 14, DLG_Y + 3, "X", C_BAD);
        y += 18;

        // Item info
        if (selectedItem != null) {
            String disp = selectedItem.displayName();
            if (disp.length() > 34) disp = disp.substring(0, 33) + "~";
            pb.drawString(lx, y, "Item: " + disp, C_TEXT);
            pb.drawStringRight(DLG_X + DLG_W - 8, y, "(" + formatCount(selectedItem.count()) + " in ME)", C_DIM);
        }
        y += 14;

        // Qty input row
        pb.drawString(lx, y, "Qty:", C_DIM);
        int qiX = lx + 30, qiW = 100, qiH = 12;
        pb.fillRect(qiX, y, qiW, qiH, 0xFF0A1030);
        pb.drawRect(qiX, y, qiW, qiH, C_DLG_BORDER);
        pb.drawString(qiX + 3, y + 2, exportQty.toString() + "_", C_TITLE);
        y += 16;

        // Separator
        pb.drawHLine(DLG_X, DLG_X + DLG_W, y, C_BORDER);
        y += 4;

        // Chests section
        pb.drawString(lx, y, "ByteChests in range:", C_DIM);
        y += 12;

        if (exportChests.isEmpty()) {
            pb.drawString(lx, y, "No labelled ByteChests within 15 blocks.", C_WARN);
            y += DLG_CHEST_ROWS * DLG_ROW_H;
        } else {
            BlockPos myPos = os.getBlockPos();
            int start = exportChestScroll;
            int end   = Math.min(exportChests.size(), start + DLG_CHEST_ROWS);
            for (int i = start; i < end; i++) {
                LabeledChest chest = exportChests.get(i);
                boolean sel = (exportChestChoice != null && chest.deviceId().equals(exportChestChoice.deviceId()));
                int rowBg = sel ? C_CHEST_SEL : (i % 2 == 0 ? C_PANEL : C_DLG_BG);
                pb.fillRect(DLG_X + 2, y, DLG_W - 4, DLG_ROW_H, rowBg);
                if (sel) pb.drawRect(DLG_X + 2, y, DLG_W - 4, DLG_ROW_H, C_DLG_BORDER);

                String lbl = chest.label().isEmpty() ? "(unlabelled)" : chest.label();
                pb.drawString(lx, y + 2, lbl, sel ? C_TITLE : C_TEXT);

                // Distance
                if (myPos != null) {
                    int dist = (int) Math.sqrt(chest.pos().distSqr(myPos));
                    pb.drawStringRight(DLG_X + DLG_W - 8, y + 2, dist + "m", C_DIM);
                }
                y += DLG_ROW_H;
            }
            // Mini scrollbar
            if (exportChests.size() > DLG_CHEST_ROWS) {
                int sbH = DLG_CHEST_ROWS * DLG_ROW_H;
                int sbY = y - sbH;
                int sbX = DLG_X + DLG_W - 5;
                pb.fillRect(sbX, sbY, 3, sbH, 0xFF111122);
                float th  = Math.max(6f, (float) DLG_CHEST_ROWS / exportChests.size() * sbH);
                float top = (float) exportChestScroll / exportChests.size() * sbH;
                pb.fillRect(sbX, sbY + (int) top, 3, (int) th, C_BORDER);
            }
        }

        // Separator
        pb.drawHLine(DLG_X, DLG_X + DLG_W, y, C_BORDER);
        y += 4;

        // Result message (3-second fade)
        if (exportResultMsg != null) {
            long age = os.getTickCount() - exportResultTick;
            if (age < 60) {
                boolean isErr = exportResultMsg.startsWith("!");
                pb.drawString(lx, y, isErr ? exportResultMsg.substring(1) : exportResultMsg,
                        isErr ? C_BAD : C_GOOD);
            } else {
                exportResultMsg = null;
            }
        }
        y += 14;

        // Buttons
        int btnW = 80, btnH = 18;
        int cancelX = DLG_X + 20;
        int exportX = DLG_X + DLG_W - btnW - 20;

        pb.fillRect(cancelX, y, btnW, btnH, C_BTN);
        pb.drawRect(cancelX, y, btnW, btnH, C_DLG_BORDER);
        pb.drawStringCentered(cancelX, btnW, y + 4, "Cancel", C_DIM);

        boolean canDoExport = (selectedItem != null && exportChestChoice != null && meNode != null && !exportQty.isEmpty());
        pb.fillRect(exportX, y, btnW, btnH, canDoExport ? C_BTN_HOT : C_BTN);
        pb.drawRect(exportX, y, btnW, btnH, canDoExport ? C_DLG_BORDER : C_DIM);
        pb.drawStringCentered(exportX, btnW, y + 4, "Export", canDoExport ? C_TITLE : C_DIM);
    }

    private void handleExportDialogClick(int px, int py) {
        // [×] close button
        if (px >= DLG_X + DLG_W - 18 && px <= DLG_X + DLG_W - 2
                && py >= DLG_Y + 1 && py <= DLG_Y + 15) {
            showExportDialog = false;
            return;
        }

        // Chest list rows
        int chestListY = DLG_Y + 4 + 18 + 14 + 16 + 4 + 12; // must match drawExportDialog layout
        for (int i = exportChestScroll; i < Math.min(exportChests.size(), exportChestScroll + DLG_CHEST_ROWS); i++) {
            int rowY = chestListY + (i - exportChestScroll) * DLG_ROW_H;
            if (py >= rowY && py < rowY + DLG_ROW_H && px >= DLG_X + 2 && px < DLG_X + DLG_W - 4) {
                exportChestChoice = exportChests.get(i);
                return;
            }
        }

        // Calculate button y (must match drawExportDialog)
        int btnY = DLG_Y + 4 + 18 + 14 + 16 + 4 + 12 + DLG_CHEST_ROWS * DLG_ROW_H + 4 + 14;
        int btnW = 80, btnH = 18;

        // Cancel button
        int cancelX = DLG_X + 20;
        if (px >= cancelX && px < cancelX + btnW && py >= btnY && py < btnY + btnH) {
            showExportDialog = false;
            return;
        }

        // Export button
        int exportX = DLG_X + DLG_W - btnW - 20;
        if (px >= exportX && px < exportX + btnW && py >= btnY && py < btnY + btnH) {
            executeExport();
        }
    }

    private void executeExport() {
        if (selectedItem == null || exportChestChoice == null || meNode == null) return;

        int qty;
        try {
            qty = Integer.parseInt(exportQty.toString());
        } catch (NumberFormatException e) {
            exportResultMsg  = "!Invalid quantity.";
            exportResultTick = os.getTickCount();
            return;
        }
        if (qty <= 0) {
            exportResultMsg  = "!Quantity must be > 0.";
            exportResultTick = os.getTickCount();
            return;
        }

        Level lvl = os.getLevel();
        if (lvl == null) {
            exportResultMsg  = "!No world context.";
            exportResultTick = os.getTickCount();
            return;
        }

        // Get the ByteChest inventory
        BlockEntity be = lvl.getBlockEntity(exportChestChoice.pos());
        if (!(be instanceof ByteChestBlockEntity chest)) {
            exportResultMsg  = "!Chest not found — is it loaded?";
            exportResultTick = os.getTickCount();
            return;
        }

        int transferred = AE2PeripheralAdapter.extractToInventoryJava(
                meNode, selectedItem.name(), qty, chest);

        if (transferred <= 0) {
            exportResultMsg = "!Export failed — item not available or chest full.";
        } else {
            String lbl = exportChestChoice.label().isEmpty() ? "chest" : exportChestChoice.label();
            exportResultMsg = "Exported " + formatCount(transferred) + "x "
                    + selectedItem.displayName() + " \u2192 " + lbl;
        }
        exportResultTick = os.getTickCount();

        // Refresh item list so count updates immediately
        refresh();
    }
}

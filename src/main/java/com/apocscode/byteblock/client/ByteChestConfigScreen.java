package com.apocscode.byteblock.client;

import com.apocscode.byteblock.block.entity.ByteChestBlockEntity;
import com.apocscode.byteblock.network.PaintByteChestPayload;
import com.apocscode.byteblock.network.RenameByteChestPayload;
import com.apocscode.byteblock.network.SetByteChestLogisticsPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Tabbed configuration screen for ByteChests. Three tabs:
 *
 * <ul>
 *   <li><b>Rename</b>    – label EditBox + OK/Cancel.</li>
 *   <li><b>Paint</b>     – 12x4 hue/brightness palette + Reset (white) and Apply.</li>
 *   <li><b>Logistics</b> – AE2 stock-keeping with 6 filter slots + adjacent push side.</li>
 * </ul>
 *
 * Opened via shift+right-click on the chest with an empty hand.
 */
public class ByteChestConfigScreen extends Screen {

    private static final int W = 720;
    private static final int H = 470;
    private static final int SWATCH = 32;
    private static final int SWATCH_GAP = 6;
    private static final int COLS = 12;
    private static final int ROWS = 4;
    private static final int GRID_W = COLS * SWATCH + (COLS - 1) * SWATCH_GAP;
    private static final int GRID_H = ROWS * SWATCH + (ROWS - 1) * SWATCH_GAP;

    private static final int FILTER_SLOT_SIZE = 22;
    private static final int FILTER_SLOT_GAP = 6;
    private static final int FILTER_COUNT = ByteChestBlockEntity.PULL_FILTER_COUNT;

    // Player inventory grid (9 cols x 4 rows: hotbar row 0, main inventory rows 1-3).
    private static final int INV_SLOT_SIZE = 20;
    private static final int INV_SLOT_GAP = 2;
    private static final int INV_COLS = 9;
    private static final int INV_ROWS = 4;
    private static final int INV_HOTBAR_GAP = 6;

    /** Cached palette (12 hues × 4 brightness rows, last row grayscale). */
    private static final int[] PALETTE = buildPalette();

    private final BlockPos pos;
    private final String initialLabel;
    private final int initialTint;
    private final boolean initialPullEnabled;
    private final String initialPullItemId;
    private final int initialKeepAmount;
    private final boolean initialPushEnabled;
    private final Direction initialPushSide;
    private final int initialMovePerTick;
    private final String[] initialFilterIds;

    private enum Tab { RENAME, PAINT, LOGISTICS }
    private Tab activeTab = Tab.RENAME;
    private int workingTint;
    private boolean workingPullEnabled;
    private String workingPullItemId;
    private int workingKeepAmount;
    private boolean workingPushEnabled;
    private Direction workingPushSide;
    private int workingMovePerTick;
    private final String[] workingFilterIds = new String[FILTER_COUNT];

    private EditBox labelField;
    private EditBox pullItemField;
    private EditBox keepAmountField;
    private EditBox movePerTickField;

    /** Index of the currently selected/focused filter slot (-1 = none). */
    private int selectedFilter = -1;
    /** Cached suggestion list for the autocomplete popup. */
    private final List<String> suggestions = new ArrayList<>();
    private int suggestionScroll = 0;
    /** Bounds of last-rendered suggestion popup (for hit-testing). */
    private int sugX, sugY, sugW, sugH;
    private boolean suggestionsVisible = false;

    /** Layout cache (recomputed each init). */
    private int filterSlotsX;
    private int filterSlotsY;
    private int invX;
    private int invY;

    public ByteChestConfigScreen(BlockPos pos, String currentLabel, int currentTint,
                                 boolean pullEnabled, String pullItemId, int keepAmount,
                                 boolean pushEnabled, Direction pushSide, int movePerTick,
                                 String[] filterIds) {
        super(Component.literal("Configure ByteChest"));
        this.pos = pos;
        this.initialLabel = currentLabel == null ? "" : currentLabel;
        this.initialTint = currentTint & 0xFFFFFF;
        this.initialPullEnabled = pullEnabled;
        this.initialPullItemId = pullItemId == null ? "" : pullItemId;
        this.initialKeepAmount = Math.max(0, keepAmount);
        this.initialPushEnabled = pushEnabled;
        this.initialPushSide = pushSide == null ? Direction.NORTH : pushSide;
        this.initialMovePerTick = Math.max(1, movePerTick);
        this.initialFilterIds = new String[FILTER_COUNT];
        for (int i = 0; i < FILTER_COUNT; i++) {
            this.initialFilterIds[i] = (filterIds != null && i < filterIds.length && filterIds[i] != null)
                    ? filterIds[i] : "";
        }

        this.workingTint = this.initialTint;
        this.workingPullEnabled = this.initialPullEnabled;
        this.workingPullItemId = this.initialPullItemId;
        this.workingKeepAmount = this.initialKeepAmount;
        this.workingPushEnabled = this.initialPushEnabled;
        this.workingPushSide = this.initialPushSide;
        this.workingMovePerTick = this.initialMovePerTick;
        System.arraycopy(this.initialFilterIds, 0, this.workingFilterIds, 0, FILTER_COUNT);
        // If legacy single id is set but slot 0 isn't, copy across.
        if ((this.workingFilterIds[0] == null || this.workingFilterIds[0].isEmpty())
                && !this.workingPullItemId.isEmpty()) {
            this.workingFilterIds[0] = this.workingPullItemId;
        }
    }

    @Override
    protected void init() {
        int left = (this.width - W) / 2;
        int top = (this.height - H) / 2;

        // ── Tab strip ──
        addRenderableWidget(Button.builder(Component.literal("Rename"), b -> {
            activeTab = Tab.RENAME;
            this.rebuildWidgets();
        }).bounds(left + 14, top + 30, 90, 22).build());

        addRenderableWidget(Button.builder(Component.literal("Paint"), b -> {
            activeTab = Tab.PAINT;
            this.rebuildWidgets();
        }).bounds(left + 110, top + 30, 90, 22).build());

        addRenderableWidget(Button.builder(Component.literal("Logistics"), b -> {
            activeTab = Tab.LOGISTICS;
            this.rebuildWidgets();
        }).bounds(left + 206, top + 30, 110, 22).build());

        if (activeTab == Tab.RENAME) {
            initRenameTab(left, top);
        } else if (activeTab == Tab.PAINT) {
            initPaintTab(left, top);
        } else {
            initLogisticsTab(left, top);
        }
    }

    private void initRenameTab(int left, int top) {
        labelField = new EditBox(this.font, left + 14, top + 80, W - 28, 22, Component.literal("Label"));
        labelField.setMaxLength(32);
        labelField.setValue(initialLabel);
        labelField.setFocused(true);
        addRenderableWidget(labelField);
        setInitialFocus(labelField);

        addRenderableWidget(Button.builder(Component.literal("OK"), b -> commitRename())
                .bounds(left + 14, top + H - 32, 110, 22).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(left + W - 124, top + H - 32, 110, 22).build());
    }

    private void initPaintTab(int left, int top) {
        addRenderableWidget(Button.builder(Component.literal("Reset"), b -> workingTint = 0xFFFFFF)
                .bounds(left + W - 90, top + 76, 76, 22).build());
        addRenderableWidget(Button.builder(Component.literal("Apply"), b -> commitPaint())
                .bounds(left + 14, top + H - 32, 110, 22).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(left + W - 124, top + H - 32, 110, 22).build());
    }

    private void initLogisticsTab(int left, int top) {
        // ── PULL section ──
        int pullSectionY = top + 66;

        // Toggle button (compact)
        addRenderableWidget(Button.builder(
                Component.literal(workingPullEnabled ? "● ON" : "○ OFF"),
                b -> { workingPullEnabled = !workingPullEnabled; rebuildWidgets(); })
                .bounds(left + W - 84, pullSectionY + 6, 70, 20).build());

        // Item id field (narrower, with autocomplete)
        pullItemField = new EditBox(this.font, left + 28, pullSectionY + 38, 240, 20,
                Component.literal("minecraft:item_id"));
        pullItemField.setMaxLength(120);
        pullItemField.setValue(workingPullItemId);
        pullItemField.setResponder(this::onPullItemFieldChanged);
        addRenderableWidget(pullItemField);

        // Keep amount (narrower)
        keepAmountField = new EditBox(this.font, left + 360, pullSectionY + 38, 70, 20,
                Component.literal("Keep"));
        keepAmountField.setMaxLength(7);
        keepAmountField.setValue(Integer.toString(workingKeepAmount));
        addRenderableWidget(keepAmountField);

        // Filter slots row
        int slotsTotalW = FILTER_COUNT * FILTER_SLOT_SIZE + (FILTER_COUNT - 1) * FILTER_SLOT_GAP;
        filterSlotsX = left + 28;
        filterSlotsY = pullSectionY + 76;
        // Clear-selected button (only meaningful when a slot is selected)
        addRenderableWidget(Button.builder(Component.literal("Clear Slot"),
                b -> {
                    if (selectedFilter >= 0 && selectedFilter < FILTER_COUNT) {
                        workingFilterIds[selectedFilter] = "";
                        if (selectedFilter == 0) {
                            workingPullItemId = "";
                            if (pullItemField != null) pullItemField.setValue("");
                        }
                    }
                }).bounds(filterSlotsX + slotsTotalW + 18, filterSlotsY - 1, 90, FILTER_SLOT_SIZE).build());
        addRenderableWidget(Button.builder(Component.literal("Set From Field"),
                b -> {
                    if (selectedFilter < 0) selectedFilter = 0;
                    String id = pullItemField != null ? pullItemField.getValue().trim() : "";
                    if (!id.isEmpty()) {
                        workingFilterIds[selectedFilter] = id;
                        if (selectedFilter == 0) workingPullItemId = id;
                    }
                }).bounds(filterSlotsX + slotsTotalW + 114, filterSlotsY - 1, 110, FILTER_SLOT_SIZE).build());

        // ── PUSH section ──
        int pushSectionY = pullSectionY + 130;

        addRenderableWidget(Button.builder(
                Component.literal(workingPushEnabled ? "● ON" : "○ OFF"),
                b -> { workingPushEnabled = !workingPushEnabled; rebuildWidgets(); })
                .bounds(left + W - 84, pushSectionY + 6, 70, 20).build());

        // Side picker — six small buttons, one per direction, labelled with the
        // detected adjacent block name (or "—" if no inventory there).
        Direction[] dirs = { Direction.NORTH, Direction.SOUTH, Direction.EAST,
                             Direction.WEST,  Direction.UP,    Direction.DOWN };
        int dirBtnW = 92;
        int dirBtnH = 18;
        int dirGap = 4;
        for (int i = 0; i < dirs.length; i++) {
            final Direction d = dirs[i];
            String label = sideName(d) + ": " + describeNeighbor(d);
            boolean selected = (workingPushSide == d);
            addRenderableWidget(Button.builder(
                    Component.literal((selected ? "▶ " : "  ") + label),
                    b -> { workingPushSide = d; rebuildWidgets(); })
                    .bounds(left + 28 + (i % 3) * (dirBtnW + dirGap),
                            pushSectionY + 32 + (i / 3) * (dirBtnH + 2),
                            dirBtnW, dirBtnH)
                    .build());
        }

        movePerTickField = new EditBox(this.font, left + 28 + 3 * (dirBtnW + dirGap) + 8,
                pushSectionY + 32, 70, 20,
                Component.literal("Move/Tick"));
        movePerTickField.setMaxLength(4);
        movePerTickField.setValue(Integer.toString(workingMovePerTick));
        addRenderableWidget(movePerTickField);

        // ── Inventory grid layout (read-only display, click to assign) ──
        int invTotalW = INV_COLS * INV_SLOT_SIZE + (INV_COLS - 1) * INV_SLOT_GAP;
        invX = left + (W - invTotalW) / 2;
        invY = pushSectionY + 100;

        // ── Footer ──
        addRenderableWidget(Button.builder(Component.literal("Apply"), b -> commitLogistics())
                .bounds(left + 14, top + H - 32, 110, 22).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(left + W - 124, top + H - 32, 110, 22).build());
    }

    // ── Autocomplete ──────────────────────────────────────────────────────────

    private void onPullItemFieldChanged(String text) {
        rebuildSuggestions(text);
        // Mirror the field into the currently selected filter slot (or slot 0).
        int slot = selectedFilter >= 0 ? selectedFilter : 0;
        workingFilterIds[slot] = text == null ? "" : text.trim();
        if (slot == 0) workingPullItemId = workingFilterIds[0];
    }

    private void rebuildSuggestions(String text) {
        suggestions.clear();
        suggestionScroll = 0;
        if (text == null) text = "";
        String q = text.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) { suggestionsVisible = false; return; }
        // Filter first by exact substring match on the namespace:path form.
        int max = 64;
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
            if (key == null) continue;
            String full = key.toString();
            if (full.toLowerCase(Locale.ROOT).contains(q)) {
                suggestions.add(full);
                if (suggestions.size() >= max) break;
            }
        }
        suggestions.sort((a, b) -> {
            // Prefer exact prefix match, then path-prefix (without namespace), then alphabetical.
            String aPath = a.contains(":") ? a.substring(a.indexOf(':') + 1) : a;
            String bPath = b.contains(":") ? b.substring(b.indexOf(':') + 1) : b;
            int aRank = a.startsWith(q) ? 0 : (aPath.startsWith(q) ? 1 : 2);
            int bRank = b.startsWith(q) ? 0 : (bPath.startsWith(q) ? 1 : 2);
            if (aRank != bRank) return Integer.compare(aRank, bRank);
            return a.compareTo(b);
        });
        suggestionsVisible = !suggestions.isEmpty();
    }

    private void applySuggestion(String full) {
        if (pullItemField != null) {
            pullItemField.setValue(full);
            pullItemField.moveCursorToEnd(false);
        }
        suggestionsVisible = false;
        suggestions.clear();
    }

    // ── Commit handlers ───────────────────────────────────────────────────────

    private void commitRename() {
        String label = labelField.getValue() == null ? "" : labelField.getValue().trim();
        PacketDistributor.sendToServer(new RenameByteChestPayload(pos, label));
        onClose();
    }

    private void commitPaint() {
        PacketDistributor.sendToServer(new PaintByteChestPayload(pos, workingTint & 0xFFFFFF));
        onClose();
    }

    private void commitLogistics() {
        String itemId = pullItemField != null ? pullItemField.getValue().trim() : workingPullItemId;
        int keep = parsePositiveInt(keepAmountField != null ? keepAmountField.getValue() : Integer.toString(workingKeepAmount), 0);
        int move = parsePositiveInt(movePerTickField != null ? movePerTickField.getValue() : Integer.toString(workingMovePerTick), 64);

        workingPullItemId = itemId;
        workingKeepAmount = keep;
        workingMovePerTick = move;
        // Slot 0 mirrors the field if user typed there.
        if (!itemId.isEmpty()) workingFilterIds[0] = itemId;

        List<String> ids = new ArrayList<>(FILTER_COUNT);
        for (int i = 0; i < FILTER_COUNT; i++) {
            ids.add(workingFilterIds[i] == null ? "" : workingFilterIds[i]);
        }

        PacketDistributor.sendToServer(new SetByteChestLogisticsPayload(
                pos,
                workingPullEnabled,
                workingPullItemId,
                workingKeepAmount,
                workingPushEnabled,
                workingPushSide.get3DDataValue(),
                workingMovePerTick,
                ids));
        onClose();
    }

    // ── Mouse / keyboard ──────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Suggestion popup hit test takes priority.
        if (activeTab == Tab.LOGISTICS && suggestionsVisible && button == 0) {
            if (mouseX >= sugX && mouseX < sugX + sugW && mouseY >= sugY && mouseY < sugY + sugH) {
                int rowH = this.font.lineHeight + 4;
                int idx = suggestionScroll + (int) ((mouseY - sugY) / rowH);
                if (idx >= 0 && idx < suggestions.size()) {
                    applySuggestion(suggestions.get(idx));
                    return true;
                }
            } else {
                // Click outside dismisses the popup.
                suggestionsVisible = false;
            }
        }

        // Filter slot click.
        if (activeTab == Tab.LOGISTICS && button == 0) {
            int hit = filterSlotAt(mouseX, mouseY);
            if (hit >= 0) {
                selectedFilter = hit;
                String id = workingFilterIds[hit] == null ? "" : workingFilterIds[hit];
                if (pullItemField != null) {
                    pullItemField.setValue(id);
                    pullItemField.moveCursorToEnd(false);
                }
                workingPullItemId = id;
                return true;
            }
        }
        if (activeTab == Tab.LOGISTICS && button == 1) {
            // Right-click clears a filter slot.
            int hit = filterSlotAt(mouseX, mouseY);
            if (hit >= 0) {
                workingFilterIds[hit] = "";
                if (hit == selectedFilter && pullItemField != null) {
                    pullItemField.setValue("");
                    workingPullItemId = "";
                }
                return true;
            }
        }

        // Inventory slot click — copy the clicked item's id into the (selected or first) filter slot.
        if (activeTab == Tab.LOGISTICS && button == 0) {
            int invSlot = invSlotAt(mouseX, mouseY);
            if (invSlot >= 0) {
                ItemStack stack = invStackAt(invSlot);
                if (!stack.isEmpty()) {
                    int target = (selectedFilter >= 0 && selectedFilter < FILTER_COUNT) ? selectedFilter : 0;
                    ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
                    if (key != null) {
                        String id = key.toString();
                        workingFilterIds[target] = id;
                        if (target == 0) {
                            workingPullItemId = id;
                        }
                        if (target == (selectedFilter >= 0 ? selectedFilter : 0) && pullItemField != null) {
                            pullItemField.setValue(id);
                            pullItemField.moveCursorToEnd(false);
                        }
                        // Auto-advance selection so successive clicks fill empty slots.
                        if (selectedFilter >= 0 && selectedFilter < FILTER_COUNT - 1) {
                            selectedFilter++;
                        }
                    }
                    return true;
                }
            }
        }

        // Paint palette swatch clicks.
        if (activeTab == Tab.PAINT && button == 0) {
            int left = (this.width - W) / 2;
            int top = (this.height - H) / 2;
            int gridX = left + (W - GRID_W) / 2;
            int gridY = top + 110;
            for (int i = 0; i < PALETTE.length; i++) {
                int col = i % COLS;
                int row = i / COLS;
                int sx = gridX + col * (SWATCH + SWATCH_GAP);
                int sy = gridY + row * (SWATCH + SWATCH_GAP);
                if (mouseX >= sx && mouseX < sx + SWATCH && mouseY >= sy && mouseY < sy + SWATCH) {
                    workingTint = PALETTE[i] & 0xFFFFFF;
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (activeTab == Tab.LOGISTICS && suggestionsVisible
                && mouseX >= sugX && mouseX < sugX + sugW
                && mouseY >= sugY && mouseY < sugY + sugH) {
            int rowH = this.font.lineHeight + 4;
            int visibleRows = Math.max(1, sugH / rowH);
            int maxScroll = Math.max(0, suggestions.size() - visibleRows);
            suggestionScroll = Math.max(0, Math.min(maxScroll, suggestionScroll - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private int filterSlotAt(double mx, double my) {
        for (int i = 0; i < FILTER_COUNT; i++) {
            int x = filterSlotsX + i * (FILTER_SLOT_SIZE + FILTER_SLOT_GAP);
            int y = filterSlotsY;
            if (mx >= x && mx < x + FILTER_SLOT_SIZE && my >= y && my < y + FILTER_SLOT_SIZE) return i;
        }
        return -1;
    }

    /** Returns the player inventory slot index under the cursor (0-8 hotbar, 9-35 main), or -1. */
    private int invSlotAt(double mx, double my) {
        for (int row = 0; row < INV_ROWS; row++) {
            int rowY = invY + row * (INV_SLOT_SIZE + INV_SLOT_GAP) + (row == 0 ? 0 : INV_HOTBAR_GAP);
            for (int col = 0; col < INV_COLS; col++) {
                int x = invX + col * (INV_SLOT_SIZE + INV_SLOT_GAP);
                if (mx >= x && mx < x + INV_SLOT_SIZE && my >= rowY && my < rowY + INV_SLOT_SIZE) {
                    // Row 0 = hotbar (slots 0-8); rows 1-3 = main inventory (slots 9-35).
                    if (row == 0) return col;
                    return 9 + (row - 1) * INV_COLS + col;
                }
            }
        }
        return -1;
    }

    /** Reads a player inventory stack at the given slot index (0-8 hotbar, 9-35 main). */
    private ItemStack invStackAt(int slot) {
        if (Minecraft.getInstance().player == null) return ItemStack.EMPTY;
        var inv = Minecraft.getInstance().player.getInventory();
        if (slot < 0 || slot >= inv.getContainerSize()) return ItemStack.EMPTY;
        return inv.getItem(slot);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Tab key: complete first suggestion when popup is open.
        if (activeTab == Tab.LOGISTICS && keyCode == 258 && suggestionsVisible
                && pullItemField != null && pullItemField.isFocused() && !suggestions.isEmpty()) {
            applySuggestion(suggestions.get(0));
            return true;
        }
        // Escape closes suggestions first, then the screen.
        if (activeTab == Tab.LOGISTICS && keyCode == 256 && suggestionsVisible) {
            suggestionsVisible = false;
            return true;
        }
        if (activeTab == Tab.RENAME && labelField != null && labelField.isFocused()) {
            if (keyCode == 257 || keyCode == 335) { commitRename(); return true; }
        }
        if (activeTab == Tab.LOGISTICS && (keyCode == 257 || keyCode == 335)) {
            // Don't apply if user is selecting a suggestion.
            if (suggestionsVisible) { suggestionsVisible = false; return true; }
            commitLogistics();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ── Rendering ─────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        // Plain dim overlay (avoid Screen.renderBackground which blurs the HUD too).
        g.fill(0, 0, this.width, this.height, 0xA0000000);
        int left = (this.width - W) / 2;
        int top = (this.height - H) / 2;

        // Outer panel
        drawPanel(g, left, top, left + W, top + H, 0xFF1E1E1E, 0xFF3A3A3A);

        // Title
        g.drawString(this.font, "Configure ByteChest", left + 14, top + 10, 0xFFE0E0E0, false);

        // Active-tab indicator (orange underline)
        int tabX = switch (activeTab) {
            case RENAME -> left + 14;
            case PAINT -> left + 110;
            case LOGISTICS -> left + 206;
        };
        int tabW = activeTab == Tab.LOGISTICS ? 110 : 90;
        g.fill(tabX, top + 54, tabX + tabW, top + 56, 0xFFE05030);

        super.render(g, mx, my, pt);

        if (activeTab == Tab.PAINT) {
            renderPaintTab(g, left, top);
        } else if (activeTab == Tab.RENAME) {
            g.drawString(this.font, "Label (max 32 chars):", left + 14, top + 66, 0xFFC0C0C0, false);
        } else {
            renderLogisticsTab(g, left, top, mx, my);
        }
    }

    private void renderPaintTab(GuiGraphics g, int left, int top) {
        // Label + current preview swatch
        g.drawString(this.font, "Current tint:", left + 14, top + 82, 0xFFC0C0C0, false);
        g.fill(left + 90, top + 76, left + 90 + 64, top + 76 + 22, 0xFF000000);
        g.fill(left + 92, top + 78, left + 90 + 62, top + 76 + 20, 0xFF000000 | (workingTint & 0xFFFFFF));

        // Palette grid (centered horizontally)
        int gridX = left + (W - GRID_W) / 2;
        int gridY = top + 110;
        for (int i = 0; i < PALETTE.length; i++) {
            int col = i % COLS;
            int row = i / COLS;
            int sx = gridX + col * (SWATCH + SWATCH_GAP);
            int sy = gridY + row * (SWATCH + SWATCH_GAP);
            g.fill(sx, sy, sx + SWATCH, sy + SWATCH, 0xFF000000 | (PALETTE[i] & 0xFFFFFF));
            if ((PALETTE[i] & 0xFFFFFF) == (workingTint & 0xFFFFFF)) {
                g.fill(sx - 1, sy - 1, sx + SWATCH + 1, sy, 0xFFFFFFFF);
                g.fill(sx - 1, sy + SWATCH, sx + SWATCH + 1, sy + SWATCH + 1, 0xFFFFFFFF);
                g.fill(sx - 1, sy, sx, sy + SWATCH, 0xFFFFFFFF);
                g.fill(sx + SWATCH, sy, sx + SWATCH + 1, sy + SWATCH, 0xFFFFFFFF);
            }
        }
    }

    private void renderLogisticsTab(GuiGraphics g, int left, int top, int mx, int my) {
        // Subtitle
        g.drawString(this.font,
                "Maintain stock from AE2 storage and optionally push items to an adjacent inventory.",
                left + 14, top + 56, 0xFF909090, false);

        // ── PULL section panel ──
        int pullY = top + 66;
        int pullH = 124;
        drawSection(g, left + 14, pullY, left + W - 14, pullY + pullH, "PULL FROM AE2", workingPullEnabled);

        // Field labels
        g.drawString(this.font, "Item ID", left + 28, pullY + 26, 0xFFB0B0B0, false);
        g.drawString(this.font, "Keep #",  left + 360, pullY + 26, 0xFFB0B0B0, false);
        g.drawString(this.font, "Filter Items (left-click to select / right-click to clear):",
                left + 28, pullY + 64, 0xFFB0B0B0, false);

        // Filter slots
        for (int i = 0; i < FILTER_COUNT; i++) {
            int x = filterSlotsX + i * (FILTER_SLOT_SIZE + FILTER_SLOT_GAP);
            int y = filterSlotsY;
            // Slot background
            int bg = (i == selectedFilter) ? 0xFF2A2A2A : 0xFF161616;
            int border = (i == selectedFilter) ? 0xFFE05030 : 0xFF3A3A3A;
            drawPanel(g, x, y, x + FILTER_SLOT_SIZE, y + FILTER_SLOT_SIZE, bg, border);
            // Item icon if set
            String id = workingFilterIds[i];
            if (id != null && !id.isEmpty()) {
                ItemStack stack = stackFromId(id);
                if (!stack.isEmpty()) {
                    g.renderItem(stack, x + 3, y + 3);
                } else {
                    // Unknown id — show a small "?" glyph
                    g.drawString(this.font, "?", x + 8, y + 7, 0xFFE05030, false);
                }
            }
        }

        // Tooltip on hover for filter slots
        int hover = filterSlotAt(mx, my);
        if (hover >= 0) {
            String id = workingFilterIds[hover];
            if (id != null && !id.isEmpty()) {
                ItemStack stack = stackFromId(id);
                if (!stack.isEmpty()) {
                    g.renderTooltip(this.font, stack, mx, my);
                } else {
                    g.renderTooltip(this.font, Component.literal(id + " (unknown)"), mx, my);
                }
            } else {
                g.renderTooltip(this.font, Component.literal("Empty filter slot"), mx, my);
            }
        }

        // ── PUSH section panel ──
        int pushY = pullY + 130;
        int pushH = 78;
        drawSection(g, left + 14, pushY, left + W - 14, pushY + pushH, "PUSH TO ADJACENT", workingPushEnabled);

        g.drawString(this.font, "Direction (click to select):", left + 28, pushY + 22, 0xFFB0B0B0, false);
        // Move/Tick label sits above the EditBox we placed to the right of the side picker.
        int dirBlockW = 3 * 92 + 2 * 4 + 8;
        g.drawString(this.font, "Items / Tick", left + 28 + dirBlockW, pushY + 22, 0xFFB0B0B0, false);

        // ── Inventory section ──
        renderInventory(g, left, pushY + pushH + 10, mx, my);

        // ── Suggestion popup (last so it draws on top) ──
        if (suggestionsVisible && pullItemField != null) {
            renderSuggestions(g, mx, my);
        }
    }

    private void renderInventory(GuiGraphics g, int left, int sectionY, int mx, int my) {
        int invTotalW = INV_COLS * INV_SLOT_SIZE + (INV_COLS - 1) * INV_SLOT_GAP;
        int invTotalH = INV_ROWS * INV_SLOT_SIZE + (INV_ROWS - 1) * INV_SLOT_GAP + INV_HOTBAR_GAP;
        int sectionH = invTotalH + 24;

        drawSection(g, left + 14, sectionY, left + W - 14, sectionY + sectionH, "YOUR INVENTORY", true);

        g.drawString(this.font,
                "Click an item to assign it to the selected filter slot.",
                left + 28, sectionY + 22, 0xFFB0B0B0, false);

        // Sync invY in case layout shifted (defensive — initLogisticsTab already set it).
        int gridTopY = sectionY + 36;
        invY = gridTopY;
        invX = left + (W - invTotalW) / 2;

        var player = Minecraft.getInstance().player;
        if (player == null) return;
        var inv = player.getInventory();

        int hoverInvSlot = invSlotAt(mx, my);

        for (int row = 0; row < INV_ROWS; row++) {
            int rowY = invY + row * (INV_SLOT_SIZE + INV_SLOT_GAP) + (row == 0 ? 0 : INV_HOTBAR_GAP);
            for (int col = 0; col < INV_COLS; col++) {
                int x = invX + col * (INV_SLOT_SIZE + INV_SLOT_GAP);
                int slotIdx = (row == 0) ? col : (9 + (row - 1) * INV_COLS + col);

                boolean hover = (hoverInvSlot == slotIdx);
                int bg = hover ? 0xFF333333 : 0xFF161616;
                int border = hover ? 0xFFE0A030 : 0xFF3A3A3A;
                drawPanel(g, x, rowY, x + INV_SLOT_SIZE, rowY + INV_SLOT_SIZE, bg, border);

                if (slotIdx >= 0 && slotIdx < inv.getContainerSize()) {
                    ItemStack stack = inv.getItem(slotIdx);
                    if (!stack.isEmpty()) {
                        g.renderItem(stack, x + 2, rowY + 2);
                        g.renderItemDecorations(this.font, stack, x + 2, rowY + 2);
                    }
                }
            }
        }

        // Tooltip on hovered inventory slot
        if (hoverInvSlot >= 0) {
            ItemStack stack = invStackAt(hoverInvSlot);
            if (!stack.isEmpty()) {
                g.renderTooltip(this.font, stack, mx, my);
            }
        }
    }

    private void renderSuggestions(GuiGraphics g, int mx, int my) {
        int rowH = this.font.lineHeight + 4;
        int maxRows = 8;
        int rows = Math.min(maxRows, suggestions.size());
        if (rows <= 0) return;

        sugX = pullItemField.getX();
        sugY = pullItemField.getY() + pullItemField.getHeight();
        sugW = pullItemField.getWidth();
        sugH = rows * rowH + 4;

        drawPanel(g, sugX, sugY, sugX + sugW, sugY + sugH, 0xFF101010, 0xFF505050);

        int visibleRows = Math.max(1, sugH / rowH);
        int maxScroll = Math.max(0, suggestions.size() - visibleRows);
        suggestionScroll = Math.max(0, Math.min(maxScroll, suggestionScroll));

        for (int i = 0; i < rows; i++) {
            int idx = suggestionScroll + i;
            if (idx >= suggestions.size()) break;
            int rx = sugX + 4;
            int ry = sugY + 2 + i * rowH;
            boolean hover = mx >= sugX && mx < sugX + sugW && my >= ry && my < ry + rowH;
            if (hover) g.fill(sugX + 1, ry, sugX + sugW - 1, ry + rowH, 0xFF2A2A2A);

            String full = suggestions.get(idx);
            // Draw icon + text
            ItemStack stack = stackFromId(full);
            if (!stack.isEmpty()) g.renderItem(stack, rx, ry - 1);
            int textColor = hover ? 0xFFFFFFFF : 0xFFC0C0C0;
            g.drawString(this.font, full, rx + 20, ry + 2, textColor, false);
        }
    }

    /** Resolve a registry id to a representative ItemStack; empty if unknown. */
    private static ItemStack stackFromId(String id) {
        if (id == null || id.isEmpty()) return ItemStack.EMPTY;
        ResourceLocation rl = ResourceLocation.tryParse(id.contains(":") ? id : ("minecraft:" + id));
        if (rl == null) return ItemStack.EMPTY;
        if (!BuiltInRegistries.ITEM.containsKey(rl)) return ItemStack.EMPTY;
        Item item = BuiltInRegistries.ITEM.get(rl);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    /** Draws a filled panel with a 1px outer border. */
    private static void drawPanel(GuiGraphics g, int x0, int y0, int x1, int y1, int fill, int border) {
        g.fill(x0, y0, x1, y1, fill);
        g.fill(x0, y0, x1, y0 + 1, border);
        g.fill(x0, y1 - 1, x1, y1, border);
        g.fill(x0, y0, x0 + 1, y1, border);
        g.fill(x1 - 1, y0, x1, y1, border);
    }

    /** Draws a section panel with a header strip and ON/OFF status pill. */
    private void drawSection(GuiGraphics g, int x0, int y0, int x1, int y1, String header, boolean enabled) {
        drawPanel(g, x0, y0, x1, y1, 0xFF181818, 0xFF3A3A3A);
        // Header strip
        g.fill(x0, y0, x1, y0 + 18, 0xFF222222);
        g.fill(x0, y0 + 18, x1, y0 + 19, 0xFF3A3A3A);
        g.drawString(this.font, header, x0 + 10, y0 + 5, 0xFFE0E0E0, false);
        // Status pill (top-right)
        int pillW = 44;
        int pillH = 12;
        int px = x1 - pillW - 90;
        int py = y0 + 4;
        int pillFill = enabled ? 0xFF2A6A2A : 0xFF5A2A2A;
        int pillBorder = enabled ? 0xFF55C55B : 0xFFC55555;
        drawPanel(g, px, py, px + pillW, py + pillH, pillFill, pillBorder);
        String label = enabled ? "ENABLED" : "DISABLED";
        int tw = this.font.width(label);
        g.drawString(this.font, label, px + (pillW - tw) / 2, py + 2, 0xFFFFFFFF, false);
    }

    private static int parsePositiveInt(String s, int fallback) {
        try { return Math.max(0, Integer.parseInt(s.trim())); }
        catch (Exception ignored) { return fallback; }
    }

    private static String sideName(Direction side) {
        return switch (side) {
            case DOWN -> "Down";
            case UP -> "Up";
            case NORTH -> "North";
            case SOUTH -> "South";
            case WEST -> "West";
            case EAST -> "East";
        };
    }

    /**
     * Returns a short label describing the inventory adjacent to this chest in
     * the given direction, or "—" if there is no inventory there.
     */
    private String describeNeighbor(Direction dir) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return "—";
        BlockPos neighbour = pos.relative(dir);
        var state = mc.level.getBlockState(neighbour);
        if (state.isAir()) return "—";
        // Probe ItemHandler capability from the appropriate side.
        var handler = mc.level.getCapability(
                net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                neighbour, dir.getOpposite());
        String name = state.getBlock().getName().getString();
        // Trim long names so the buttons stay tidy.
        if (name.length() > 11) name = name.substring(0, 10) + "…";
        return (handler != null) ? name : "(" + name + ")";
    }

    private static Direction nextSide(Direction side) {
        Direction[] dirs = Direction.values();
        int idx = 0;
        for (int i = 0; i < dirs.length; i++) if (dirs[i] == side) { idx = i; break; }
        return dirs[(idx + 1) % dirs.length];
    }

    private static int[] buildPalette() {
        int hues = 12;
        int rows = 4;
        int[] out = new int[hues * rows];
        int idx = 0;
        for (int row = 0; row < rows; row++) {
            float brightness = 0.35f + row * 0.22f;
            for (int h = 0; h < hues; h++) {
                float hue = h / (float) hues;
                int rgb = java.awt.Color.HSBtoRGB(hue, 0.85f, Math.min(1.0f, brightness));
                out[idx++] = rgb & 0xFFFFFF;
            }
        }
        for (int i = 0; i < hues; i++) {
            int v = (int) (i * (255.0 / (hues - 1)));
            out[(rows - 1) * hues + i] = (v << 16) | (v << 8) | v;
        }
        return out;
    }
}

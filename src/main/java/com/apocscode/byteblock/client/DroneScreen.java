package com.apocscode.byteblock.client;

import com.apocscode.byteblock.menu.DroneMenu;
import com.apocscode.byteblock.network.PackUpEntityPayload;
import com.apocscode.byteblock.network.SetDroneHomePayload;
import com.apocscode.byteblock.network.SetEntityChannelPayload;
import com.apocscode.byteblock.network.SetEntityLabelPayload;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Drone inventory screen — 3×3 cargo + battery + label EditBox + fuel bar.
 */
public class DroneScreen extends AbstractContainerScreen<DroneMenu> {

    private EditBox labelField;
    private boolean labelSynced;

    public DroneScreen(DroneMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = 176;
        this.imageHeight = 186;
        this.inventoryLabelY = 92;
        this.titleLabelX = 8;
        this.titleLabelY = 6;
    }

    @Override
    protected void init() {
        super.init();
        labelField = new EditBox(this.font, leftPos + 36, topPos + 4, 134, 12, Component.literal("Label"));
        labelField.setMaxLength(32);
        labelField.setBordered(false);
        labelField.setTextColor(0xFFFFFFFF);
        addRenderableWidget(labelField);
        if (!labelSynced) {
            var name = menu.getDrone().getCustomName();
            labelField.setValue(name != null ? name.getString() : "");
            labelSynced = true;
        }

        // Customize tab — opens paint picker for this drone (in floating header above frame)
        addRenderableWidget(Button.builder(Component.literal("Paint"),
                b -> this.minecraft.setScreen(new DroneCustomizeScreen(menu.getDrone())))
            .pos(leftPos + 6, topPos - 22)
            .size(48, 18)
            .build());

        // Pack Up — serialise entity into spawn egg and remove it from the world
        addRenderableWidget(Button.builder(Component.literal("Pack Up"),
                b -> PacketDistributor.sendToServer(new PackUpEntityPayload(menu.getDrone().getId())))
            .pos(leftPos + 56, topPos - 22)
            .size(46, 18)
            .build());

        // Set Home — pins drone's current position as home
        addRenderableWidget(Button.builder(Component.literal("Set Home"),
                b -> PacketDistributor.sendToServer(new SetDroneHomePayload(menu.getDrone().getId())))
            .pos(leftPos + 104, topPos - 22)
            .size(66, 18)
            .build());
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (labelField != null && labelField.isFocused()) {
            if (keyCode == 257 || keyCode == 335) {
                sendLabel();
                labelField.setFocused(false);
                return true;
            }
            return labelField.keyPressed(keyCode, scanCode, modifiers)
                || labelField.canConsumeInput();
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (labelField != null) sendLabel();
        super.onClose();
    }

    private void sendLabel() {
        if (labelField == null) return;
        PacketDistributor.sendToServer(new SetEntityLabelPayload(menu.getDrone().getId(), labelField.getValue()));
    }

    @Override
    protected void renderBg(GuiGraphics gui, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;

        // Floating header panel — extended to show home coords above the buttons.
        int hY = y - 38;
        gui.fill(x, hY, x + imageWidth, y - 1, 0xFFC6C6C6);
        gui.fill(x, hY, x + imageWidth, hY + 1, 0xFFFFFFFF);
        gui.fill(x, hY, x + 1, y - 1, 0xFFFFFFFF);
        gui.fill(x + imageWidth - 1, hY, x + imageWidth, y - 1, 0xFF555555);
        // Separator between coords row and buttons row.
        gui.fill(x + 1, y - 25, x + imageWidth - 1, y - 24, 0xFF999999);
        // Home coordinates display.
        BlockPos syncedHome = menu.getDrone().getSyncedHomePos();
        String homeText = syncedHome != null
                ? "Home: " + syncedHome.getX() + ", " + syncedHome.getY() + ", " + syncedHome.getZ()
                : "Home: not set";
        int homeTextColor = syncedHome != null ? 0xFF404040 : 0xFF888888;
        gui.drawString(font, homeText, x + 8, hY + 4, homeTextColor, false);

        // Channel widget: [-] CH:N [+] — right side of coords row.
        int ch = menu.getDrone().getSyncedBluetoothChannel();
        int cwX = x + 102, cwY = hY + 3;
        drawChannelWidget(gui, cwX, cwY, ch);

        gui.fill(x, y, x + imageWidth, y + imageHeight, 0xFFC6C6C6);
        gui.fill(x, y, x + imageWidth, y + 1, 0xFFFFFFFF);
        gui.fill(x, y, x + 1, y + imageHeight, 0xFFFFFFFF);
        gui.fill(x + imageWidth - 1, y, x + imageWidth, y + imageHeight, 0xFF555555);
        gui.fill(x, y + imageHeight - 1, x + imageWidth, y + imageHeight, 0xFF555555);

        gui.drawString(font, "Name:", x + 8, y + 6, 0xFF404040, false);

        // Cargo 3×3
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                renderSlotBg(gui, x + 61 + col * 18, y + 17 + row * 18);
            }
        }
        // Battery
        renderSlotBg(gui, x + 7, y + 35);
        gui.drawString(font, "FE", x + 26, y + 40, 0xFF404040, false);
        renderSlotBg(gui, x + 7, y + 55);
        gui.drawString(font, "G", x + 26, y + 60, 0xFF404040, false);

        // Upgrade slots (right column, beside fuel bar)
        for (int i = 0; i < 4; i++) {
            renderSlotBg(gui, x + 151, y + 17 + i * 18);
        }
        gui.drawString(font, "UP", x + 153, y + 5, 0xFF404040, false);

        // Fuel bar
        int barX = x + 138, barY = y + 17, barW = 10, barH = 54;
        gui.fill(barX, barY, barX + barW, barY + barH, 0xFF373737);
        int fuel = menu.getDrone().getSyncedFuel();
        int maxFuel = 72000;
        int pct = fuel * barH / maxFuel;
        // Gradient: red < 10% → orange → yellow → light green → green at full.
        for (int i = 0; i < pct; i++) {
            float frac = i / (float) barH;
            int color = RobotScreen.chargeColor(frac);
            int yRow = barY + barH - 1 - i;
            gui.fill(barX + 1, yRow, barX + barW - 1, yRow + 1, color);
        }

        gui.fill(x + 7, y + 89, x + imageWidth - 7, y + 90, 0xFF999999);

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                renderSlotBg(gui, x + 7 + col * 18, y + 103 + row * 18);
            }
        }
        for (int col = 0; col < 9; col++) {
            renderSlotBg(gui, x + 7 + col * 18, y + 161);
        }
    }

    private void renderSlotBg(GuiGraphics gui, int x, int y) {
        gui.fill(x, y, x + 18, y + 18, 0xFF8B8B8B);
        gui.fill(x, y, x + 18, y + 1, 0xFF373737);
        gui.fill(x, y, x + 1, y + 18, 0xFF373737);
        gui.fill(x + 1, y + 1, x + 17, y + 17, 0xFF8B8B8B);
    }

    /** Renders a small [-] CH:N [+] widget at (cwX, cwY) — 52×10 px. */
    private void drawChannelWidget(GuiGraphics gui, int cwX, int cwY, int ch) {
        // [-] button
        gui.fill(cwX, cwY, cwX + 10, cwY + 10, 0xFF555555);
        gui.fill(cwX + 1, cwY + 1, cwX + 9, cwY + 9, 0xFF777777);
        gui.drawString(font, "-", cwX + 2, cwY + 1, 0xFFFFFFFF, false);
        // channel text
        gui.drawString(font, "CH:" + ch, cwX + 13, cwY + 1, 0xFF404040, false);
        // [+] button
        gui.fill(cwX + 42, cwY, cwX + 52, cwY + 10, 0xFF555555);
        gui.fill(cwX + 43, cwY + 1, cwX + 51, cwY + 9, 0xFF777777);
        gui.drawString(font, "+", cwX + 44, cwY + 1, 0xFFFFFFFF, false);
    }

    @Override
    protected void renderLabels(GuiGraphics gui, int mouseX, int mouseY) {
        gui.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, 0x404040, false);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        super.render(gui, mouseX, mouseY, partialTick);
        int barX = leftPos + 138, barY = topPos + 17;
        if (mouseX >= barX && mouseX < barX + 10 && mouseY >= barY && mouseY < barY + 54) {
            int fuel = menu.getDrone().getSyncedFuel();
            gui.renderTooltip(this.font,
                Component.literal("Fuel: " + (fuel / 20) + "s"),
                mouseX, mouseY);
        }
        renderTooltip(gui, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Channel widget hit-test (x+102 to x+154, hY+3 to hY+13).
        int cwX = leftPos + 102, cwY = topPos - 35;
        if (mouseY >= cwY && mouseY < cwY + 10) {
            if (mouseX >= cwX && mouseX < cwX + 10) {
                int ch = menu.getDrone().getSyncedBluetoothChannel();
                PacketDistributor.sendToServer(new SetEntityChannelPayload(menu.getDrone().getId(), ch - 1));
                return true;
            }
            if (mouseX >= cwX + 42 && mouseX < cwX + 52) {
                int ch = menu.getDrone().getSyncedBluetoothChannel();
                PacketDistributor.sendToServer(new SetEntityChannelPayload(menu.getDrone().getId(), ch + 1));
                return true;
            }
        }
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (labelField != null && !labelField.isMouseOver(mouseX, mouseY) && labelField.isFocused()) {
            sendLabel();
            labelField.setFocused(false);
        }
        return handled;
    }
}

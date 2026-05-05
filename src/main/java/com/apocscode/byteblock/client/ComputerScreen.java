package com.apocscode.byteblock.client;

import com.apocscode.byteblock.computer.JavaOS;
import com.apocscode.byteblock.computer.OSEvent;
import com.apocscode.byteblock.computer.PixelBuffer;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Minecraft Screen that renders the ByteBlock computer display.
 * Uses a pixel-level framebuffer (640×400) uploaded to a GPU DynamicTexture
 * for crisp, high-resolution rendering. Maps keyboard + mouse events into OS events.
 */
public class ComputerScreen extends Screen {

    private static final int FB_W = PixelBuffer.SCREEN_W; // 640
    private static final int FB_H = PixelBuffer.SCREEN_H; // 400

    private final JavaOS os;

    // GPU texture pipeline
    private NativeImage nativeImage;
    private DynamicTexture dynamicTexture;
    private ResourceLocation textureLoc;

    // Layout
    private float scale;                // uniform framebuffer scale (text/icon size)
    private int renderW, renderH;       // framebuffer pixel size on MC screen (FB_W*scale)
    private int winW, winH;             // outer window content size (>= render size); extra space = bezel
    private int border, headerH;
    private int winX, winY;             // top-left of the window content (header bar starts at winY - headerH - border)
    private int termX, termY;           // top-left of the framebuffer area (centered inside winW/winH)
    private boolean positioned;
    private boolean fullscreen;
    private int savedWinX, savedWinY, savedWinW, savedWinH;
    private float savedUserScale;

    // Interaction
    private boolean dragging;
    private int resizeMode;             // 0=none, 1=right, 2=bottom, 3=corner
    private double dragOffX, dragOffY;
    private float userScale;            // 0 = auto-fit
    private int userWinW, userWinH;     // 0 = match render size
    private int clipboardSyncTicks;

    private static final float MIN_SCALE = 0.3f;
    private static final float MAX_SCALE = 4.0f;
    private static final int EDGE_GRAB = 8;

    // Title bar buttons (Windows-style)
    private Button btnMinimize, btnMaximize, btnClose;
    private static final int BTN_W = 20, BTN_H = 14;

    public ComputerScreen(JavaOS os) {
        super(Component.literal("ByteBlock Computer"));
        this.os = os;
    }

    @Override
    protected void init() {
        super.init();
        // Create GPU texture (once). Name is unique per screen instance so re-opening the
        // same robot/computer terminal multiple times can't collide with a previously
        // released texture name in the TextureManager.
        if (nativeImage == null) {
            nativeImage = new NativeImage(NativeImage.Format.RGBA, FB_W, FB_H, false);
            dynamicTexture = new DynamicTexture(nativeImage);
            String name = "byteblock_screen_" + java.util.UUID.randomUUID().toString().replace("-", "");
            textureLoc = Minecraft.getInstance().getTextureManager()
                    .register(name, dynamicTexture);
        }
        recalcLayout();
        // Title bar buttons — created fresh each init() (Minecraft clears widgets on resize)
        btnMinimize = Button.builder(Component.literal("_"), b -> this.onClose())
                .bounds(0, 0, BTN_W, BTN_H).build();
        btnMaximize = Button.builder(Component.literal("\u25a1"), b -> toggleFullscreen())
                .bounds(0, 0, BTN_W, BTN_H).build();
        btnClose = Button.builder(Component.literal("\u2715"), b -> this.onClose())
                .bounds(0, 0, BTN_W, BTN_H).build();
        addRenderableWidget(btnMinimize);
        addRenderableWidget(btnMaximize);
        addRenderableWidget(btnClose);
        updateTitleBarButtons();
    }

    /** Reposition the 3 title-bar buttons to sit in the right side of the header. */
    private void updateTitleBarButtons() {
        if (btnClose == null) return;
        int totalW = BTN_W * 3 + 4; // 3 buttons + 2px gaps
        int bx = winX + winW + border - totalW;
        int by = winY - headerH - border + (headerH + border * 2 - BTN_H) / 2;
        btnMinimize.setPosition(bx, by);
        btnMaximize.setPosition(bx + BTN_W + 2, by);
        btnClose.setPosition(bx + (BTN_W + 2) * 2, by);
    }

    private void recalcLayout() {
        // Auto-fit scale based on the whole MC screen.
        float fitW = this.width * (fullscreen ? 0.98f : 0.82f);
        float fitH = this.height * (fullscreen ? 0.94f : 0.80f);
        float autoScale = Math.min(fitW / FB_W, fitH / FB_H);
        float newScale = userScale > 0 ? userScale : Math.max(0.4f, autoScale);
        newScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, newScale));

        boolean scaleChanged = (newScale != scale);
        scale = newScale;

        renderW = Math.round(FB_W * scale);
        renderH = Math.round(FB_H * scale);
        border = Math.max(3, Math.round(3 * scale));
        headerH = Math.max(18, Math.round(16 * scale));

        if (fullscreen) {
            // Fullscreen: make the framebuffer fill the available area (text/icons scale up).
            int availW = this.width - border * 2;
            int availH = this.height - headerH - border * 3;
            float fsScale = Math.min((float) availW / FB_W, (float) availH / FB_H);
            scale = fsScale;
            renderW = Math.round(FB_W * scale);
            renderH = Math.round(FB_H * scale);
            // Recompute chrome dims at the new scale.
            border = Math.max(3, Math.round(3 * scale));
            headerH = Math.max(18, Math.round(16 * scale));
            // Window matches framebuffer (no extra bezel padding).
            winW = renderW;
            winH = renderH;
            winX = (this.width - winW) / 2;
            winY = (this.height - winH - headerH - border) / 2 + headerH + border;
            termX = winX;
            termY = winY;
            positioned = true;
            updateTitleBarButtons();
            return;
        }

        // Windowed: window size = user-stretched (or = render size by default). Window can never be smaller than the framebuffer.
        winW = userWinW > 0 ? Math.max(userWinW, renderW) : renderW;
        winH = userWinH > 0 ? Math.max(userWinH, renderH) : renderH;

        // Re-center when auto-scale changes (GUI scale change) or first time.
        if (!positioned || (scaleChanged && userScale <= 0)) {
            winX = (this.width - winW) / 2;
            winY = (this.height - winH - headerH - border) / 2 + headerH + border;
            positioned = true;
        }
        termX = winX + (winW - renderW) / 2;
        termY = winY + (winH - renderH) / 2;
        updateTitleBarButtons();
    }

    private void toggleFullscreen() {
        if (!fullscreen) {
            savedWinX = winX;
            savedWinY = winY;
            savedWinW = winW;
            savedWinH = winH;
            savedUserScale = userScale;
            fullscreen = true;
            userScale = 0f;
            recalcLayout();
            return;
        }

        fullscreen = false;
        userScale = savedUserScale;
        userWinW = savedWinW;
        userWinH = savedWinH;
        recalcLayout();
        winX = savedWinX;
        winY = savedWinY;
        winW = savedWinW;
        winH = savedWinH;
        termX = winX + (winW - renderW) / 2;
        termY = winY + (winH - renderH) / 2;
        positioned = true;
        updateTitleBarButtons();
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        // Close screen automatically when OS shuts down
        if (os.isShutdown()) {
            this.onClose();
            return;
        }

        renderBackground(gfx, mouseX, mouseY, partialTick);

        // Dynamic scale update — recalculate when window/GUI scale changes
        float fitW = this.width * (fullscreen ? 0.98f : 0.82f);
        float fitH = this.height * (fullscreen ? 0.94f : 0.80f);
        float autoScale = Math.min(fitW / FB_W, fitH / FB_H);
        float effectiveScale = userScale > 0 ? userScale : Math.max(0.4f, autoScale);
        if (effectiveScale != scale) recalcLayout();

        // --- Monitor chrome (sized by window dims, not framebuffer) ---
        // Bezel
        gfx.fill(winX - border, winY - headerH - border,
                  winX + winW + border, winY + winH + border, 0xFF222222);
        // Inner bezel edge around the framebuffer
        gfx.fill(termX - 1, termY - 1,
                  termX + renderW + 1, termY + renderH + 1, 0xFF111111);
        // Header bar
        gfx.fill(winX - border, winY - headerH - border,
                  winX + winW + border, winY - 1, 0xFF1A1A2E);
        // Header text
        String headerText = os.getLabel() + " [" + os.getComputerId().toString().substring(0, 8) + "]";
        float hScale = Math.max(0.45f, Math.min(0.6f, scale * 0.35f));
        gfx.pose().pushPose();
        gfx.pose().scale(hScale, hScale, 1.0f);
        gfx.drawString(this.font, headerText,
                (int)((winX + 4) / hScale), (int)((winY - headerH + 2) / hScale), 0xAABBCC, false);
        gfx.pose().popPose();
        // Power indicator
        int indicatorColor = os.isRunning() ? 0xFF00FF00 : (os.isBooting() ? 0xFFFFAA00 : 0xFF555555);
        gfx.fill(winX + winW - 8, winY - headerH + 2,
                  winX + winW - 2, winY - headerH + 8, indicatorColor);

        // Title bar buttons are Minecraft widgets (addRenderableWidget), rendered automatically.

        // --- Upload framebuffer to GPU texture ---
        uploadPixelBuffer();

        // --- Blit the texture (single draw call!) ---
        gfx.blit(textureLoc,
                termX, termY, 0.0f, 0.0f,
                renderW, renderH, renderW, renderH);

        // --- Cursor overlay ---
        if (os.isRunning() && os.getForegroundProgram() != null) {
            var fg = os.getForegroundProgram();
            if (fg.isLastCursorBlink() && (System.currentTimeMillis() / 500) % 2 == 0) {
                int cx = fg.getLastCursorX();
                int cy = fg.getLastCursorY();
                // Map cell coords to screen coords
                float cellW = (float) renderW / PixelBuffer.TEXT_COLS;
                float cellH = (float) renderH / PixelBuffer.TEXT_ROWS;
                int px = termX + (int)(cx * cellW);
                int py = termY + (int)((cy + 1) * cellH) - 2;
                gfx.fill(px, py, px + (int)cellW, py + 2, 0xFFFFFFFF);
            }
        }

        // Reboot UX: explicit black screen + short countdown so reboot feedback is visible.
        if (os.isBooting() && os.isRebooting()) {
            gfx.fill(termX, termY, termX + renderW, termY + renderH, 0xFF000000);
            int seconds = os.getBootSecondsRemaining();
            String msg = "Rebooting" + (seconds > 0 ? " in " + seconds + "..." : "...");
            int tx = termX + (renderW - this.font.width(msg)) / 2;
            int ty = termY + (renderH / 2) - 4;
            gfx.drawString(this.font, msg, tx, ty, 0xFFDDDDDD, false);
        }

        // --- Resize handles (windowed mode only) ---
        if (!fullscreen) {
            int gs = Math.max(8, Math.round(6 * scale));
            int rx = winX + winW + border;
            int ry = winY + winH + border;
            // Right edge highlight
            gfx.fill(rx - 2, winY + winH / 4, rx, winY + winH * 3 / 4, 0xFF555555);
            // Bottom edge highlight
            gfx.fill(winX + winW / 4, ry - 2, winX + winW * 3 / 4, ry, 0xFF555555);
            // Corner grip
            gfx.fill(rx - gs, ry - gs, rx, ry, 0xFF444444);
            gfx.fill(rx - gs + 1, ry - gs + 1, rx - 1, ry - 1, 0xFF888888);
        }

        // --- Clipboard sync (program → MC system clipboard) ---
        String clipOut = os.consumeClipboard();
        if (clipOut != null && minecraft != null) {
            minecraft.keyboardHandler.setClipboard(clipOut);
        }
        if (clipboardSyncTicks > 0 && minecraft != null) {
            clipboardSyncTicks--;
            String clip = os.getClipboard();
            if (clip != null && !clip.isEmpty()) {
                minecraft.keyboardHandler.setClipboard(clip);
            }
        }

        // Render Screen widgets last so the title-bar controls sit above the terminal chrome.
        // NOTE: do NOT call super.render() — it would call renderBackground() again, dimming our content.
        for (var renderable : this.renderables) {
            renderable.render(gfx, mouseX, mouseY, partialTick);
        }
    }

    /**
     * Copy PixelBuffer data → NativeImage, then upload to GPU.
     * Converts from ARGB (PixelBuffer) to ABGR (NativeImage's OpenGL format).
     */
    private void uploadPixelBuffer() {
        PixelBuffer pb = os.getPixelBuffer();
        int[] pixels = pb.getPixels();
        for (int y = 0; y < FB_H; y++) {
            int off = y * FB_W;
            for (int x = 0; x < FB_W; x++) {
                int argb = pixels[off + x];
                // Convert ARGB → ABGR for OpenGL
                int a = argb & 0xFF000000;
                int r = (argb >> 16) & 0xFF;
                int g = argb & 0x0000FF00;
                int b = (argb << 16) & 0x00FF0000;
                nativeImage.setPixelRGBA(x, y, a | b | g | r);
            }
        }
        dynamicTexture.upload();
    }

    // --- Input handling ---

    /** Convert MC screen pixel coords to PixelBuffer pixel coords */
    private int[] screenToPixel(double mouseX, double mouseY) {
        int px = (int)((mouseX - termX) * FB_W / renderW);
        int py = (int)((mouseY - termY) * FB_H / renderH);
        if (px >= 0 && px < FB_W && py >= 0 && py < FB_H) {
            return new int[]{px, py};
        }
        return null;
    }

    /** Convert MC screen coords to terminal cell coords (for OS events) */
    private int[] screenToCell(double mouseX, double mouseY) {
        int[] px = screenToPixel(mouseX, mouseY);
        if (px != null) {
            return new int[]{px[0] / PixelBuffer.CELL_W, px[1] / PixelBuffer.CELL_H};
        }
        return null;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 && (modifiers & 2) != 0) { // Ctrl+Enter
            toggleFullscreen();
            return true;
        }
        if (keyCode == 84 && (modifiers & 2) != 0) { // Ctrl+T
            os.pushEvent(new OSEvent(OSEvent.Type.TERMINATE));
            return true;
        }
        if (keyCode == 81 && (modifiers & 2) != 0) { // Ctrl+Q — close window, leave OS/programs running
            this.onClose();
            return true;
        }
        if (keyCode == 82 && (modifiers & 2) != 0) { // Ctrl+R
            os.pushEvent(new OSEvent(OSEvent.Type.REBOOT));
            return true;
        }
        if (keyCode == 86 && (modifiers & 2) != 0) { // Ctrl+V
            String clipboard = this.minecraft.keyboardHandler.getClipboard();
            if (clipboard != null && !clipboard.isEmpty()) {
                os.pushEvent(new OSEvent(OSEvent.Type.PASTE, clipboard));
            }
            return true;
        }
        if ((keyCode == 67 || keyCode == 88) && (modifiers & 2) != 0) { // Ctrl+C / Ctrl+X
            os.pushEvent(new OSEvent(OSEvent.Type.KEY, keyCode, 0, modifiers));
            // Let the program handle copy/cut first, then mirror OS clipboard to system clipboard.
            clipboardSyncTicks = 3;
            return true;
        }
        // Escape is forwarded to the OS so programs can handle it (e.g., dismiss dialogs,
        // exit test patterns). DesktopProgram shuts down the OS when nothing is open,
        // which triggers onClose() via the isShutdown() check in render().
        os.pushEvent(new OSEvent(OSEvent.Type.KEY, keyCode, 0, modifiers));
        return true;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        os.pushEvent(new OSEvent(OSEvent.Type.KEY_UP, keyCode));
        return true;
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (c >= 32 && c < 127) {
            os.pushEvent(new OSEvent(OSEvent.Type.CHAR, String.valueOf(c)));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Let Minecraft widgets (title-bar buttons) handle clicks first
        boolean widgetConsumed = super.mouseClicked(mouseX, mouseY, button);
        if (widgetConsumed) return true;

        if (button == 0) {
            // Resize zones (windowed mode only) — right edge / bottom edge / corner
            if (!fullscreen) {
                int rx = winX + winW + border;
                int ry = winY + winH + border;
                boolean nearRight = mouseX >= rx - EDGE_GRAB && mouseX <= rx
                                 && mouseY >= winY - headerH - border && mouseY <= ry;
                boolean nearBottom = mouseY >= ry - EDGE_GRAB && mouseY <= ry
                                 && mouseX >= winX - border && mouseX <= rx;
                if (nearRight && nearBottom) { resizeMode = 3; return true; }
                if (nearRight)               { resizeMode = 1; return true; }
                if (nearBottom)              { resizeMode = 2; return true; }
            }

            // Header drag (windowed mode only) — exclude button area on the right
            if (!fullscreen) {
                int btnAreaX = btnMinimize != null ? btnMinimize.getX() - 2 : winX + winW;
                if (mouseX >= winX - border && mouseX <= btnAreaX &&
                    mouseY >= winY - headerH - border && mouseY <= winY) {
                    dragging = true;
                    dragOffX = mouseX - winX;
                    dragOffY = mouseY - winY;
                    return true;
                }
            }
        }
        // Send both cell and pixel coordinates to OS
        int[] cell = screenToCell(mouseX, mouseY);
        if (cell != null) {
            int[] px = screenToPixel(mouseX, mouseY);
            os.pushEvent(new OSEvent(OSEvent.Type.MOUSE_CLICK, button, cell[0], cell[1]));
            os.pushEvent(new OSEvent(OSEvent.Type.MOUSE_CLICK_PX, button, px[0], px[1]));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging) { dragging = false; return true; }
        if (resizeMode != 0) { resizeMode = 0; return true; }
        int[] cell = screenToCell(mouseX, mouseY);
        if (cell != null) {
            os.pushEvent(new OSEvent(OSEvent.Type.MOUSE_UP, button, cell[0], cell[1]));
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging) {
            winX = (int)(mouseX - dragOffX);
            winY = (int)(mouseY - dragOffY);
            termX = winX + (winW - renderW) / 2;
            termY = winY + (winH - renderH) / 2;
            updateTitleBarButtons();
            return true;
        }
        if (resizeMode != 0) {
            int minW = Math.round(FB_W * MIN_SCALE);
            int minH = Math.round(FB_H * MIN_SCALE);
            int maxW = this.width;
            int maxH = this.height;
            if (resizeMode == 1 || resizeMode == 3) {
                int newW = (int)(mouseX - winX);
                userWinW = Math.max(minW, Math.min(maxW, newW));
            }
            if (resizeMode == 2 || resizeMode == 3) {
                int newH = (int)(mouseY - winY);
                userWinH = Math.max(minH, Math.min(maxH, newH));
            }
            recalcLayout();
            return true;
        }
        int[] cell = screenToCell(mouseX, mouseY);
        if (cell != null) {
            os.pushEvent(new OSEvent(OSEvent.Type.MOUSE_DRAG, button, cell[0], cell[1]));
            int[] pxCoord = screenToPixel(mouseX, mouseY);
            if (pxCoord != null) {
                os.pushEvent(new OSEvent(OSEvent.Type.MOUSE_DRAG_PX, button, pxCoord[0], pxCoord[1]));
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizAmount, double vertAmount) {
        int[] cell = screenToCell(mouseX, mouseY);
        if (cell != null) {
            int dir = vertAmount > 0 ? -1 : 1;
            os.pushEvent(new OSEvent(OSEvent.Type.MOUSE_SCROLL, dir, cell[0], cell[1]));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizAmount, vertAmount);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        super.removed();
        // Clean up GPU resources
        if (textureLoc != null) {
            Minecraft.getInstance().getTextureManager().release(textureLoc);
            textureLoc = null;
        }
        dynamicTexture = null;
        nativeImage = null;
    }
}

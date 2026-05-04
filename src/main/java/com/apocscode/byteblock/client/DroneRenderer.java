package com.apocscode.byteblock.client;

import com.apocscode.byteblock.entity.DroneEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * White quad-copter drone renderer — central body, 4 arms, 4 rotor discs, landing skids.
 */
public class DroneRenderer extends EntityRenderer<DroneEntity> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/misc/white.png");

    public DroneRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.3f;
    }

    @Override
    public void render(DroneEntity entity, float yaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight) {
        // Low-fuel visual alert — spawn smoke particles below the drone (~20 TPS rate-limited)
        if (entity.level() != null && entity.getFuel() > 0 && entity.getFuel() < 400
                && entity.tickCount % 4 == 0) {
            double dx = (entity.getRandom().nextDouble() - 0.5) * 0.3;
            double dz = (entity.getRandom().nextDouble() - 0.5) * 0.3;
            entity.level().addParticle(ParticleTypes.SMOKE,
                    entity.getX() + dx, entity.getY() - 0.1, entity.getZ() + dz,
                    0.0, -0.02, 0.0);
        }
        // Defender mode — angry red particles around the drone while armed
        if (entity.level() != null && entity.isDefender() && entity.tickCount % 10 == 0) {
            double dx = (entity.getRandom().nextDouble() - 0.5) * 0.8;
            double dy = entity.getRandom().nextDouble() * 0.4;
            double dz = (entity.getRandom().nextDouble() - 0.5) * 0.8;
            entity.level().addParticle(ParticleTypes.ANGRY_VILLAGER,
                    entity.getX() + dx, entity.getY() + 0.3 + dy, entity.getZ() + dz,
                    0.0, 0.0, 0.0);
        }

        pose.pushPose();

        // Gentle hover bob
        float bob = (float) Math.sin((entity.tickCount + partialTick) * 0.1) * 0.04f;
        pose.translate(0.0, bob, 0.0);
        pose.mulPose(Axis.YP.rotationDegrees(-yaw));

        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        PoseStack.Pose last = pose.last();
        Matrix4f mat = last.pose();

        // Resolve paint slots — defaults match the original hard-coded look.
        com.apocscode.byteblock.entity.EntityPaint paint = entity.getPaint();
        int[] cBody   = unpack(paint.get("body",   rgb(230, 230, 235)));
        int[] cTrim   = unpack(paint.get("trim",   rgb(180, 180, 185)));
        int[] cArms   = unpack(paint.get("arms",   rgb(215, 215, 225)));
        int[] cBlades = unpack(paint.get("blades", rgb(60,  60,  65)));

        // White body colors (legacy locals — kept for any downstream code, but
        // primary cubes now use the paint-resolved slots above).
        int bw = cBody[0], bg = cBody[1], bb = cBody[2];
        int dw = cTrim[0], dg = cTrim[1], db = cTrim[2];
        int rw = cBlades[0], rg = cBlades[1], rb = cBlades[2];

        // === CENTRAL BODY ===
        // Main hull — rounded-ish square
        drawBox(vc, mat, last, -0.12f, 0.08f, -0.12f, 0.12f, 0.18f, 0.12f,
                bw, bg, bb, packedLight);
        // Top dome / camera bump
        drawBox(vc, mat, last, -0.06f, 0.18f, -0.06f, 0.06f, 0.22f, 0.06f,
                dw, dg, db, packedLight);
        // Bottom sensor / camera
        drawBox(vc, mat, last, -0.04f, 0.05f, -0.04f, 0.04f, 0.08f, 0.04f,
                40, 40, 45, packedLight);
        // Camera lens (front, blue dot)
        drawBox(vc, mat, last, -0.02f, 0.06f, -0.05f, 0.02f, 0.08f, -0.04f,
                30, 120, 220, packedLight);

        // Front direction indicator — small green stripe
        drawBox(vc, mat, last, -0.08f, 0.17f, -0.13f, 0.08f, 0.19f, -0.12f,
                30, 200, 50, packedLight);
        // Rear indicator — red stripe
        drawBox(vc, mat, last, -0.08f, 0.17f, 0.12f, 0.08f, 0.19f, 0.13f,
                220, 40, 40, packedLight);
        // Laser emitter — glowing red nozzle on front underside (only when laser installed)
        if (entity.hasLaserUpgrade()) {
            boolean laserPulse = (entity.tickCount % 6) < 4;
            int emR = laserPulse ? 255 : 180, emG = 30, emB = 30;
            drawBox(vc, mat, last, -0.025f, 0.07f, -0.17f, 0.025f, 0.12f, -0.14f,
                    emR, emG, emB, packedLight);
        }

        // === FOUR ARMS extending diagonally ===
        // Front-Left arm
        drawArm(vc, mat, last, -0.12f, 0.12f, -0.12f, -0.35f, 0.12f, -0.35f,
                cArms[0], cArms[1], cArms[2], packedLight);
        // Front-Right arm
        drawArm(vc, mat, last, 0.12f, 0.12f, -0.12f, 0.35f, 0.12f, -0.35f,
                cArms[0], cArms[1], cArms[2], packedLight);
        // Back-Left arm
        drawArm(vc, mat, last, -0.12f, 0.12f, 0.12f, -0.35f, 0.12f, 0.35f,
                cArms[0], cArms[1], cArms[2], packedLight);
        // Back-Right arm
        drawArm(vc, mat, last, 0.12f, 0.12f, 0.12f, 0.35f, 0.12f, 0.35f,
                cArms[0], cArms[1], cArms[2], packedLight);

        // === FOUR ROTOR MOTORS (cylindrical hubs) ===
        drawBox(vc, mat, last, -0.39f, 0.12f, -0.39f, -0.31f, 0.17f, -0.31f,
                dw - 20, dg - 20, db - 15, packedLight); // FL
        drawBox(vc, mat, last, 0.31f, 0.12f, -0.39f, 0.39f, 0.17f, -0.31f,
                dw - 20, dg - 20, db - 15, packedLight); // FR
        drawBox(vc, mat, last, -0.39f, 0.12f, 0.31f, -0.31f, 0.17f, 0.39f,
                dw - 20, dg - 20, db - 15, packedLight); // BL
        drawBox(vc, mat, last, 0.31f, 0.12f, 0.31f, 0.39f, 0.17f, 0.39f,
                dw - 20, dg - 20, db - 15, packedLight); // BR

        // === SPINNING ROTOR DISCS (flat) — stop when docked on charge pad ===
        boolean docked = entity.isDocked();
        float rotorSpin = docked ? 0f : ((entity.tickCount + partialTick) * 45f) % 360f;
        drawRotor(vc, mat, last, -0.35f, 0.175f, -0.35f, 0.12f, rotorSpin,
                rw, rg, rb, packedLight);
        drawRotor(vc, mat, last, 0.35f, 0.175f, -0.35f, 0.12f, -rotorSpin,
                rw, rg, rb, packedLight);
        drawRotor(vc, mat, last, -0.35f, 0.175f, 0.35f, 0.12f, -rotorSpin,
                rw, rg, rb, packedLight);
        drawRotor(vc, mat, last, 0.35f, 0.175f, 0.35f, 0.12f, rotorSpin,
                rw, rg, rb, packedLight);

        // === LANDING SKIDS ===
        // Two rails underneath
        drawBox(vc, mat, last, -0.2f, 0.0f, -0.15f, -0.16f, 0.05f, 0.15f,
                dw - 40, dg - 40, db - 35, packedLight);
        drawBox(vc, mat, last, 0.16f, 0.0f, -0.15f, 0.2f, 0.05f, 0.15f,
                dw - 40, dg - 40, db - 35, packedLight);
        // Vertical struts connecting skids to body
        drawBox(vc, mat, last, -0.18f, 0.05f, -0.08f, -0.16f, 0.10f, -0.04f,
                dw - 30, dg - 30, db - 25, packedLight);
        drawBox(vc, mat, last, -0.18f, 0.05f, 0.04f, -0.16f, 0.10f, 0.08f,
                dw - 30, dg - 30, db - 25, packedLight);
        drawBox(vc, mat, last, 0.16f, 0.05f, -0.08f, 0.18f, 0.10f, -0.04f,
                dw - 30, dg - 30, db - 25, packedLight);
        drawBox(vc, mat, last, 0.16f, 0.05f, 0.04f, 0.18f, 0.10f, 0.08f,
                dw - 30, dg - 30, db - 25, packedLight);

        // === SOLAR PANELS (on top dome when card installed) ===
        if (entity.hasSolarUpgrade()) {
            pose.pushPose();
            pose.translate(-0.04f, 0.22f, 0f);
            pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(20f));
            PoseStack.Pose spL = pose.last(); Matrix4f spM = spL.pose();
            drawBox(vc, spM, spL, -0.06f, 0f, -0.055f, 0.06f, 0.008f, 0.055f, 20, 40, 120, packedLight);
            drawBox(vc, spM, spL, -0.059f, 0.008f, -0.02f, 0.059f, 0.014f, -0.015f, 200, 160, 0, packedLight);
            drawBox(vc, spM, spL, -0.059f, 0.008f,  0.00f, 0.059f, 0.014f,  0.005f, 200, 160, 0, packedLight);
            drawBox(vc, spM, spL, -0.059f, 0.008f,  0.02f, 0.059f, 0.014f,  0.025f, 200, 160, 0, packedLight);
            pose.popPose();
            pose.pushPose();
            pose.translate(0.04f, 0.22f, 0f);
            pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(-20f));
            PoseStack.Pose spL2 = pose.last(); Matrix4f spM2 = spL2.pose();
            drawBox(vc, spM2, spL2, -0.06f, 0f, -0.055f, 0.06f, 0.008f, 0.055f, 20, 40, 120, packedLight);
            drawBox(vc, spM2, spL2, -0.059f, 0.008f, -0.02f, 0.059f, 0.014f, -0.015f, 200, 160, 0, packedLight);
            drawBox(vc, spM2, spL2, -0.059f, 0.008f,  0.00f, 0.059f, 0.014f,  0.005f, 200, 160, 0, packedLight);
            drawBox(vc, spM2, spL2, -0.059f, 0.008f,  0.02f, 0.059f, 0.014f,  0.025f, 200, 160, 0, packedLight);
            pose.popPose();
        }

        pose.popPose();

        // Laser beam
        int laserTargetId = entity.getLaserTargetId();
        if (laserTargetId != -1 && entity.level() != null) {
            Entity laserTarget = entity.level().getEntity(laserTargetId);
            if (laserTarget != null && laserTarget.isAlive()) {
                renderLaserBeam(entity, laserTarget, partialTick, pose, buffers);
            }
        }

        // GPS destination line — thin semi-transparent line to the GPS waypoint B (output/dest).
        net.minecraft.world.item.ItemStack gpsTool = entity.getGpsToolStack();
        if (!gpsTool.isEmpty() && entity.level() != null) {
            net.minecraft.core.BlockPos gpsTarget = com.apocscode.byteblock.item.GpsToolItem.getB(gpsTool);
            if (gpsTarget == null) gpsTarget = com.apocscode.byteblock.item.GpsToolItem.getA(gpsTool);
            if (gpsTarget != null) {
                double exW = Mth.lerp(partialTick, entity.xOld, entity.getX());
                double eyW = Mth.lerp(partialTick, entity.yOld, entity.getY());
                double ezW = Mth.lerp(partialTick, entity.zOld, entity.getZ());
                float gx = (float)(gpsTarget.getX() + 0.5 - exW);
                float gy = (float)(gpsTarget.getY() + 1.0 - eyW);
                float gz = (float)(gpsTarget.getZ() + 0.5 - ezW);
                float dist = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);
                if (dist > 1.0f && dist < 256.0f) {
                    float gnx = gx / dist, gny = gy / dist, gnz = gz / dist;
                    pose.pushPose();
                    VertexConsumer gvc = buffers.getBuffer(RenderType.lines());
                    PoseStack.Pose glast = pose.last();
                    Matrix4f gmat = glast.pose();
                    // White-tipped line fading to the GPS mode color toward the target.
                    String gpsMode = com.apocscode.byteblock.item.GpsToolItem.getMode(gpsTool).name();
                    int lineR = 180, lineG = 230, lineB = 255; // default cyan-white
                    if ("ROUTE".equals(gpsMode))    { lineR = 50;  lineG = 220; lineB = 80; }
                    if ("WAYPOINT".equals(gpsMode)) { lineR = 0;   lineG = 180; lineB = 255; }
                    if ("AREA".equals(gpsMode))     { lineR = 255; lineG = 200; lineB = 0; }
                    if ("PATH".equals(gpsMode))     { lineR = 180; lineG = 50;  lineB = 255; }
                    gvc.addVertex(gmat, 0f, 0.15f, 0f)
                       .setColor(255, 255, 255, 200).setNormal(glast, gnx, gny, gnz);
                    gvc.addVertex(gmat, gx, gy, gz)
                       .setColor(lineR, lineG, lineB, 80).setNormal(glast, gnx, gny, gnz);
                    pose.popPose();
                }
            }
        }

        super.render(entity, yaw, partialTick, pose, buffers, packedLight);

        // === SHIELD BUBBLE (translucent overlay) ===
        if (entity.hasShieldUpgrade() && entity.getShieldHP() > 0) {
            float sf = entity.getShieldHP() / 8f;
            float pulse = (float)(0.95 + 0.05 * Math.sin(entity.tickCount * 0.15 + partialTick * 0.15));
            float bR = 0.45f * pulse;
            pose.pushPose();
            pose.translate(0, 0.12f, 0);
            PoseStack.Pose shL = pose.last(); Matrix4f shM = shL.pose();
            VertexConsumer shVc = buffers.getBuffer(net.minecraft.client.renderer.RenderType.entityTranslucentCull(TEXTURE));
            drawBoxAlpha(shVc, shM, shL, -bR, -bR * 0.6f, -bR, bR, bR * 0.6f, bR,
                    40, 160, 255, (int)(70 * sf), packedLight);
            pose.popPose();
        }

        // === STEALTH SHIMMER (translucent body-duplicate) ===
        if (entity.hasStealthUpgrade()) {
            int stA = (int)((0.35 + 0.15 * Math.sin(entity.tickCount * 0.08f + partialTick * 0.08f)) * 255);
            pose.pushPose();
            PoseStack.Pose stL = pose.last(); Matrix4f stM = stL.pose();
            VertexConsumer stVc = buffers.getBuffer(net.minecraft.client.renderer.RenderType.entityTranslucentCull(TEXTURE));
            drawBoxAlpha(stVc, stM, stL, -0.13f, 0f, -0.13f, 0.13f, 0.24f, 0.13f,
                    80, 0, 160, stA, packedLight);
            pose.popPose();
        }
    }

    /** Draw a thin arm connecting two points. */
    private static void drawArm(VertexConsumer vc, Matrix4f mat, PoseStack.Pose last,
                                 float x0, float y0, float z0,
                                 float x1, float y1, float z1,
                                 int r, int g, int b, int light) {
        float hw = 0.025f, hh = 0.02f;
        // We draw a box from min to max along the diagonal
        float minX = Math.min(x0, x1) - hw, maxX = Math.max(x0, x1) + hw;
        float minZ = Math.min(z0, z1) - hw, maxZ = Math.max(z0, z1) + hw;
        drawBox(vc, mat, last, minX, y0 - hh, minZ, maxX, y0 + hh, maxZ,
                r, g, b, light);
    }

    /** Draw a flat rotor disc as a thin cross (two perpendicular blades). */
    private static void drawRotor(VertexConsumer vc, Matrix4f mat, PoseStack.Pose last,
                                   float cx, float cy, float cz, float radius, float angle,
                                   int r, int g, int b, int light) {
        float bw = 0.025f; // blade half-width
        float h = 0.005f;  // blade half-height
        // Convert angle to radians for blade tips
        double rad = Math.toRadians(angle);
        float cosA = (float) Math.cos(rad);
        float sinA = (float) Math.sin(rad);

        // Blade 1: along angle direction
        float bx0 = cx + cosA * radius, bz0 = cz + sinA * radius;
        float bx1 = cx - cosA * radius, bz1 = cz - sinA * radius;
        drawBox(vc, mat, last,
                Math.min(bx0, bx1) - bw, cy - h, Math.min(bz0, bz1) - bw,
                Math.max(bx0, bx1) + bw, cy + h, Math.max(bz0, bz1) + bw,
                r, g, b, light);

        // Blade 2: perpendicular
        float bx2 = cx + sinA * radius, bz2 = cz - cosA * radius;
        float bx3 = cx - sinA * radius, bz3 = cz + cosA * radius;
        drawBox(vc, mat, last,
                Math.min(bx2, bx3) - bw, cy - h, Math.min(bz2, bz3) - bw,
                Math.max(bx2, bx3) + bw, cy + h, Math.max(bz2, bz3) + bw,
                r, g, b, light);
    }

    /** Draw an axis-aligned box with 6 shaded faces. */
    private static void drawBox(VertexConsumer vc, Matrix4f mat, PoseStack.Pose last,
                                 float x0, float y0, float z0, float x1, float y1, float z1,
                                 int r, int g, int b, int light) {
        int a = 255, ds = 15;
        quad(vc, mat, last, x0,y0,z1, x1,y0,z1, x1,y0,z0, x0,y0,z0,
             0,-1,0, light, r-ds*2, g-ds*2, b-ds*2, a);
        quad(vc, mat, last, x0,y1,z0, x1,y1,z0, x1,y1,z1, x0,y1,z1,
             0,1,0, light, r, g, b, a);
        quad(vc, mat, last, x0,y1,z0, x0,y0,z0, x1,y0,z0, x1,y1,z0,
             0,0,-1, light, r-ds, g-ds, b-ds, a);
        quad(vc, mat, last, x1,y1,z1, x1,y0,z1, x0,y0,z1, x0,y1,z1,
             0,0,1, light, r-ds, g-ds, b-ds, a);
        quad(vc, mat, last, x0,y1,z1, x0,y0,z1, x0,y0,z0, x0,y1,z0,
             -1,0,0, light, r-ds-5, g-ds-5, b-ds-5, a);
        quad(vc, mat, last, x1,y1,z0, x1,y0,z0, x1,y0,z1, x1,y1,z1,
             1,0,0, light, r-ds-5, g-ds-5, b-ds-5, a);
    }

    /** Like drawBox but with custom alpha (0-255). */
    private static void drawBoxAlpha(VertexConsumer vc, Matrix4f mat, PoseStack.Pose last,
                                     float x0, float y0, float z0, float x1, float y1, float z1,
                                     int r, int g, int b, int a, int light) {
        int ds = 15;
        quad(vc, mat, last, x0,y0,z1, x1,y0,z1, x1,y0,z0, x0,y0,z0,  0,-1,0, light, r-ds*2, g-ds*2, b-ds*2, a);
        quad(vc, mat, last, x0,y1,z0, x1,y1,z0, x1,y1,z1, x0,y1,z1,  0, 1,0, light, r,      g,      b,      a);
        quad(vc, mat, last, x0,y1,z0, x0,y0,z0, x1,y0,z0, x1,y1,z0,  0, 0,-1,light, r-ds,   g-ds,   b-ds,   a);
        quad(vc, mat, last, x1,y1,z1, x1,y0,z1, x0,y0,z1, x0,y1,z1,  0, 0, 1,light, r-ds,   g-ds,   b-ds,   a);
        quad(vc, mat, last, x0,y1,z1, x0,y0,z1, x0,y0,z0, x0,y1,z0, -1, 0,0, light, r-ds-5, g-ds-5, b-ds-5, a);
        quad(vc, mat, last, x1,y1,z0, x1,y0,z0, x1,y0,z1, x1,y1,z1,  1, 0,0, light, r-ds-5, g-ds-5, b-ds-5, a);
    }

    private static void quad(VertexConsumer vc, Matrix4f mat, PoseStack.Pose last,
                              float x0, float y0, float z0,
                              float x1, float y1, float z1,
                              float x2, float y2, float z2,
                              float x3, float y3, float z3,
                              float nx, float ny, float nz,
                              int light, int r, int g, int b, int a) {
        r = Math.max(0, Math.min(255, r)); g = Math.max(0, Math.min(255, g)); b = Math.max(0, Math.min(255, b));
        vc.addVertex(mat, x0, y0, z0).setColor(r,g,b,a).setUv(0,0)
          .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(last, nx, ny, nz);
        vc.addVertex(mat, x1, y1, z1).setColor(r,g,b,a).setUv(0,1)
          .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(last, nx, ny, nz);
        vc.addVertex(mat, x2, y2, z2).setColor(r,g,b,a).setUv(1,1)
          .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(last, nx, ny, nz);
        vc.addVertex(mat, x3, y3, z3).setColor(r,g,b,a).setUv(1,0)
          .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(last, nx, ny, nz);
    }

    @Override
    public ResourceLocation getTextureLocation(DroneEntity entity) {
        return TEXTURE;
    }

    @Override
    protected boolean shouldShowName(DroneEntity entity) {
        return true;
    }

    /**
     * Draw a pulsing laser beam from the drone body toward a target entity.
     * Uses RenderType.lines() with 5 parallel lines (cross pattern) for visible width.
     * Flickers every 4th tick and emits CRIT particles along the beam.
     */
    private void renderLaserBeam(DroneEntity entity, Entity target, float partialTick,
                                  PoseStack pose, MultiBufferSource buffers) {
        // Interpolated world positions
        double exW = Mth.lerp(partialTick, entity.xOld, entity.getX());
        double eyW = Mth.lerp(partialTick, entity.yOld, entity.getY());
        double ezW = Mth.lerp(partialTick, entity.zOld, entity.getZ());

        // Target centre in entity-local space (pose stack origin = entity feet)
        float tx = (float)(Mth.lerp(partialTick, target.xOld, target.getX()) - exW);
        float ty = (float)(Mth.lerp(partialTick, target.yOld, target.getY())
                           + target.getBbHeight() * 0.5 - eyW);
        float tz = (float)(Mth.lerp(partialTick, target.zOld, target.getZ()) - ezW);

        float len = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (len < 0.1f) return;
        float nx = tx / len, ny = ty / len, nz = tz / len;

        // Flicker: dim on tick%4==3
        boolean dim = (entity.tickCount % 4) == 3;
        int alpha = dim ? 80 : 230;

        pose.pushPose();
        VertexConsumer vc = buffers.getBuffer(RenderType.lines());
        PoseStack.Pose last = pose.last();
        Matrix4f mat  = last.pose();

        // 5-line cross pattern for beam thickness; centre = white-hot, edges = red
        float[][] off = {{0f, 0f}, {0.02f, 0f}, {-0.02f, 0f}, {0f, 0.02f}, {0f, -0.02f}};
        int[][] cols  = {{255,220,220}, {220,30,30}, {220,30,30}, {220,30,30}, {220,30,30}};

        float sy = 0.10f; // beam origin: slightly below drone centre
        for (int i = 0; i < 5; i++) {
            float ox = off[i][0], oy = off[i][1];
            int r = cols[i][0], g = cols[i][1], b = cols[i][2];
            vc.addVertex(mat, ox,      sy + oy,      0f)
              .setColor(r, g, b, alpha).setNormal(last, nx, ny, nz);
            vc.addVertex(mat, tx + ox, ty + oy, tz)
              .setColor(r, g, b, alpha).setNormal(last, nx, ny, nz);
        }
        pose.popPose();

        // Spark particle at impact point (client-side, ~10/s)
        if (entity.level() != null && !dim && entity.tickCount % 2 == 0) {
            double ipx = Mth.lerp(partialTick, target.xOld, target.getX());
            double ipy = Mth.lerp(partialTick, target.yOld, target.getY())
                         + target.getBbHeight() * 0.5;
            double ipz = Mth.lerp(partialTick, target.zOld, target.getZ());
            entity.level().addParticle(ParticleTypes.CRIT,       ipx, ipy, ipz, 0, 0, 0);
            entity.level().addParticle(ParticleTypes.DAMAGE_INDICATOR, ipx, ipy, ipz, 0, 0, 0);
        }
    }

    @Override
    protected void renderNameTag(DroneEntity entity, net.minecraft.network.chat.Component displayName,
                                  PoseStack poseStack, MultiBufferSource buffer,
                                  int packedLight, float partialTick) {
        if (this.entityRenderDispatcher.distanceToSqr(entity) > 4096.0) return;

        Font font = this.getFont();
        boolean hasName = entity.hasCustomName();
        net.minecraft.network.chat.Component stats = entity.getStatsLine();

        int nameW  = hasName ? font.width(displayName) : 0;
        int statsW = font.width(stats);
        int panelW = Math.max(nameW, statsW) + 8;

        float nameY  = 0f;
        float statsY = hasName ? 11f : 0f;

        float panelTop    = (hasName ? nameY : statsY) - 4f;
        float accentBot   = panelTop + 2f;
        float panelBottom = statsY + 10f;

        poseStack.pushPose();
        poseStack.translate(0.0, entity.getBbHeight() + 0.5f, 0.0);
        poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());
        poseStack.scale(0.009f, -0.009f, 0.009f);
        Matrix4f mat = poseStack.last().pose();

        float left  = -panelW / 2f;
        float right =  panelW / 2f;

        VertexConsumer bg = buffer.getBuffer(RenderType.textBackground());
        // Top accent strip (drone blue #288CD8).
        bg.addVertex(mat, left,  panelTop, 0f).setColor(0xFF288CD8).setLight(packedLight);
        bg.addVertex(mat, left,  accentBot, 0f).setColor(0xFF288CD8).setLight(packedLight);
        bg.addVertex(mat, right, accentBot, 0f).setColor(0xFF288CD8).setLight(packedLight);
        bg.addVertex(mat, right, panelTop,  0f).setColor(0xFF288CD8).setLight(packedLight);
        // Dark body.
        bg.addVertex(mat, left,  accentBot,   0f).setColor(0xB8101010).setLight(packedLight);
        bg.addVertex(mat, left,  panelBottom, 0f).setColor(0xB8101010).setLight(packedLight);
        bg.addVertex(mat, right, panelBottom, 0f).setColor(0xB8101010).setLight(packedLight);
        bg.addVertex(mat, right, accentBot,   0f).setColor(0xB8101010).setLight(packedLight);

        if (hasName) {
            font.drawInBatch(displayName, -nameW / 2f, nameY, 0xFFFFFF, false, mat, buffer,
                             Font.DisplayMode.NORMAL, 0, packedLight);
        }
        font.drawInBatch(stats, -statsW / 2f, statsY, 0xFFFFFF, false, mat, buffer,
                         Font.DisplayMode.NORMAL, 0, packedLight);

        poseStack.popPose();
    }

    private static int rgb(int r, int g, int b) { return ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF); }
    private static int[] unpack(int rgb) { return new int[]{(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF}; }
}

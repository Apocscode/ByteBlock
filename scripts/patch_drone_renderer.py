"""
Patches DroneRenderer.java to add solar panels, shield bubble, and stealth shimmer rendering,
plus a drawBoxAlpha helper method.
"""

path = r"F:\JavaCraft\src\main\java\com\apocscode\byteblock\client\DroneRenderer.java"

with open(path, "r", encoding="utf-8") as f:
    src = f.read()

# ---- 1. Insert solar panels before pose.popPose() (inside main pose block) ----
# The drone body ends with wheel/skid details then pose.popPose()
OLD_POPPOSE = '''        drawBox(vc, mat, last, 0.16f, 0.05f, 0.04f, 0.18f, 0.10f, 0.08f,
                dw - 30, dg - 30, db - 25, packedLight);

        pose.popPose();'''

NEW_POPPOSE = '''        drawBox(vc, mat, last, 0.16f, 0.05f, 0.04f, 0.18f, 0.10f, 0.08f,
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

        pose.popPose();'''

assert OLD_POPPOSE in src, "Could not find OLD_POPPOSE in source!"
src = src.replace(OLD_POPPOSE, NEW_POPPOSE, 1)
print("Inserted solar panels OK")

# ---- 2. Insert shield/stealth after super.render() ----
OLD_SUPER = '''        super.render(entity, yaw, partialTick, pose, buffers, packedLight);
    }

    /** Draw a thin arm connecting two points. */'''

NEW_SUPER = '''        super.render(entity, yaw, partialTick, pose, buffers, packedLight);

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

    /** Draw a thin arm connecting two points. */'''

assert OLD_SUPER in src, "Could not find OLD_SUPER in source!"
src = src.replace(OLD_SUPER, NEW_SUPER, 1)
print("Inserted shield/stealth OK")

# ---- 3. Add drawBoxAlpha after the closing brace of drawBox ----
OLD_DRAWBOX_END = '''        quad(vc, mat, last, x1,y1,z0, x1,y0,z0, x1,y0,z1, x1,y1,z1,
             1,0,0, light, r-ds-5, g-ds-5, b-ds-5, a);
    }

    private static void quad'''

NEW_DRAWBOX_END = '''        quad(vc, mat, last, x1,y1,z0, x1,y0,z0, x1,y0,z1, x1,y1,z1,
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

    private static void quad'''

assert OLD_DRAWBOX_END in src, "Could not find OLD_DRAWBOX_END in source!"
src = src.replace(OLD_DRAWBOX_END, NEW_DRAWBOX_END, 1)
print("Added drawBoxAlpha OK")

with open(path, "w", encoding="utf-8") as f:
    f.write(src)

print("Done! DroneRenderer.java written.")

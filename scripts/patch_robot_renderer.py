"""
Patches RobotRenderer.java to add solar panels, shield bubble, and stealth shimmer rendering,
plus a drawBoxAlpha helper method.
"""
import re

path = r"F:\JavaCraft\src\main\java\com\apocscode\byteblock\client\RobotRenderer.java"

with open(path, "r", encoding="utf-8") as f:
    src = f.read()

# ---- 1. Insert solar panels + stealth/shield after antenna/pose.popPose/super.render ----

OLD_BLOCK = '''        // Red LED tip
        drawBox(vc, mat, last, -0.03f, 1.12f, -0.03f, 0.03f, 1.15f, 0.03f,
                220, 30, 30, packedLight);

        pose.popPose();
        super.render(entity, yaw, partialTick, pose, buffers, packedLight);
    }'''

NEW_BLOCK = '''        // Red LED tip
        drawBox(vc, mat, last, -0.03f, 1.12f, -0.03f, 0.03f, 1.15f, 0.03f,
                220, 30, 30, packedLight);

        // === SOLAR PANELS (head top, when card installed) ===
        if (entity.hasSolarUpgrade()) {
            pose.pushPose();
            pose.translate(-0.10f, 1.0f, 0f);
            pose.mulPose(Axis.ZP.rotationDegrees(15f));
            PoseStack.Pose spL = pose.last(); Matrix4f spM = spL.pose();
            drawBox(vc, spM, spL, -0.09f, 0f, -0.09f, 0.09f, 0.012f, 0.09f, 20, 40, 120, packedLight);
            drawBox(vc, spM, spL, -0.089f, 0.012f, -0.03f, 0.089f, 0.018f, -0.025f, 200, 160, 0, packedLight);
            drawBox(vc, spM, spL, -0.089f, 0.012f,  0.00f, 0.089f, 0.018f,  0.005f, 200, 160, 0, packedLight);
            drawBox(vc, spM, spL, -0.089f, 0.012f,  0.03f, 0.089f, 0.018f,  0.035f, 200, 160, 0, packedLight);
            pose.popPose();
            pose.pushPose();
            pose.translate(0.10f, 1.0f, 0f);
            pose.mulPose(Axis.ZP.rotationDegrees(-15f));
            PoseStack.Pose spL2 = pose.last(); Matrix4f spM2 = spL2.pose();
            drawBox(vc, spM2, spL2, -0.09f, 0f, -0.09f, 0.09f, 0.012f, 0.09f, 20, 40, 120, packedLight);
            drawBox(vc, spM2, spL2, -0.089f, 0.012f, -0.03f, 0.089f, 0.018f, -0.025f, 200, 160, 0, packedLight);
            drawBox(vc, spM2, spL2, -0.089f, 0.012f,  0.00f, 0.089f, 0.018f,  0.005f, 200, 160, 0, packedLight);
            drawBox(vc, spM2, spL2, -0.089f, 0.012f,  0.03f, 0.089f, 0.018f,  0.035f, 200, 160, 0, packedLight);
            pose.popPose();
        }

        pose.popPose();
        super.render(entity, yaw, partialTick, pose, buffers, packedLight);

        // === SHIELD BUBBLE (translucent overlay) ===
        if (entity.hasShieldUpgrade() && entity.getShieldHP() > 0) {
            float sf = entity.getShieldHP() / 8f;
            float pulse = (float)(0.95 + 0.05 * Math.sin(entity.tickCount * 0.15 + partialTick * 0.15));
            float bR = 0.7f * pulse;
            pose.pushPose();
            pose.translate(0, 0.55f, 0);
            PoseStack.Pose shL = pose.last(); Matrix4f shM = shL.pose();
            VertexConsumer shVc = buffers.getBuffer(RenderType.entityTranslucentCull(TEXTURE));
            drawBoxAlpha(shVc, shM, shL, -bR, -bR * 0.8f, -bR, bR, bR * 0.8f, bR,
                    40, 160, 255, (int)(70 * sf), packedLight);
            pose.popPose();
        }

        // === STEALTH SHIMMER (translucent body-duplicate) ===
        if (entity.hasStealthUpgrade()) {
            int stA = (int)((0.35 + 0.15 * Math.sin(entity.tickCount * 0.08f + partialTick * 0.08f)) * 255);
            pose.pushPose();
            float fy2 = switch (entity.getRobotFacing()) {
                case SOUTH -> 180f; case WEST -> 90f; case EAST -> -90f; default -> 0f;
            };
            pose.mulPose(Axis.YP.rotationDegrees(fy2 + 180f));
            PoseStack.Pose stL = pose.last(); Matrix4f stM = stL.pose();
            VertexConsumer stVc = buffers.getBuffer(RenderType.entityTranslucentCull(TEXTURE));
            drawBoxAlpha(stVc, stM, stL, -0.371f, 0f, -0.318f, 0.371f, 0.689f, 0.318f,
                    80, 0, 160, stA, packedLight);
            pose.popPose();
        }
    }'''

assert OLD_BLOCK in src, "Could not find OLD_BLOCK in source!"
src = src.replace(OLD_BLOCK, NEW_BLOCK, 1)
print("Replaced render block OK")

# ---- 2. Insert drawBoxAlpha after the closing brace of drawBox ----
# drawBox ends with the Right (x+) quad and then a closing brace.
OLD_DRAWBOX_END = '''        // Right (x+)
        quad(vc, mat, last, x1,y1,z0, x1,y0,z0, x1,y0,z1, x1,y1,z1,
             1,0,0, light, r-ds-5, g-ds-5, b-ds-5, a);
    }'''

NEW_DRAWBOX_END = '''        // Right (x+)
        quad(vc, mat, last, x1,y1,z0, x1,y0,z0, x1,y0,z1, x1,y1,z1,
             1,0,0, light, r-ds-5, g-ds-5, b-ds-5, a);
    }

    /** Like {@link #drawBox} but with a custom alpha (0-255). */
    static void drawBoxAlpha(VertexConsumer vc, Matrix4f mat, PoseStack.Pose last,
                             float x0, float y0, float z0, float x1, float y1, float z1,
                             int r, int g, int b, int a, int light) {
        int ds = 20;
        quad(vc, mat, last, x0,y0,z1, x1,y0,z1, x1,y0,z0, x0,y0,z0,  0,-1,0, light, r-ds*2, g-ds*2, b-ds*2, a);
        quad(vc, mat, last, x0,y1,z0, x1,y1,z0, x1,y1,z1, x0,y1,z1,  0, 1,0, light, r,      g,      b,      a);
        quad(vc, mat, last, x0,y1,z0, x0,y0,z0, x1,y0,z0, x1,y1,z0,  0, 0,-1,light, r-ds,   g-ds,   b-ds,   a);
        quad(vc, mat, last, x1,y1,z1, x1,y0,z1, x0,y0,z1, x0,y1,z1,  0, 0, 1,light, r-ds,   g-ds,   b-ds,   a);
        quad(vc, mat, last, x0,y1,z1, x0,y0,z1, x0,y0,z0, x0,y1,z0, -1, 0,0, light, r-ds-5, g-ds-5, b-ds-5, a);
        quad(vc, mat, last, x1,y1,z0, x1,y0,z0, x1,y0,z1, x1,y1,z1,  1, 0,0, light, r-ds-5, g-ds-5, b-ds-5, a);
    }'''

assert OLD_DRAWBOX_END in src, "Could not find OLD_DRAWBOX_END in source!"
src = src.replace(OLD_DRAWBOX_END, NEW_DRAWBOX_END, 1)
print("Added drawBoxAlpha OK")

with open(path, "w", encoding="utf-8") as f:
    f.write(src)

print("Done! File written.")

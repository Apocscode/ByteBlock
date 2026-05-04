import zlib, struct, os

def write_png(path, pixels, w=16, h=16):
    """Write a 16x16 RGBA PNG from a flat list of (R,G,B,A) tuples."""
    def chunk(tag, data):
        c = zlib.crc32(tag + data) & 0xffffffff
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", c)
    raw = b""
    for row in range(h):
        raw += b"\x00"
        for col in range(w):
            r, g, b, a = pixels[row * w + col]
            raw += bytes([r, g, b, a])
    compressed = zlib.compress(raw, 9)
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)  # colour type 6 = RGBA
    png  = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", ihdr)
    png += chunk(b"IDAT", compressed)
    png += chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)

BASE   = r"F:\JavaCraft\src\main\resources\assets\byteblock\textures\item"
BORDER = (20, 15, 25, 255)
CHIP   = (210, 185, 60, 255)
CHIP_H = (240, 215, 90, 255)

def make_card(card_bg, trace_c, ind_c):
    p = [card_bg] * 256

    # 1-px border
    for i in range(16):
        p[i]         = BORDER
        p[15 * 16 + i] = BORDER
        p[i * 16]    = BORDER
        p[i * 16 + 15] = BORDER

    # indicator square top-right (rows 2-4, cols 11-13)
    for r in range(2, 5):
        for c in range(11, 14):
            p[r * 16 + c] = ind_c

    # horizontal traces at rows 6 and 9, cols 4-11
    for c in range(4, 12):
        p[6 * 16 + c] = trace_c
        p[9 * 16 + c] = trace_c

    # vertical traces at cols 4 and 11, rows 6-9
    for r in range(6, 10):
        p[r * 16 + 4]  = trace_c
        p[r * 16 + 11] = trace_c

    # chip body rows 7-9, cols 5-10
    for r in range(7, 10):
        for c in range(5, 11):
            p[r * 16 + c] = CHIP

    # chip highlight top row & left edge
    for c in range(5, 11):
        p[7 * 16 + c] = CHIP_H
    p[8 * 16 + 5] = CHIP_H

    return p

CARDS = {
    "upgrade_range_1":        ((25, 55,  130, 255), (35,  75, 165, 255), (80,  190, 255, 255)),
    "upgrade_range_2":        ((10, 28,   80, 255), (15,  42, 110, 255), (40,  110, 220, 255)),
    "upgrade_range_creative": (( 0, 100, 115, 255), ( 5, 130, 148, 255), (80,  255, 240, 255)),
    "upgrade_speed":          ((115, 85,   0, 255), (145, 108,   8, 255), (255, 215,  40, 255)),
    "upgrade_inventory":      (( 75, 48,  18, 255), ( 98,  62,  28, 255), (200, 148,  72, 255)),
    "upgrade_laser":          ((115, 18,  18, 255), (148,  28,  28, 255), (255,  70,  40, 255)),
    "upgrade_shield":         (( 22, 80, 140, 255), ( 30, 110, 175, 255), (130, 220, 255, 255)),
    "upgrade_stealth":        (( 30, 30,  50, 255), ( 45,  45,  70, 255), (140, 100, 220, 255)),
    "upgrade_solar":          (( 60, 90,  20, 255), ( 80, 120,  25, 255), (200, 240,  60, 255)),
    "upgrade_filter":         (( 80, 30,  80, 255), (110,  42, 110, 255), (240, 100, 240, 255)),
}

for name, (bg, tr, ind) in CARDS.items():
    pixels = make_card(bg, tr, ind)
    write_png(f"{BASE}\\{name}.png", pixels)
    print(f"  wrote {name}.png")

print("done")

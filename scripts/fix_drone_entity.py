"""Fix DroneEntity.java: remove leftover getMyRidingOffset and duplicate checkDespawn."""

path = r"F:\JavaCraft\src\main\java\com\apocscode\byteblock\entity\DroneEntity.java"

with open(path, "r", encoding="utf-8") as f:
    src = f.read()

# The bad block to remove
BAD = '''        /** Mobs use this to decide detection range — stealth card reduces it by 75%. */
        @Override
        public float getMyRidingOffset() {
            return super.getMyRidingOffset();
        }

    @Override
    public void checkDespawn() {
        @Override
        public void checkDespawn() {'''

GOOD = '''    @Override
    public void checkDespawn() {'''

assert BAD in src, f"BAD block not found! Check manually."
src = src.replace(BAD, GOOD, 1)
print("Fixed checkDespawn duplicate OK")

with open(path, "w", encoding="utf-8") as f:
    f.write(src)

print("Done!")

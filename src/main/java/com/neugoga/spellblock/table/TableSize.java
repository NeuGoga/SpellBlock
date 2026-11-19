package com.neugoga.spellblock.table;

import net.minecraft.util.StringRepresentable;

public enum TableSize implements StringRepresentable {
    SINGLE("single", 1, 1),
    LONG_X("long_x", 2, 1),
    LONG_Z("long_z", 1, 2),
    SQUARE("square", 2, 2);

    private final String name;
    private final int width;
    private final int depth;

    TableSize(String name, int width, int depth) {
        this.name = name;
        this.width = width;
        this.depth = depth;
    }

    public int width() {
        return width;
    }

    public int depth() {
        return depth;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}

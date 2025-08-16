package com.winlator.cmod.xserver;

public class WorkArea {
    public int x, y, width, height;

    public WorkArea(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = Math.max(0, width);
        this.height = Math.max(0, height);
    }

    public boolean isEmpty() {
        return width <= 0 || height <= 0;
    }

    public WorkArea getWorkAreaOrScreen() {
        return this;
    }

}
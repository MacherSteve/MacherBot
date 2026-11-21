package de.pvptrainer.ui;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

final class SettingSlider extends AbstractSliderButton {
    private final String label;
    private final int maxIndex;
    private final IntFunction<String> format;
    private final IntConsumer save;
    private int index;

    SettingSlider(int x, int y, int width, int height, String label, int steps,
                  int initial, IntFunction<String> format, IntConsumer save) {
        super(x, y, width, height, Component.literal(label), (double) initial / (steps - 1));
        this.label = label;
        this.maxIndex = steps - 1;
        this.index = initial;
        this.format = format;
        this.save = save;
        updateMessage();
    }

    void showIndex(int current) {
        index = Math.clamp(current, 0, maxIndex);
        value = (double) index / maxIndex;
        updateMessage();
    }

    @Override public void extractWidgetRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        TrainerPanel.drawButton(graphics, this, TrainerPanel.Style.NORMAL, TrainerPanel.SELECTED, TrainerPanel.EMBER);
        var font = net.minecraft.client.Minecraft.getInstance().font;
        int left = getX() + 5;
        String displayedValue = format.apply(index);
        int valueWidth = font.width(displayedValue);
        int available = Math.max(0, getWidth() - 15 - valueWidth);
        String heading = font.width(label) <= available ? label : font.plainSubstrByWidth(label, Math.max(0,available - font.width("…"))) + "…";
        int textY = getY() + (getHeight()-10)/2 - 1;
        graphics.text(font, heading, left, textY, active ? TrainerPanel.TEXT : TrainerPanel.OFF, false);
        graphics.text(font, displayedValue, getRight()-5-valueWidth, textY, active ? TrainerPanel.MUTED : TrainerPanel.OFF, false);
        int trackY = getBottom()-4;
        int end = getRight()-5;
        int handle = left + (int)Math.round((end-left)*value);
        graphics.fill(left,trackY,end+1,trackY+1,TrainerPanel.LINE);
        graphics.fill(left,trackY,handle+1,trackY+1,active ? TrainerPanel.EMBER : TrainerPanel.OFF);
        graphics.fill(handle-1,trackY-1,handle+2,trackY+2,active ? TrainerPanel.TEXT : TrainerPanel.OFF);
        handleCursor(graphics);
    }

    @Override protected void applyValue() {
        int next = (int) Math.round(value * maxIndex);
        value = (double) next / maxIndex;
        if (index != next) {
            index = next;
            save.accept(index);
        }
    }

    @Override public boolean keyPressed(KeyEvent key) {
        if (canChangeValue && (key.key() == 263 || key.key() == 262)) {
            setValue((double)Math.max(0, Math.min(maxIndex, index + (key.key() == 262 ? 1 : -1))) / maxIndex);
            return true;
        }
        return super.keyPressed(key);
    }

    @Override protected void updateMessage() {
        if (format != null) setMessage(Component.literal(label + " · " + format.apply(index)));
    }
}

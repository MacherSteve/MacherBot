package de.pvptrainer.ui;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
        super(x, y, width, height, Component.literal(label), steps <= 1 ? 0.0 : (double) initial / (steps - 1));
        this.label = label;
        this.maxIndex = Math.max(1, steps - 1);
        this.index = Math.clamp(initial, 0, this.maxIndex);
        this.format = format;
        this.save = save;
        updateMessage();
    }

    void showIndex(int current) {
        index = Math.clamp(current, 0, maxIndex);
        value = (double) index / maxIndex;
        updateMessage();
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        drawAppleSlider(graphics, getX(), getY(), getWidth(), getHeight(), label, format.apply(index), value, active, isHoveredOrFocused());
        handleCursor(graphics);
    }

    static void drawAppleSlider(GuiGraphicsExtractor graphics, int x, int y, int w, int h,
                                String label, String displayedValue, double progress, boolean active, boolean hovered) {
        drawAppleSlider(graphics, x, y, w, h, label, displayedValue, progress, active, hovered, TrainerPanel.ACCENT_LINE, true);
    }

    static void drawAppleSlider(GuiGraphicsExtractor graphics, int x, int y, int w, int h,
                                String label, String displayedValue, double progress, boolean active, boolean hovered,
                                int fillColor, boolean drawCard) {
        if (drawCard) {
            TrainerPanel.fillRounded(graphics, x, y, w, h, hovered && active ? TrainerPanel.HOVER : TrainerPanel.CELL);
            TrainerPanel.outlineRounded(graphics, x, y, w, h, hovered && active ? TrainerPanel.BORDER_HOVER : TrainerPanel.LINE);
        }
        var font = net.minecraft.client.Minecraft.getInstance().font;
        int left = x + (drawCard ? 7 : 0);
        int right = x + w - (drawCard ? 7 : 0);
        int valueWidth = font.width(displayedValue);
        int available = Math.max(0, right - left - valueWidth - 6);
        String heading = font.width(label) <= available ? label : font.plainSubstrByWidth(label, Math.max(0, available - font.width("…"))) + "…";
        int textY = y + (drawCard ? Math.max(3, (h - 15) / 2) : 0);
        graphics.text(font, heading, left, textY, active ? TrainerPanel.TEXT : TrainerPanel.OFF, false);
        graphics.text(font, displayedValue, right - valueWidth, textY, active ? TrainerPanel.MUTED : TrainerPanel.OFF, false);

        // Sleek Apple-style rounded track + circular white knob
        int trackY = y + h - (drawCard ? 6 : 5);
        int trackW = Math.max(12, right - left);
        int clamped = (int) Math.round(Math.clamp(progress, 0.0, 1.0) * trackW);
        TrainerPanel.fillRounded(graphics, left, trackY, trackW, 2, 0xFF2C2C35);
        if (clamped > 0) {
            TrainerPanel.fillRounded(graphics, left, trackY, Math.max(2, clamped), 2, active ? fillColor : TrainerPanel.OFF);
        }
        int knobX = Math.clamp(left + clamped - 3, left, right - 6);
        TrainerPanel.fillRounded(graphics, knobX, trackY - 2, 6, 6, active ? TrainerPanel.TEXT : TrainerPanel.OFF);
    }

    @Override
    protected void applyValue() {
        int next = (int) Math.round(value * maxIndex);
        value = (double) next / maxIndex;
        if (index != next) {
            index = next;
            save.accept(index);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent key) {
        if (canChangeValue && (key.key() == 263 || key.key() == 262)) {
            setValue((double) Math.max(0, Math.min(maxIndex, index + (key.key() == 262 ? 1 : -1))) / maxIndex);
            return true;
        }
        return super.keyPressed(key);
    }

    @Override
    protected void updateMessage() {
        if (format != null) setMessage(Component.literal(label + " · " + format.apply(index)));
    }
}

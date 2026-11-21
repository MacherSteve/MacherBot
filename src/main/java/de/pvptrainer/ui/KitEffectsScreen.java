package de.pvptrainer.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;

final class KitEffectsScreen extends TrainerPanel {
    private final boolean bot;
    private final List<MobEffectInstance> draft;
    private final Consumer<List<MobEffectInstance>> save;
    private int page;

    KitEffectsScreen(KitEditorScreen parent, boolean bot, List<MobEffectInstance> effects,
                     Consumer<List<MobEffectInstance>> save) {
        super(parent, "Kit-Effekte", parent.mode);
        this.bot = bot;
        this.draft = new ArrayList<>(effects);
        this.save = save;
    }

    @Override void build() {
        subtitle = (bot ? "Bot" : "Spieler") + " · " + draft.size() + " aktiv";
        var effects = minecraft.level.registryAccess().lookupOrThrow(Registries.MOB_EFFECT)
            .listElements().filter(h -> h.value().isEnabled(minecraft.level.enabledFeatures()))
            .sorted(Comparator.comparing(h -> h.value().getDisplayName().getString())).toList();
        int rows = Math.max(1, (bottom - y - 25) / (30 + gap));
        int perPage = rows * 2;
        int pages = Math.max(1, (effects.size() + perPage - 1) / perPage);
        page = Math.min(page, pages - 1);
        for (int i = 0; i < perPage && page * perPage + i < effects.size(); i++) {
            var effect = effects.get(page * perPage + i);
            var current = draft.stream().filter(instance -> instance.is(effect)).findFirst().orElse(null);
            String state = current == null ? "Aus" : "Stufe " + (current.getAmplifier() + 1) + " · " + durationLabel(effect, current.getDuration());
            tile(col(i % 2, 2), y + i / 2 * (30 + gap), cw(i % 2, 2), 30, effect.value().getDisplayName().getString(),
                state, ItemStack.EMPTY, current == null ? Style.NORMAL : Style.SELECTED,
                () -> open(new EffectDetails(this, effect, current)));
        }
        action(x, bottom - 19, 28, 19, "‹", Style.NORMAL, () -> { page--; refresh(); }).active = page > 0;
        action(x + 30, bottom - 19, 28, 19, "›", Style.NORMAL, () -> { page++; refresh(); }).active = page + 1 < pages;
        action(x + 60 + gap, bottom - 19, inner - 60 - gap, 19, "Übernehmen · " + (page + 1) + "/" + pages, Style.PRIMARY,
            () -> { save.accept(List.copyOf(draft)); onClose(); });
    }

    private static String durationLabel(Holder<MobEffect> type, int ticks) {
        if (type.value().isInstantaneous()) return "Sofort";
        if (ticks == MobEffectInstance.INFINITE_DURATION) return "Ganze Runde";
        if (ticks % 20 != 0) return ticks + " Ticks";
        return (ticks / 20) + " s";
    }

    private static final class EffectDetails extends TrainerPanel {
        private final KitEffectsScreen editor;
        private final Holder<MobEffect> type;
        private final MobEffectInstance original;
        private int level;
        private int duration;

        EffectDetails(KitEffectsScreen editor, Holder<MobEffect> type, MobEffectInstance original) {
            super(editor, type.value().getDisplayName().getString(), editor.mode);
            this.editor = editor;
            this.type = type;
            this.original = original;
            level = original == null ? 1 : original.getAmplifier() + 1;
            duration = type.value().isInstantaneous() ? 1 : (original == null ? MobEffectInstance.INFINITE_DURATION : original.getDuration());
        }

        @Override void build() {
            subtitle = editor.bot ? "Bot-Kit" : "Spieler-Kit";
            addRenderableWidget(new SettingSlider(x, y, inner, 25, "Stufe", Math.max(5, level), level - 1,
                step -> Integer.toString(step + 1), step -> level = step + 1));
            if (!type.value().isInstantaneous()) {
                var durations = new ArrayList<>(List.of(100, 300, 600, 1200, 2400, 6000, 12000));
                if (duration >= 0 && !durations.contains(duration)) { durations.add(duration); durations.sort(Integer::compare); }
                durations.add(MobEffectInstance.INFINITE_DURATION);
                addRenderableWidget(new SettingSlider(x, y + 25 + gap, inner, 25, "Dauer", durations.size(), durations.indexOf(duration),
                    step -> durationLabel(type, durations.get(step)), step -> duration = durations.get(step)));
            }
            action(col(0, 2), bottom - 19, cw(0, 2), 19, "Entfernen", Style.NORMAL,
                () -> { editor.draft.removeIf(effect -> effect.is(type)); onClose(); }).active = original != null;
            action(col(1, 2), bottom - 19, cw(1, 2), 19, "Übernehmen", Style.PRIMARY, () -> {
                editor.draft.removeIf(effect -> effect.is(type));
                editor.draft.add(new MobEffectInstance(type, duration, level - 1, original != null && original.isAmbient(),
                    original == null || original.isVisible(), original == null || original.showIcon()));
                onClose();
            });
        }
    }
}

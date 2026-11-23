package de.pvptrainer.arena;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.item.component.ResolvableProfile;

public final class MacherAppearance {
   public static final Component NAME = Component.literal("MacherBot");
   public static final ResolvableProfile PROFILE = bundledProfile();

   private MacherAppearance() {
   }

   public static Component healthName(float health, float maxHealth, float absorption, boolean showHealth) {
      if (!showHealth) {
         return NAME;
      }
      float hp = Math.max(0.0F, health);
      float abs = Math.max(0.0F, absorption);
      float ratio = maxHealth > 0.0F ? hp / maxHealth : 1.0F;
      ChatFormatting hpColor = ratio > 0.6F
         ? ChatFormatting.GREEN
         : (ratio > 0.3F ? ChatFormatting.YELLOW : ChatFormatting.RED);
      MutableComponent tag = Component.literal("MacherBot")
         .append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
         .append(Component.literal(String.format(Locale.GERMANY, "%.1f", hp) + " ❤").withStyle(hpColor));
      if (abs > 0.05F) {
         tag.append(Component.literal(" +" + String.format(Locale.GERMANY, "%.1f", abs) + " ❤").withStyle(ChatFormatting.GOLD));
      }
      return tag;
   }

   private static ResolvableProfile bundledProfile() {
      JsonObject var0 = new JsonObject();
      var0.addProperty("texture", "pvptrainer:entity/macher_steve");
      var0.addProperty("model", PlayerModelType.WIDE.getSerializedName());
      return (ResolvableProfile)ResolvableProfile.CODEC.parse(JsonOps.INSTANCE, var0).getOrThrow();
   }
}

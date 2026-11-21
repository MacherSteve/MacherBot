package de.pvptrainer;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.item.component.ResolvableProfile;

final class MacherAppearance {
   static final Component NAME = Component.literal("MacherBot");
   static final ResolvableProfile PROFILE = bundledProfile();

   private MacherAppearance() {
   }

   private static ResolvableProfile bundledProfile() {
      JsonObject var0 = new JsonObject();
      var0.addProperty("texture", "pvptrainer:entity/macher_steve");
      var0.addProperty("model", PlayerModelType.WIDE.getSerializedName());
      return (ResolvableProfile)ResolvableProfile.CODEC.parse(JsonOps.INSTANCE, var0).getOrThrow();
   }
}

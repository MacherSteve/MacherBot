package de.pvptrainer.hooks;

import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemCooldowns;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ItemCooldowns.class)
public interface CooldownAccess {
   @Accessor("cooldowns")
   Map<Identifier, ?> pvptrainer$groups();

   @Accessor("tickCount")
   int pvptrainer$clock();

   @Mixin(targets = "net.minecraft.world.item.ItemCooldowns$CooldownInstance")
   interface Timer {
      @Accessor("endTime")
      int pvptrainer$end();
   }
}

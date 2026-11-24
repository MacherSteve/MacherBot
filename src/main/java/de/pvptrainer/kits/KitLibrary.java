package de.pvptrainer.kits;
import de.pvptrainer.arena.MacherAppearance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class KitLibrary {
   private static final Logger LOG = LoggerFactory.getLogger("pvptrainer-kits");
   private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
   private static final Component OLD_CRYSTAL_LABEL = Component.literal("ɴᴀᴘᴠᴘ.ᴜѕ");
   private static final Set<Item> OLD_LABELED_GEAR = Set.of(
      Items.NETHERITE_HELMET,
      Items.NETHERITE_CHESTPLATE,
      Items.NETHERITE_LEGGINGS,
      Items.NETHERITE_BOOTS,
      Items.NETHERITE_SWORD,
      Items.CROSSBOW,
      Items.SHIELD,
      Items.NETHERITE_AXE,
      Items.MACE,
      Items.NETHERITE_PICKAXE
   );
   private static final JsonObject PRESETS;
   private static JsonObject data;
   private static Path file;
   private static String warning;
   private static boolean readOnly;

   static {
      try (InputStream stream = Objects.requireNonNull(KitLibrary.class.getResourceAsStream("/assets/pvptrainer/kits.json"));
           InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
         PRESETS = JsonParser.parseReader(reader).getAsJsonObject();
      } catch (IOException e) {
         throw new UncheckedIOException(e);
      }
      warning = "";
   }

   static synchronized void configure(Path var0) {
      file = var0;
      data = null;
      warning = "";
      readOnly = false;
   }

   public static synchronized String notice() {
      return warning;
   }

   private static JsonObject data() {
      if (file == null) {
         UUID var0 = Minecraft.getInstance().getUser().getProfileId();
         file = FabricLoader.getInstance().getConfigDir().resolve("pvptrainer/kits").resolve(var0 + ".json");
      }

      if (data != null) {
         return data;
      }

      data = new JsonObject();
      if (Files.exists(file)) {
         try {
            data = read(file);

            for (String var3 : List.of("custom", "selected", "layouts", "modeKits")) {
               if (data.has(var3) && !data.get(var3).isJsonObject()) {
                  warnDamaged(new IllegalArgumentException("Ungültiges Feld " + var3));
                  data.remove(var3);
               }
            }
         } catch (RuntimeException var2) {
            warnDamaged(var2);
         }
      }

      return data;
   }

   private static void warnDamaged(RuntimeException var0) {
      if (warning.isEmpty()) {
         LOG.warn("Kitprofil konnte nicht vollständig gelesen werden: {}", file, var0);

         try {
            Path var1 = Files.createTempFile(file.getParent(), "kitprofil-" + Instant.now().toEpochMilli() + "-", ".json");
            Files.copy(file, var1, StandardCopyOption.REPLACE_EXISTING);
            warning = "Ein Kit-Eintrag ist beschädigt. Original gesichert als " + var1.getFileName();
         } catch (IOException var2) {
            readOnly = true;
            warning = "Kitprofil unlesbar. Vorlagen verfügbar. Bitte prüfe die Datei vor dem Speichern.";
            LOG.warn("Original des Kitprofils konnte nicht gesichert werden", var2);
         }
      }
   }

   static JsonObject read(Path path) {
      try (BufferedReader reader = Files.newBufferedReader(path)) {
         return JsonParser.parseReader(reader).getAsJsonObject();
      } catch (IOException | RuntimeException ex) {
         throw new IllegalStateException("Kit-Datei nicht lesbar: " + path.getFileName(), ex);
      }
   }

   static void write(Path path, JsonObject content) {
      try {
         Files.createDirectories(path.getParent());
         Path pending = Files.createTempFile(path.getParent(), "pvpt-", ".tmp");
         try {
            try (FileOutputStream out = new FileOutputStream(pending.toFile());
                 OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
               JSON.toJson(content, writer);
               writer.flush();
               out.getFD().sync();
            }
            try {
               Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
               Files.move(pending, path, StandardCopyOption.REPLACE_EXISTING);
            }
         } finally {
            Files.deleteIfExists(pending);
         }
      } catch (IOException ex) {
         throw new IllegalStateException("Speichern fehlgeschlagen: " + path.getFileName(), ex);
      }
   }

   private static void change(Consumer<JsonObject> var0) {
      JsonObject var1 = data().deepCopy();
      if (readOnly) {
         throw new IllegalStateException(warning);
      }

      var0.accept(var1);
      write(file, var1);
      data = var1;
   }

   private static JsonObject section(JsonObject var0, String var1) {
      if (!var0.has(var1)) {
         var0.add(var1, new JsonObject());
      }

      return var0.getAsJsonObject(var1);
   }

   private static String modeKey(TrainingSettings.Mode var0) {
      return var0 == TrainingSettings.Mode.CRYSTAL ? "ltcrystal" : var0.name().toLowerCase(Locale.ROOT);
   }

   private static String defaultId(TrainingSettings.Mode var0) {
      return "pvpprac-" + modeKey(var0);
   }

   private static String selectionKey(TrainingSettings.Mode var0, boolean var1) {
      return var0.name() + (var1 ? "-bot" : "-player");
   }

   public static List<ItemStack> emptySlots() {
      return new ArrayList<>(Collections.nCopies(41, ItemStack.EMPTY));
   }

   public static <T> JsonElement encode(Provider var0, Codec<T> var1, T var2) {
      return (JsonElement)var1.encodeStart(var0.createSerializationContext(JsonOps.INSTANCE), var2).getOrThrow();
   }

   public static <T> T decode(Provider var0, Codec<T> var1, JsonElement var2) {
      return (T)var1.parse(var0.createSerializationContext(JsonOps.INSTANCE), var2).getOrThrow();
   }

   private static <T> Holder<T> entry(Provider var0, ResourceKey<? extends Registry<T>> var1, String var2) {
      return var0.lookupOrThrow(var1).getOrThrow(ResourceKey.create(var1, Identifier.parse(var2)));
   }

   public static KitLoadout builtin(Provider var0, TrainingSettings.Mode var1) {
      JsonObject var2 = PRESETS.getAsJsonObject(modeKey(var1));
      List var3 = emptySlots();
      JsonArray var4 = var2.getAsJsonArray("inventory");

      for (int var5 = 0; var5 < 36; var5++) {
         var3.set(var5, presetStack(var0, var4.get(var5)));
      }

      String[] var10 = new String[]{"boots", "leggings", "chestplate", "helmet", "offhand"};

      for (int var6 = 0; var6 < var10.length; var6++) {
         var3.set(36 + var6, presetStack(var0, var2.get(var10[var6])));
      }

      ArrayList var11 = new ArrayList();
      if (var2.has("effects")) {
         for (JsonElement var7 : var2.getAsJsonArray("effects")) {
            JsonObject var9 = var7.getAsJsonObject();
            var11.add(
               new MobEffectInstance(
                  entry(var0, Registries.MOB_EFFECT, var9.get("type").getAsString()), var9.get("duration").getAsInt(), var9.get("amplifier").getAsInt()
               )
            );
         }
      }

      if (var1 == TrainingSettings.Mode.UHC) styleGoldenHeads(var3);
      return new KitLoadout(defaultId(var1), var1.label() + "-Standard", "PvPPrac · 19.11.2026", var3, var11);
   }

   private static void styleGoldenHeads(List<ItemStack> slots) {
      for (ItemStack stack : slots) {
         if (MacherItems.isHealingHead(stack)) stack.set(DataComponents.PROFILE, MacherAppearance.PROFILE);
      }
   }

   private static ItemStack presetStack(Provider var0, JsonElement var1) {
      if (var1 != null && !var1.isJsonNull()) {
         JsonObject var2 = var1.getAsJsonObject();
         ItemStack var3 = new ItemStack(entry(var0, Registries.ITEM, var2.get("material").getAsString()), var2.get("amount").getAsInt());
         if (var2.has("enchants")) {
            for (JsonElement var4 : var2.getAsJsonArray("enchants")) {
               JsonObject var6 = var4.getAsJsonObject();
               var3.enchant(entry(var0, Registries.ENCHANTMENT, var6.get("id").getAsString()), var6.get("level").getAsInt());
            }
         }

         if (var2.has("name")) {
            var3.set(DataComponents.CUSTOM_NAME, Component.literal(var2.get("name").getAsString()));
         }

         if (var2.has("potion")) {
            var3.set(DataComponents.POTION_CONTENTS, new PotionContents(entry(var0, Registries.POTION, var2.get("potion").getAsString())));
         }

         if (var3.is(Items.PLAYER_HEAD) && var2.has("name") && var2.get("name").getAsString().startsWith("Golden Head")) {
            var3.set(
               DataComponents.CONSUMABLE,
               Consumable.builder()
                  .consumeSeconds(1.0F)
                  .onConsume(
                     new ApplyStatusEffectsConsumeEffect(
                        List.of(new MobEffectInstance(MobEffects.REGENERATION, 200, 1), new MobEffectInstance(MobEffects.ABSORPTION, 2400, 0))
                     )
                  )
                  .build()
            );
         }

         return var3;
      } else {
         return ItemStack.EMPTY;
      }
   }

   public static synchronized List<KitLoadout> list(Provider var0, TrainingSettings.Mode var1) {
      ArrayList var2 = new ArrayList();
      var2.add(builtin(var0, var1));

      for (Entry var3 : section(data(), "custom").entrySet()) {
         try {
            JsonObject var5 = ((JsonElement)var3.getValue()).getAsJsonObject();
            if (validMode(var5)) {
               var2.add(custom(var0, (String)var3.getKey(), var5));
            }
         } catch (RuntimeException var6) {
            warnDamaged(var6);
         }
      }

      return var2;
   }

   private static KitLoadout custom(Provider var0, String var1, JsonObject var2) {
      String var3 = var2.get("name").getAsString();
      String var4 = var2.has("source") ? var2.get("source").getAsString() : "Eigenes Kit";
      if (var4.equals("Eigene Kopie · PvPPrac · 19.11.2026")) {
         TrainingSettings.Mode var5 = TrainingSettings.Mode.valueOf(var2.get("mode").getAsString());
         String var6 = "PvPPrac · " + (var5 == TrainingSettings.Mode.CRYSTAL ? "Crystal (Eval)" : var5.label());
         if (var3.startsWith(var6) && var3.substring(var6.length()).matches("(?: · (?:Kopie|Eigen))+")) {
            var3 = var5.label() + "-Standard" + var3.substring(var6.length());
         }
      }

      List<ItemStack> slots = decode(var0, ItemStack.OPTIONAL_CODEC.listOf(), var2.get("slots"));
      if ("UHC".equals(var2.get("mode").getAsString())) styleGoldenHeads(slots);
      return new KitLoadout(
         var1,
         var3,
         var4,
         slots,
         decode(var0, MobEffectInstance.CODEC.listOf(), var2.get("effects"))
      );
   }

   private static boolean validMode(JsonObject record) {
      String name = record.get("mode").getAsString();
      for (var mode : TrainingSettings.Mode.values()) if (mode.name().equals(name)) return true;
      return false;
   }

   public static String modeKitId(TrainingSettings.Mode mode, boolean bot) {
      return "mode-kit-" + selectionKey(mode, bot);
   }

   public static synchronized KitLoadout modeKit(Provider registry, TrainingSettings.Mode mode, boolean bot) {
      String id = modeKitId(mode, bot);
      JsonObject record = section(data(), "modeKits").getAsJsonObject(id);
      if (record != null) {
         try {
            return custom(registry, id, record);
         } catch (RuntimeException ex) {
            warnDamaged(ex);
         }
      }
      return builtin(registry, mode);
   }

   static synchronized KitLoadout find(Provider registry, TrainingSettings.Mode mode, String id) {
      if (defaultId(mode).equals(id)) return builtin(registry, mode);
      if (modeKitId(mode, false).equals(id)) return modeKit(registry, mode, false);
      if (modeKitId(mode, true).equals(id)) return modeKit(registry, mode, true);
      JsonObject record = section(data(), "custom").getAsJsonObject(id);
      if (record != null && validMode(record)) return custom(registry, id, record);
      throw new IllegalArgumentException("Dieses Kit ist nicht mehr verfügbar.");
   }

   public static synchronized KitLoadout selected(Provider registry, TrainingSettings.Mode mode, boolean bot) {
      JsonElement selected = section(data(), "selected").get(selectionKey(mode, bot));
      if (selected != null) {
         try {
            return find(registry, mode, selected.getAsString());
         } catch (RuntimeException ex) {
            warnDamaged(ex);
         }
      }
      return modeKit(registry, mode, bot);
   }

   public static synchronized void select(TrainingSettings.Mode mode, boolean bot, String id) {
      if (!defaultId(mode).equals(id) && !modeKitId(mode,bot).equals(id)) {
         JsonObject entry = section(data(), "custom").getAsJsonObject(id);
         if (entry == null || !validMode(entry)) throw new IllegalArgumentException("Dieses Kit ist nicht mehr verfügbar.");
      }
      change(profile -> section(profile, "selected").addProperty(selectionKey(mode, bot), id));
   }

   public static synchronized void delete(String id) {
      if (id == null || id.startsWith("pvpprac-") || id.startsWith("mode-kit-")) {
         throw new IllegalArgumentException("Nur eigene Kits können gelöscht werden.");
      }
      change(profile -> {
         JsonObject custom = section(profile, "custom");
         if (!custom.has(id)) throw new IllegalArgumentException("Dieses Kit fehlt bereits.");
         custom.remove(id);
         section(profile, "layouts").remove(id);
         section(profile, "selected").entrySet().removeIf(e ->
            e.getValue().isJsonPrimitive() && id.equals(e.getValue().getAsString()));
      });
   }

   public static synchronized void resetModeKit(TrainingSettings.Mode mode, boolean bot) {
      String id = modeKitId(mode, bot);
      change(profile -> {
         section(profile, "modeKits").remove(id);
         section(profile, "layouts").remove(id);
         JsonObject selected = section(profile,"selected");
         String role = selectionKey(mode,bot);
         if (selected.has(role) && id.equals(selected.get(role).getAsString())) selected.remove(role);
      });
   }

   public static KitLoadout capture(Player var0) {
      return new KitLoadout(null, "Mein Inventar", "Inventar", inventory(var0), new ArrayList<>(var0.getActiveEffects()));
   }

   public static List<ItemStack> inventory(Player var0) {
      List var1 = emptySlots();

      for (int var2 = 0; var2 < var1.size(); var2++) {
         var1.set(var2, var0.getInventory().getItem(var2).copy());
      }

      return var1;
   }

   public static synchronized KitLoadout save(Provider var0, TrainingSettings.Mode var1, String var2, String var3, List<ItemStack> var4, List<MobEffectInstance> var5) {
      return save(var0, var1, var2, var3, var4, var5, null);
   }

   public static synchronized KitLoadout save(
      Provider var0, TrainingSettings.Mode var1, String var2, String var3, List<ItemStack> var4, List<MobEffectInstance> var5, String var6
   ) {
      if (var3 == null || var3.strip().isEmpty() || var3.strip().length() > 80) {
         throw new IllegalArgumentException("Kit-Name muss 1 bis 80 Zeichen enthalten.");
      }

      if (var4.size() != 41) {
         throw new IllegalArgumentException("Das Kit hat nicht 41 Plätze.");
      }

      for (ItemStack var7 : var4) {
         if (!var7.isEmpty() && var7.getCount() > var7.getMaxStackSize()) {
            throw new IllegalArgumentException("Unzulässige Stapelgröße.");
         }
      }

      String var10 = var2 == null ? UUID.randomUUID().toString() : var2;
      boolean modeKit = modeKitId(var1,false).equals(var2) || modeKitId(var1,true).equals(var2);
      if (var2 != null && !modeKit) {
         JsonObject var11 = section(data(), "custom").getAsJsonObject(var2);
         if (var11 == null || !validMode(var11)) {
            throw new IllegalArgumentException("Die Vorlage muss als Kopie gespeichert werden.");
         }
      }

      JsonObject var12 = new JsonObject();
      var12.addProperty("mode", var1.name());
      var12.addProperty("name", var3.strip());
      if (modeKit) {
         var12.addProperty("source", "Modus-Kit · " + var1.label());
      } else if (var6 != null && !var6.isBlank()) {
         if (var6.length() > 160) {
            throw new IllegalArgumentException("Die Kit-Quelle ist zu lang.");
         }

         var12.addProperty("source", var6.strip());
      } else if (var2 != null && !modeKit) {
         JsonElement var9 = section(data(), "custom").getAsJsonObject(var2).get("source");
         if (var9 != null) {
            var12.add("source", var9.deepCopy());
         }
      }

      var12.add("slots", encode(var0, ItemStack.OPTIONAL_CODEC.listOf(), var4));
      var12.add("effects", encode(var0, MobEffectInstance.CODEC.listOf(), var5));
      change(var2x -> {
         section(var2x, modeKit ? "modeKits" : "custom").add(var10, var12);
         section(var2x, "layouts").remove(var10);
      });
      return custom(var0, var10, var12);
   }

   public static synchronized KitLoadout personalized(Provider var0, TrainingSettings.Mode var1, String var2) {
      KitLoadout var3 = find(var0, var1, var2);
      JsonElement var4 = section(data(), "layouts").get(var2);
      if (var4 == null) {
         return var3;
      }

      try {
         List<ItemStack> var5 = decode(var0, ItemStack.OPTIONAL_CODEC.listOf(), var4);
         if (var3.slots().stream().anyMatch(stack -> MacherItems.isHealingHead(stack)
             && MacherAppearance.PROFILE.equals(stack.get(DataComponents.PROFILE)))) styleGoldenHeads(var5);
         if (defaultId(TrainingSettings.Mode.CRYSTAL).equals(var3.id())) {
            // Built-in layouts can rearrange slots but have no editable enchantments. Preserve
            // previous arrangements by migrating legacy Blast Protection IV boots/leggings.
            ItemStack updatedBoots = var3.stack(36);
            ItemStack legacyBoots = updatedBoots.copy();
            ItemStack updatedLeggings = var3.stack(37);
            ItemStack legacyLeggings = updatedLeggings.copy();
            var enchants = var0.lookupOrThrow(Registries.ENCHANTMENT);
            var protection = enchants.getOrThrow(Enchantments.PROTECTION);
            var blastProtection = enchants.getOrThrow(Enchantments.BLAST_PROTECTION);
            EnchantmentHelper.updateEnchantments(legacyBoots, levels -> {
               levels.set(protection, 0);
               levels.set(blastProtection, 4);
            });
            EnchantmentHelper.updateEnchantments(legacyLeggings, levels -> {
               levels.set(protection, 0);
               levels.set(blastProtection, 4);
            });
            for (ItemStack var6 : var5) {
               if (OLD_LABELED_GEAR.contains(var6.getItem()) && OLD_CRYSTAL_LABEL.equals(var6.get(DataComponents.CUSTOM_NAME))) {
                  var6.remove(DataComponents.CUSTOM_NAME);
               }
               if (ItemStack.isSameItemSameComponents(var6, legacyBoots)) {
                  var6.set(DataComponents.ENCHANTMENTS, updatedBoots.get(DataComponents.ENCHANTMENTS));
               }
               if (ItemStack.isSameItemSameComponents(var6, legacyLeggings)) {
                  var6.set(DataComponents.ENCHANTMENTS, updatedLeggings.get(DataComponents.ENCHANTMENTS));
               }
            }
         }

         if (var5.size() == 41 && TrainingKit.sameContents(var3.slots(), var5)) {
            return new KitLoadout(var2, var3.name(), var3.source(), var5, var3.effects());
         }

         warnDamaged(new IllegalArgumentException("Sortierung passt nicht zum Inhalt des Kits."));
      } catch (RuntimeException var8) {
         warnDamaged(var8);
      }

      return var3;
   }

   public static synchronized void resetLayout(TrainingSettings.Mode var0, String var1) {
      change(var1x -> section(var1x, "layouts").remove(var1));
   }

   static synchronized boolean remember(Provider var0, TrainingSettings.Mode var1, KitLoadout var2, List<ItemStack> var3) {
      if (!var2.preset() && (!section(data(), "custom").has(var2.id()) && !section(data(), "modeKits").has(var2.id())
         || !TrainingKit.sameContents(find(var0, var1, var2.id()).slots(), var2.slots()))) {
         return false;
      } else {
         JsonElement var4 = encode(var0, ItemStack.OPTIONAL_CODEC.listOf(), var3);
         if (!var4.equals(section(data(), "layouts").get(var2.id()))) {
            change(var2x -> section(var2x, "layouts").add(var2.id(), var4));
            return true;
         } else {
            return false;
         }
      }
   }

   public static String fingerprint(Provider var0, KitLoadout var1) {
      List<ItemStack> var2 = var1.slots();
      // Keep pre-skin UHC setups in the same analytics group.
      for (ItemStack stack : var2) {
         if (MacherItems.isHealingHead(stack) && MacherAppearance.PROFILE.equals(stack.get(DataComponents.PROFILE)))
            stack.set(DataComponents.PROFILE, Items.PLAYER_HEAD.getDefaultInstance().get(DataComponents.PROFILE));
      }
      if (defaultId(TrainingSettings.Mode.CRYSTAL).equals(var1.id())) {
         for (ItemStack var3 : var2) {
            if (OLD_LABELED_GEAR.contains(var3.getItem()) && !var3.has(DataComponents.CUSTOM_NAME)) {
               var3.set(DataComponents.CUSTOM_NAME, OLD_CRYSTAL_LABEL);
            }
         }
      }

      String var6 = var1.id()
         + "|"
         + canonical(encode(var0, ItemStack.OPTIONAL_CODEC.listOf(), var2))
         + "|"
         + canonical(encode(var0, MobEffectInstance.CODEC.listOf(), var1.effects()));

      try {
         return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(var6.getBytes(StandardCharsets.UTF_8)));
      } catch (NoSuchAlgorithmException var5) {
         throw new AssertionError(var5);
      }
   }

   private static JsonElement canonical(JsonElement var0) {
      if (var0.isJsonObject()) {
         JsonObject var2 = new JsonObject();
         new TreeMap<String, JsonElement>(var0.getAsJsonObject().asMap()).forEach((var1x, var2x) -> var2.add(var1x, canonical(var2x)));
         return var2;
      } else if (var0.isJsonArray()) {
         JsonArray var1 = new JsonArray();
         var0.getAsJsonArray().forEach(var1x -> var1.add(canonical(var1x)));
         return var1;
      } else {
         return var0;
      }
   }
}

package de.pvptrainer.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.pvptrainer.drill.Drill;
import de.pvptrainer.kits.TrainingSettings;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.LoggerFactory;

/** Datengetriebener PvP-Technik-Guide (assets/pvptrainer/knowledge_guide.json). */
public final class KnowledgeGuide {
   private static final String RESOURCE_PATH = "/assets/pvptrainer/knowledge_guide.json";
   private static final List<Entry> ENTRIES = load();

   private KnowledgeGuide() {
   }

   public static List<Entry> all() {
      return ENTRIES;
   }

   public static List<Entry> byCategory(Category category) {
      return ENTRIES.stream().filter(e -> e.category() == category).toList();
   }

   public static Entry forDrill(Drill drill, TrainingSettings.Mode mode) {
      if (drill != null) {
         Category preferred = mode == null ? null : Category.fromMode(mode);
         if (preferred != null) {
            for (Entry entry : ENTRIES) {
               if (entry.drill() == drill && entry.category() == preferred) {
                  return entry;
               }
            }
         }
         for (Entry entry : ENTRIES) {
            if (entry.drill() == drill) {
               return entry;
            }
         }
      }
      if (mode != null) {
         Category cat = Category.fromMode(mode);
         List<Entry> inCat = byCategory(cat);
         if (!inCat.isEmpty()) {
            return inCat.getFirst();
         }
      }
      return ENTRIES.isEmpty() ? null : ENTRIES.getFirst();
   }

   public static ItemStack iconStack(String itemId) {
      if (itemId == null || itemId.isBlank()) {
         return new ItemStack(Items.BOOK);
      }
      try {
         Identifier id = itemId.contains(":")
            ? Identifier.parse(itemId)
            : Identifier.fromNamespaceAndPath("minecraft", itemId);
         Item item = BuiltInRegistries.ITEM.getValue(id);
         return item == Items.AIR ? new ItemStack(Items.BOOK) : new ItemStack(item);
      } catch (RuntimeException ignored) {
         return new ItemStack(Items.BOOK);
      }
   }

   private static List<Entry> load() {
      try (InputStream in = KnowledgeGuide.class.getResourceAsStream(RESOURCE_PATH)) {
         if (in == null) {
            LoggerFactory.getLogger("pvptrainer").warn("Knowledge Guide fehlt: {}", RESOURCE_PATH);
            return List.of();
         }
         JsonArray array = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
         List<Entry> result = new ArrayList<>(array.size());
         for (JsonElement element : array) {
            JsonObject obj = element.getAsJsonObject();
            String id = str(obj, "id");
            Category category = Category.valueOf(str(obj, "category"));
            String title = str(obj, "title");
            String icon = str(obj, "icon");
            String summary = str(obj, "summary");
            String timing = str(obj, "timing");
            String example = str(obj, "example");
            List<String> steps = new ArrayList<>();
            if (obj.has("steps") && obj.get("steps").isJsonArray()) {
               for (JsonElement step : obj.getAsJsonArray("steps")) {
                  steps.add(step.getAsString());
               }
            }
            String mistakes = str(obj, "mistakes");
            TrainingSettings.Mode mode = obj.has("mode") && !obj.get("mode").isJsonNull()
               ? TrainingSettings.Mode.valueOf(obj.get("mode").getAsString())
               : null;
            Drill drill = obj.has("drill") && !obj.get("drill").isJsonNull()
               ? Drill.valueOf(obj.get("drill").getAsString())
               : null;
            result.add(new Entry(id, category, title, icon, summary, timing, example, List.copyOf(steps), mistakes, mode, drill));
         }
         return List.copyOf(result);
      } catch (IOException | RuntimeException error) {
         LoggerFactory.getLogger("pvptrainer").warn("Knowledge Guide konnte nicht geladen werden", error);
         return List.of();
      }
   }

   private static String str(JsonObject obj, String key) {
      return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
   }

   public enum Category {
      BASICS("Grundlagen"),
      SWORD("Sword"),
      UHC("UHC"),
      CRYSTAL("Crystal"),
      MACE("Mace"),
      HUGOSMP("HugoSMP");

      private final String label;

      Category(String label) {
         this.label = label;
      }

      public String label() {
         return this.label;
      }

      public static Category fromMode(TrainingSettings.Mode mode) {
         return switch (mode) {
            case SWORD -> SWORD;
            case UHC -> UHC;
            case CRYSTAL -> CRYSTAL;
            case MACE -> MACE;
            case HUGOSMP -> HUGOSMP;
         };
      }
   }

   public record Entry(
      String id,
      Category category,
      String title,
      String icon,
      String summary,
      String timing,
      String example,
      List<String> steps,
      String mistakes,
      TrainingSettings.Mode mode,
      Drill drill
   ) {
   }
}

package de.pvptrainer.kits;
import de.pvptrainer.drill.Drill;
import de.pvptrainer.drill.DrillOptions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TrainingProfiles {
   private static final Logger LOG = LoggerFactory.getLogger("pvptrainer");
   private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
   private final EnumMap<TrainingSettings.Mode, TrainingSettings> profiles = new EnumMap<>(TrainingSettings.Mode.class);
   private final EnumMap<TrainingSettings.Mode, DrillOptions> exercises = new EnumMap<>(TrainingSettings.Mode.class);
   private final Path file;
   private TrainingSettings.Mode selected = TrainingSettings.Mode.SWORD;

   public TrainingProfiles(Path var1) {
      this.file = var1;
      for (TrainingSettings.Mode var2 : TrainingSettings.Mode.values()) {
         this.profiles.put(var2, TrainingSettings.defaults(var2));
         this.exercises.put(var2, DrillOptions.defaults(var2));
      }

      if (Files.isRegularFile(var1)) {
         try (BufferedReader var30 = Files.newBufferedReader(var1)) {
            JsonObject var31 = JsonParser.parseReader(var30).getAsJsonObject();
            JsonObject var6 = var31.getAsJsonObject("profiles");
            if (var6 != null) {
               for (TrainingSettings.Mode var7 : TrainingSettings.Mode.values()) {
                  if (var6.has(var7.name())) {
                     try {
                        TrainingSettings var11 = JSON.fromJson(var6.get(var7.name()), TrainingSettings.class);
                        if (var11 != null && var11.mode() == var7) {
                           this.profiles.put(var7, var11);
                        }
                     } catch (RuntimeException var24) {
                        LOG.warn("Ungültiges Trainingsprofil für {}", var7, var24);
                     }
                  }
               }
            }

            JsonObject var32 = var31.getAsJsonObject("exercises");
            for (TrainingSettings.Mode var33 : TrainingSettings.Mode.values()) {
               if (var32 != null && var32.has(var33.name())) {
                  try {
                     DrillOptions var12 = JSON.fromJson(var32.get(var33.name()), DrillOptions.class);
                     if (var12 != null && var12.drill().supports(var33)) {
                        this.exercises.put(var33, var12);
                     }
                  } catch (RuntimeException var23) {
                     LOG.warn("Ungültige Übungsoptionen für {}", var33, var23);
                  }
               } else if (var6 != null && var6.has(var33.name()) && this.profiles.get(var33).counterAttacks()) {
                  this.exercises.put(var33, DrillOptions.forDrill(Drill.DUEL));
               }
            }

            if (var31.has("selected")) {
               this.selected = TrainingSettings.Mode.valueOf(var31.get("selected").getAsString());
            }
         } catch (IOException | RuntimeException var27) {
            LOG.warn("Trainingsprofile konnten nicht vollständig geladen werden", var27);
         }
      }
   }

   public TrainingSettings selected() {
      return this.profiles.get(this.selected);
   }

   public TrainingSettings get(TrainingSettings.Mode var1) {
      return this.profiles.get(var1);
   }

   public void save(TrainingSettings var1) {
      if (this.selected != var1.mode() || !var1.equals(this.profiles.get(this.selected)) || !Files.isRegularFile(this.file)) {
         this.selected = var1.mode();
         this.profiles.put(this.selected, var1);
         this.write();
      }
   }

   public DrillOptions options(TrainingSettings.Mode var1) {
      return this.exercises.get(var1);
   }

   public void saveSetup(TrainingSettings var1, DrillOptions var2) {
      TrainingSettings.Mode var3 = var1.mode();
      if (!var2.drill().supports(var3)) {
         throw new IllegalArgumentException("Übung gehört zu einem anderen Modus.");
      }

      if (this.selected != var3 || !var1.equals(this.profiles.get(var3)) || !var2.equals(this.exercises.get(var3)) || !Files.isRegularFile(this.file)) {
         this.selected = var3;
         this.profiles.put(var3, var1);
         this.exercises.put(var3, var2);
         this.write();
      }
   }

   public void saveOptions(TrainingSettings.Mode var1, DrillOptions var2) {
      if (!var2.drill().supports(var1)) {
         throw new IllegalArgumentException("Übung gehört zu einem anderen Modus.");
      }

      if (!var2.equals(this.exercises.get(var1))) {
         this.exercises.put(var1, var2);
         this.write();
      }
   }

   private void write() {
      JsonObject var1 = new JsonObject();
      var1.addProperty("selected", this.selected.name());
      var1.add("profiles", JSON.toJsonTree(this.profiles));
      var1.add("exercises", JSON.toJsonTree(this.exercises));

      try {
         Files.createDirectories(this.file.getParent());
         Path var2 = Files.createTempFile(this.file.getParent(), "pvptrainer-", ".tmp");

         try {
            try (BufferedWriter var5 = Files.newBufferedWriter(var2)) {
               JSON.toJson(var1, var5);
            }

            try {
               Files.move(var2, this.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException var22) {
               Files.move(var2, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
         } finally {
            Files.deleteIfExists(var2);
         }
      } catch (IOException var26) {
         LOG.warn("Trainingsprofile konnten nicht gespeichert werden", var26);
      }
   }
}

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;
import net.fabricmc.tinyremapper.extension.mixin.MixinExtension;

/** Remapt kompilierte Klassen (inkl. Mixin-Annotationen via MixinExtension) deterministisch zwischen Namespaces. */
public final class RemapTool {
    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("Usage: RemapTool <input> <output> <mappings.tiny> <fromNs> <toNs> [--mixins] [classpath...]");
            System.exit(2);
        }
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        Path mappings = Path.of(args[2]);
        String fromNs = args[3];
        String toNs = args[4];
        boolean mixins = false;
        List<Path> classpath = new ArrayList<>();
        for (int i = 5; i < args.length; i++) {
            if ("--mixins".equals(args[i])) {
                mixins = true;
            } else {
                classpath.add(Path.of(args[i]));
            }
        }

        TinyRemapper.Builder builder = TinyRemapper.newRemapper()
            .withMappings(TinyUtils.createTinyMappingProvider(mappings, fromNs, toNs))
            .ignoreConflicts(true)
            .renameInvalidLocals(false)
            .threads(1);
        if (mixins) {
            builder.extension(new MixinExtension(EnumSet.allOf(MixinExtension.AnnotationTarget.class)));
        }
        TinyRemapper remapper = builder.build();
        try {
            if (!classpath.isEmpty()) {
                remapper.readClassPath(classpath.toArray(Path[]::new));
            }
            remapper.readInputs(input);

            if (output.getFileName().toString().endsWith(".jar")) {
                if (output.getParent() != null) {
                    Files.createDirectories(output.getParent());
                }
                Files.deleteIfExists(output);
                try (OutputConsumerPath consumer = new OutputConsumerPath.Builder(output).assumeArchive(true).build()) {
                    remapper.apply(consumer);
                }
            } else {
                Files.createDirectories(output);
                remapper.apply((name, bytes) -> {
                    try {
                        Path target = output.resolve(name + ".class");
                        if (target.getParent() != null) {
                            Files.createDirectories(target.getParent());
                        }
                        Files.write(target, bytes);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        } finally {
            remapper.finish();
        }
    }
}

import app.morphe.patcher.patch.PatchLoader;
import java.io.File;
import java.util.Collections;
import java.util.zip.ZipFile;

/** Verify bundle discovery and packaged DEX files without requiring a YouTube APK. */
public final class PatchBundleSmokeTest {
    public static void main(String[] args) throws Exception {
        File bundle = new File(args[0]);
        try (ZipFile zip = new ZipFile(bundle)) {
            for (String entry : new String[]{"classes.dex", "hebrew-helper.dex"}) {
                if (zip.getEntry(entry) == null || zip.getEntry(entry).getSize() == 0) {
                    throw new AssertionError("Missing or empty " + entry);
                }
            }
        }
        PatchLoader.Jar patches = new PatchLoader.Jar(Collections.singleton(bundle));
        if (patches.size() != 1 || !"Hebrew auto-translated subtitles".equals(
                patches.iterator().next().getName())) {
            throw new AssertionError("Expected exactly the Hebrew subtitle patch");
        }
        System.out.println("Morphe loaded the Hebrew subtitle patch; both DEX files are present.");
    }
}

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.patch.PatchLoader
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import java.io.File
import kotlinx.coroutines.runBlocking

/** Optional args: official bundle, then official-first (default) or hebrew-first. */
fun main(args: Array<String>) = runBlocking {
    val hebrew = PatchLoader.Jar(setOf(File(args[1]))).single()
    val patches = if (args.size >= 4) {
        val official = PatchLoader.Jar(setOf(File(args[3]))).single {
            it.name == "Hide player flyout menu components"
        }
        val sequence = bytecodePatch("Verify combined caption patches") {
            // Only add this driver as a root: dependency order forces both
            // permutations rather than allowing alphabetical root sorting.
            if (args.getOrNull(4) == "hebrew-first") dependsOn(hebrew, official)
            else dependsOn(official, hebrew)
            execute {
                var hebrewCalls = 0
                var morpheCalls = 0
                classDefForEach { clazz ->
                    // Count menu call sites, not calls inside either extension.
                    if (!clazz.type.startsWith("Lapp/")) {
                        clazz.methods.forEach { method ->
                            method.implementation?.instructions?.forEach { instruction ->
                                val ref = (instruction as? ReferenceInstruction)?.reference?.toString()
                                if (ref == "Lapp/revanced/extension/youtube/subtitle/HebrewSubtitlesHelper;->injectHebrewOption(Ljava/lang/Object;Landroid/widget/ListView;)V") hebrewCalls++
                                if (ref == "Lapp/morphe/extension/youtube/patches/HidePlayerFlyoutMenuPatch;->hideCaptionsOldBottomSheetFooter(Landroid/widget/ListView;Landroid/view/View;Ljava/lang/Object;Z)V") morpheCalls++
                            }
                        }
                    }
                }
                check(hebrewCalls == 2 && morpheCalls == 2) {
                    "Missing combined menu hooks: Hebrew=$hebrewCalls, Morphe=$morpheCalls"
                }
                println("COMBINED HOOKS PASSED: Hebrew=$hebrewCalls, Morphe=$morpheCalls")
            }
        }
        setOf(sequence)
    } else setOf(hebrew)
    Patcher(PatcherConfig(apkFile = File(args[0]), temporaryFilesPath = File(args[2]))).use { patcher ->
        patcher += patches
        patcher().collect { result ->
            result.exception?.let { throw it }
            println("PATCH EXECUTION PASSED: ${result.patch.name}")
        }
        patcher.get()
        println("PATCHED DEX SERIALIZATION PASSED")
    }
}

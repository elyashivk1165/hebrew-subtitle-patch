import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.patch.PatchLoader
import java.io.File
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) = runBlocking {
    Patcher(PatcherConfig(apkFile = File(args[0]), temporaryFilesPath = File(args[2]))).use { patcher ->
        patcher += PatchLoader.Jar(setOf(File(args[1])))
        patcher().collect { result ->
            result.exception?.let { throw it }
            println("PATCH EXECUTION PASSED: ${result.patch.name}")
        }
        patcher.get()
        println("PATCHED DEX SERIALIZATION PASSED")
    }
}

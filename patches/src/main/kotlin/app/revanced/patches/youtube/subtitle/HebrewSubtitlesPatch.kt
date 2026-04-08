package app.revanced.patches.youtube.subtitle

import app.revanced.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.InstructionExtensions.getInstruction
import app.revanced.patcher.fingerprint
import app.revanced.patcher.patch.Patch
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private fun Method.findInstructionIndex(filter: (com.android.tools.smali.dexlib2.iface.instruction.Instruction) -> Boolean): Int {
    val impl = implementation ?: return -1
    return impl.instructions.indexOfFirst(filter)
}

private fun Method.indexOfNewUrlRequestBuilderInstruction() =
    findInstructionIndex { instruction ->
        instruction.opcode == Opcode.INVOKE_VIRTUAL &&
        (instruction as? ReferenceInstruction)?.reference?.toString() ==
            "Lorg/chromium/net/CronetEngine;->newUrlRequestBuilder(Ljava/lang/String;Lorg/chromium/net/UrlRequest\$Callback;Ljava/util/concurrent/Executor;)Lorg/chromium/net/UrlRequest\$Builder;"
    }

private fun Method.indexOfBuildInstruction() =
    findInstructionIndex { instruction ->
        instruction.opcode == Opcode.INVOKE_VIRTUAL &&
        (instruction as? ReferenceInstruction)?.reference?.toString() ==
            "Lorg/chromium/net/UrlRequest\$Builder;->build()Lorg/chromium/net/UrlRequest;"
    }

@Suppress("DEPRECATION")
private val transcriptUrlFingerprint = fingerprint {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returns("L")
    custom { method, _ ->
        method.indexOfNewUrlRequestBuilderInstruction() >= 0 &&
        method.indexOfBuildInstruction() >= 0
    }
}

@Suppress("unused", "DEPRECATION")
val hebrewSubtitlesPatch: Patch = bytecodePatch(
    "Hebrew auto-translated subtitles",
    "Automatically adds Hebrew as the translation language for video subtitles.",
) {
    compatibleWith("com.google.android.youtube" to (null as Set<String>?))

    extendWith("hebrew-helper.dex")

    execute {
        val urlClassDef = transcriptUrlFingerprint.classDefOrNull
            ?: throw PatchException("Could not find transcript URL method")

        transcriptUrlFingerprint.match(urlClassDef).method.apply {
            val urlIndex = indexOfNewUrlRequestBuilderInstruction()
            val invoke = getInstruction<FiveRegisterInstruction>(urlIndex)
            val urlRegister = invoke.registerD

            val usedRegs = setOf(invoke.registerC, invoke.registerD, invoke.registerE, invoke.registerF)
            val tempReg = (0..15).first { it !in usedRegs }

            addInstructionsWithLabels(
                urlIndex,
                """
                    invoke-static { }, Landroid/app/ActivityThread;->currentApplication()Landroid/app/Application;
                    move-result-object v$tempReg
                    invoke-static { v$tempReg }, Lapp/revanced/extension/youtube/subtitle/HebrewSubtitlesHelper;->isEnabled(Landroid/content/Context;)Z
                    move-result v$tempReg
                    if-eqz v$tempReg, :skip
                    const-string v$tempReg, "timedtext"
                    invoke-virtual { v$urlRegister, v$tempReg }, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
                    move-result v$tempReg
                    if-eqz v$tempReg, :skip
                    const-string v$tempReg, "tlang="
                    invoke-virtual { v$urlRegister, v$tempReg }, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
                    move-result v$tempReg
                    if-nez v$tempReg, :skip
                    const-string v$tempReg, "&tlang=iw"
                    invoke-virtual { v$urlRegister, v$tempReg }, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$urlRegister
                    const/4 v$tempReg, 0x0
                """,
                ExternalLabel("skip", getInstruction(urlIndex))
            )
        }
    }
}

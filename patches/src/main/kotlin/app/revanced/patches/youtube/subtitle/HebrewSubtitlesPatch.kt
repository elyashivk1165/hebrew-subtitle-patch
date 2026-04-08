package app.revanced.patches.youtube.subtitle

import app.revanced.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.InstructionExtensions.getInstruction
import app.revanced.patcher.fingerprint
import app.revanced.patcher.patch.Patch
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
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

// Adds a SwitchPreference to revanced_prefs.xml (created by RVX settingsPatch).
// If the file is not present (RVX not applied alongside), this silently skips.
@Suppress("DEPRECATION")
private val hebrewSubtitlesResourcePatch = resourcePatch {
    execute {
        try {
            document("res/xml/revanced_prefs.xml").apply {
                documentElement.appendChild(
                    createElement("SwitchPreference").also {
                        it.setAttribute("android:key", "revanced_hebrew_subtitles_enabled")
                        it.setAttribute("android:title", "Hebrew auto-translated subtitles")
                        it.setAttribute("android:summaryOn", "Subtitles will be auto-translated to Hebrew")
                        it.setAttribute("android:summaryOff", "Hebrew subtitle auto-translation is disabled")
                        it.setAttribute("android:defaultValue", "true")
                    }
                )
            }.close()
        } catch (_: Exception) {
            // revanced_prefs.xml not available (RVX settings not applied alongside), skip.
        }
    }
}

@Suppress("unused", "DEPRECATION")
val hebrewSubtitlesPatch: Patch = bytecodePatch(
    "Hebrew auto-translated subtitles",
    "Automatically adds Hebrew as the translation language for video subtitles. " +
    "Toggle via RVX settings: 'Hebrew auto-translated subtitles'.",
) {
    compatibleWith("com.google.android.youtube" to (null as Set<String>?))
    dependsOn(hebrewSubtitlesResourcePatch)

    execute {
        val classDef = transcriptUrlFingerprint.classDefOrNull
            ?: throw PatchException("Could not find transcript URL method")

        transcriptUrlFingerprint.match(classDef).method.apply {
            val urlIndex = indexOfNewUrlRequestBuilderInstruction()
            val invoke = getInstruction<FiveRegisterInstruction>(urlIndex)
            val urlRegister = invoke.registerD

            // Avoid clobbering the invoke instruction's own registers.
            val usedRegs = setOf(invoke.registerC, invoke.registerD, invoke.registerE, invoke.registerF)
            // Use first free register within the method's existing register count.
            val maxReg = implementation!!.registerCount - 1
            val tempReg = (0..maxReg).first { it !in usedRegs }

            addInstructionsWithLabels(
                urlIndex,
                """
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

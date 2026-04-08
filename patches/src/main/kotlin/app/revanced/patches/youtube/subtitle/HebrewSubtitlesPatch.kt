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
            // Prefer high-numbered registers to reduce risk of overwriting method locals.
            val freeRegs = (15 downTo 0).filter { it !in usedRegs }.take(3)
            val tempReg  = freeRegs[0]
            val tempReg2 = freeRegs[1]
            val tempReg3 = freeRegs[2]

            addInstructionsWithLabels(
                urlIndex,
                """
                    invoke-static { }, Landroid/app/ActivityThread;->currentApplication()Landroid/app/Application;
                    move-result-object v$tempReg
                    const-string v$tempReg2, "revanced_prefs"
                    const/4 v$tempReg3, 0x0
                    invoke-virtual { v$tempReg, v$tempReg2, v$tempReg3 }, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;
                    move-result-object v$tempReg
                    const-string v$tempReg2, "revanced_hebrew_subtitles_enabled"
                    const/4 v$tempReg3, 0x1
                    invoke-interface { v$tempReg, v$tempReg2, v$tempReg3 }, Landroid/content/SharedPreferences;->getBoolean(Ljava/lang/String;Z)Z
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

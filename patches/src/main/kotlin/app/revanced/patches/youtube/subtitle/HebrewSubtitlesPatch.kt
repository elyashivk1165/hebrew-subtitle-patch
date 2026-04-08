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

// Fingerprint for the player bottom controls inflate — looks for ViewStub.inflate() calls
// in a class that manages the bottom UI container.
@Suppress("DEPRECATION")
private val playerBottomControlsFingerprint = fingerprint {
    strings("bottomUiContainerStub")
}

@Suppress("unused", "DEPRECATION")
val hebrewSubtitlesPatch: Patch = bytecodePatch(
    "Hebrew auto-translated subtitles",
    "Automatically adds Hebrew as the translation language for video subtitles, with a player button to toggle.",
) {
    compatibleWith("com.google.android.youtube" to (null as Set<String>?))

    extendWith("hebrew-helper.dex")

    execute {
        // ── URL interceptor ──────────────────────────────────────────────────────
        val urlClassDef = transcriptUrlFingerprint.classDefOrNull
            ?: throw PatchException("Could not find transcript URL method")

        transcriptUrlFingerprint.match(urlClassDef).method.apply {
            val urlIndex = indexOfNewUrlRequestBuilderInstruction()
            val invoke = getInstruction<FiveRegisterInstruction>(urlIndex)
            val urlRegister = invoke.registerD

            // tempReg: a free register not used by the invoke — reused for all temp work.
            val usedRegs = setOf(invoke.registerC, invoke.registerD, invoke.registerE, invoke.registerF)
            val tempReg = (0..15).first { it !in usedRegs }

            // With the extension DEX, isEnabled(Context) is a 1-arg call.
            // tempReg holds context → then bool result → then string args — no register expansion needed.
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

        // ── Player button ────────────────────────────────────────────────────────
        val btnClassDef = playerBottomControlsFingerprint.classDefOrNull
        if (btnClassDef != null) {
            playerBottomControlsFingerprint.match(btnClassDef).method.apply {
                // Use v0 at the top of the method — a safe local register at method entry.
                // We restore it to 0 before any original code runs.
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static { }, Landroid/app/ActivityThread;->currentActivity()Landroid/app/Activity;
                        move-result-object v0
                        if-eqz v0, :no_activity
                        invoke-static { v0 }, Lapp/revanced/extension/youtube/subtitle/HebrewSubtitlesHelper;->initButton(Landroid/app/Activity;)V
                        :no_activity
                        const/4 v0, 0x0
                    """
                )
            }
        }
        // If fingerprint not found, skip button silently — URL injection still works.
    }
}

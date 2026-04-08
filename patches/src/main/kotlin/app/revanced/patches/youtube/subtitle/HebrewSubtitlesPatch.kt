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
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
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

            val usedRegs = setOf(invoke.registerC, invoke.registerD, invoke.registerE, invoke.registerF)
            val tempReg = (0..15).first { it !in usedRegs }

            val mutableImpl = implementation as MutableMethodImplementation
            val ctxReg = mutableImpl.registerCount
            val boolReg = mutableImpl.registerCount + 1
            mutableImpl.registerCount += 2

            addInstructionsWithLabels(
                urlIndex,
                """
                    invoke-static { }, Landroid/app/ActivityThread;->currentApplication()Landroid/app/Application;
                    move-result-object v$ctxReg
                    invoke-static { v$ctxReg }, Lapp/revanced/extension/youtube/subtitle/HebrewSubtitlesHelper;->isEnabled(Landroid/content/Context;)Z
                    move-result v$boolReg
                    if-eqz v$boolReg, :skip
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
                val mutableImpl = implementation as MutableMethodImplementation
                val r0 = mutableImpl.registerCount
                val r1 = mutableImpl.registerCount + 1
                mutableImpl.registerCount += 2

                // Inject at index 0: call HebrewSubtitlesHelper.initButton(activity)
                // to add the toggle button to the player controls.
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static { }, Landroid/app/ActivityThread;->currentActivity()Landroid/app/Activity;
                        move-result-object v$r0
                        if-eqz v$r0, :no_activity
                        invoke-static { v$r0 }, Lapp/revanced/extension/youtube/subtitle/HebrewSubtitlesHelper;->initButton(Landroid/app/Activity;)V
                        :no_activity
                        const/4 v$r0, 0x0
                    """,
                    ExternalLabel("no_activity", getInstruction(0))
                )
            }
        }
        // If fingerprint not found, skip button silently — URL injection still works.
    }
}

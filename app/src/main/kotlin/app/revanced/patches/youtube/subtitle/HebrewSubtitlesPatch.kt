package app.revanced.patches.youtube.subtitle

import app.revanced.patcher.extensions.InstructionExtensions.addInstruction
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

private const val HELPER = "Lapp/revanced/extension/youtube/subtitle/HebrewSubtitlesHelper;"

// ── Helper extensions ─────────────────────────────────────────────────────────

private fun Method.findInstructionIndex(
    filter: (com.android.tools.smali.dexlib2.iface.instruction.Instruction) -> Boolean,
): Int {
    val impl = implementation ?: return -1
    return impl.instructions.indexOfFirst(filter)
}

private fun Method.indexOfNewUrlRequestBuilderInstruction() =
    findInstructionIndex { instr ->
        instr.opcode == Opcode.INVOKE_VIRTUAL &&
        (instr as? ReferenceInstruction)?.reference?.toString() ==
            "Lorg/chromium/net/CronetEngine;->newUrlRequestBuilder(" +
            "Ljava/lang/String;" +
            "Lorg/chromium/net/UrlRequest\$Callback;" +
            "Ljava/util/concurrent/Executor;" +
            ")Lorg/chromium/net/UrlRequest\$Builder;"
    }

private fun Method.indexOfBuildInstruction() =
    findInstructionIndex { instr ->
        instr.opcode == Opcode.INVOKE_VIRTUAL &&
        (instr as? ReferenceInstruction)?.reference?.toString() ==
            "Lorg/chromium/net/UrlRequest\$Builder;->build()Lorg/chromium/net/UrlRequest;"
    }

private fun Method.indexOfAddFooterViewInstruction() =
    findInstructionIndex { instr ->
        instr.opcode == Opcode.INVOKE_VIRTUAL &&
        (instr as? ReferenceInstruction)?.reference?.toString()
            ?.contains("Landroid/widget/ListView;->addFooterView") == true
    }

// ── Fingerprints ──────────────────────────────────────────────────────────────

/**
 * Fingerprint for the CronetEngine.newUrlRequestBuilder call site.
 */
@Suppress("DEPRECATION")
private val transcriptUrlFingerprint = fingerprint {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returns("L")
    custom { method, _ ->
        method.indexOfNewUrlRequestBuilderInstruction() >= 0 &&
        method.indexOfBuildInstruction() >= 0
    }
}

/**
 * Fingerprint for SubtitleMenuBottomSheetFragment.onCreateView (oju.N).
 *
 * Identification: the class contains the string constant
 * "SUBTITLE_MENU_BOTTOM_SHEET_FRAGMENT" (used in a conditional inside the
 * class), and this specific method calls ListView.addFooterView — both
 * characteristics are unique to the CC track-selection panel.
 *
 * Injection: right before addFooterView, pass p0 (oju instance) and the
 * ListView register to `injectHebrewOption(Object, ListView)`.
 * The helper stores a WeakRef to oju and uses it for reflection-based
 * track selection when the user taps "עברית".
 */
@Suppress("DEPRECATION")
private val subtitleMenuSheetFingerprint = fingerprint {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returns("Landroid/view/View;")
    parameters(
        "Landroid/view/LayoutInflater;",
        "Landroid/view/ViewGroup;",
        "Landroid/os/Bundle;",
    )
    custom { method, classDef ->
        // Class must contain the "SUBTITLE_MENU_BOTTOM_SHEET_FRAGMENT" string constant
        classDef.methods.any { m ->
            m.implementation?.instructions?.any { instr ->
                (instr.opcode == Opcode.CONST_STRING ||
                 instr.opcode == Opcode.CONST_STRING_JUMBO) &&
                (instr as? ReferenceInstruction)?.reference?.toString() ==
                    "SUBTITLE_MENU_BOTTOM_SHEET_FRAGMENT"
            } == true
        } &&
        // This exact method calls addFooterView (oju.N)
        method.indexOfAddFooterViewInstruction() >= 0
    }
}

// ── Patch ─────────────────────────────────────────────────────────────────────

@Suppress("unused", "DEPRECATION")
val hebrewSubtitlesPatch: Patch = bytecodePatch(
    "Hebrew auto-translated subtitles",
    "Injects &tlang=iw into YouTube's timedtext URLs and adds a CC-panel option to switch to Hebrew.",
) {
    compatibleWith("com.google.android.youtube" to (null as Set<String>?))

    extendWith("hebrew-helper.dex")

    execute {

        // ── Injection 0: URL interceptor ─────────────────────────────────────────
        val urlClassDef = transcriptUrlFingerprint.classDefOrNull
            ?: throw PatchException("Could not find CronetEngine.newUrlRequestBuilder call site")

        transcriptUrlFingerprint.match(urlClassDef).method.apply {
            val urlIndex = indexOfNewUrlRequestBuilderInstruction()
            val invoke = getInstruction<FiveRegisterInstruction>(urlIndex)
            val cronetReg   = invoke.registerC
            val urlReg      = invoke.registerD
            val callbackReg = invoke.registerE
            val executorReg = invoke.registerF

            val usedRegs = setOf(cronetReg, urlReg, callbackReg, executorReg)
            val tempReg = (0..15).first { it !in usedRegs }

            addInstructionsWithLabels(
                urlIndex,
                """
                invoke-static { v$cronetReg, v$urlReg, v$callbackReg, v$executorReg }, $HELPER->saveTimedtextRequest(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V
                invoke-static { }, Landroid/app/ActivityThread;->currentApplication()Landroid/app/Application;
                move-result-object v$tempReg
                invoke-static { v$tempReg }, $HELPER->isEnabled(Landroid/content/Context;)Z
                move-result v$tempReg
                if-eqz v$tempReg, :skip
                const-string v$tempReg, "timedtext"
                invoke-virtual { v$urlReg, v$tempReg }, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
                move-result v$tempReg
                if-eqz v$tempReg, :skip
                const-string v$tempReg, "tlang="
                invoke-virtual { v$urlReg, v$tempReg }, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
                move-result v$tempReg
                if-nez v$tempReg, :skip
                const-string v$tempReg, "&tlang=iw"
                invoke-virtual { v$urlReg, v$tempReg }, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v$urlReg
                const/4 v$tempReg, 0x0
                """,
                ExternalLabel("skip", getInstruction(urlIndex)),
            )
        }

        // ── Injection 1: CC panel Hebrew option ──────────────────────────────────
        //
        // Matches oju.N() by the "SUBTITLE_MENU_BOTTOM_SHEET_FRAGMENT" string and
        // addFooterView call.  We inject BEFORE YouTube's addFooterView so our
        // item appears immediately below the track list, above YouTube's footer.
        //
        // p0 = oju instance (this), v$listViewReg = the ListView.
        // The helper stores a WeakRef to oju for reflection-based track switching.
        val subtitleSheetClassDef = subtitleMenuSheetFingerprint.classDefOrNull
        if (subtitleSheetClassDef != null) {
            try {
                subtitleMenuSheetFingerprint.match(subtitleSheetClassDef).method.apply {
                    val footerIdx = indexOfAddFooterViewInstruction()
                    val listViewReg = getInstruction<FiveRegisterInstruction>(footerIdx).registerC
                    addInstruction(
                        footerIdx,
                        "invoke-static { p0, v$listViewReg }, $HELPER->injectHebrewOption(Ljava/lang/Object;Landroid/widget/ListView;)V",
                    )
                }
            } catch (_: Exception) {
                // Method match failed — Cronet URL hook still active as fallback.
            }
        }
    }
}

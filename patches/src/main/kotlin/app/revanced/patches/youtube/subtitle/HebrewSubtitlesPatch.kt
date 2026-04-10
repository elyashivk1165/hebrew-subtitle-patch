package app.revanced.patches.youtube.subtitle

import app.revanced.patcher.extensions.InstructionExtensions.addInstruction
import app.revanced.patcher.extensions.InstructionExtensions.getInstruction
import app.revanced.patcher.fingerprint
import app.revanced.patcher.patch.Patch
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
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
 * Used only to save request parameters for the Cronet fallback —
 * we no longer modify the URL inline (that caused all tracks to be
 * translated to Hebrew, not just the one the user selected).
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
        classDef.methods.any { m ->
            m.implementation?.instructions?.any { instr ->
                (instr.opcode == Opcode.CONST_STRING ||
                 instr.opcode == Opcode.CONST_STRING_JUMBO) &&
                (instr as? ReferenceInstruction)?.reference?.toString() ==
                    "SUBTITLE_MENU_BOTTOM_SHEET_FRAGMENT"
            } == true
        } &&
        method.indexOfAddFooterViewInstruction() >= 0
    }
}

// ── Patch ─────────────────────────────────────────────────────────────────────

@Suppress("unused", "DEPRECATION")
val hebrewSubtitlesPatch: Patch = bytecodePatch(
    "Hebrew auto-translated subtitles",
    "Adds a Hebrew option to the CC panel; switches to Hebrew via YouTube internal API.",
) {
    compatibleWith("com.google.android.youtube" to (null as Set<String>?))

    extendWith("hebrew-helper.dex")

    execute {

        // ── Injection 0: save Cronet request params ───────────────────────────
        //
        // We no longer modify the URL inline.  Doing so caused every timedtext
        // request (English, Auto-translate, etc.) to be fetched in Hebrew.
        // Instead we only save the engine/url/callback/executor so that
        // reloadSubtitlesCronet() can issue a one-shot Hebrew request as a
        // fallback when the reflection-based track selection fails.
        val urlClassDef = transcriptUrlFingerprint.classDefOrNull
            ?: throw PatchException("Could not find CronetEngine.newUrlRequestBuilder call site")

        transcriptUrlFingerprint.match(urlClassDef).method.apply {
            val urlIndex = indexOfNewUrlRequestBuilderInstruction()
            val invoke = getInstruction<FiveRegisterInstruction>(urlIndex)
            addInstruction(
                urlIndex,
                "invoke-static { v${invoke.registerC}, v${invoke.registerD}, v${invoke.registerE}, v${invoke.registerF} }, $HELPER->saveTimedtextRequest(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V",
            )
        }

        // ── Injection 1: CC panel Hebrew option ───────────────────────────────
        //
        // Injected BEFORE YouTube's addFooterView so our item appears above
        // YouTube's settings footer.  p0 = oju instance (for reflection-based
        // track selection), v$listViewReg = the ListView.
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
                // Method match failed — Cronet fallback still active.
            }
        }
    }
}

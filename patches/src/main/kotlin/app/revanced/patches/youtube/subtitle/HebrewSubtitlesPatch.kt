package app.revanced.patches.youtube.subtitle

import app.revanced.patcher.extensions.InstructionExtensions.addInstruction
import app.revanced.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
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

private fun Method.indexOfAddFooterViewInstruction() =
    findInstructionIndex { instr ->
        instr.opcode == Opcode.INVOKE_VIRTUAL &&
        (instr as? ReferenceInstruction)?.reference?.toString()
            ?.contains("Landroid/widget/ListView;->addFooterView") == true
    }

// ── Fingerprints ──────────────────────────────────────────────────────────────

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
    "Adds a Hebrew option to the CC panel using direct track selection with URL interception fallback.",
) {
    compatibleWith("com.google.android.youtube" to (null as Set<String>?))

    extendWith("hebrew-helper.dex")

    execute {

        // ── Injection 0: URL interceptor at EVERY Cronet call site ────────────
        //
        // Verified in the bytecode that YouTube builds requests through SEVERAL
        // HTTP clients — there are multiple CronetEngine.newUrlRequestBuilder
        // call sites. Hooking only one made Hebrew work only intermittently and
        // come back slowly after the app was backgrounded, because some subtitle
        // (timedtext) fetches went through the other clients and bypassed us.
        //
        // We now hook them ALL. interceptTimedtextUrl() ignores any URL that does
        // not contain "timedtext", so every other request passes through
        // untouched and only Hebrew subtitle fetches are affected.
        var urlHooks = 0
        classes.toList().forEach { classDef ->
            if (classDef.methods.none { it.indexOfNewUrlRequestBuilderInstruction() >= 0 }) return@forEach
            proxy(classDef).mutableClass.methods.forEach { method ->
                val urlIndex = method.indexOfNewUrlRequestBuilderInstruction()
                if (urlIndex < 0) return@forEach
                try {
                    val urlReg = method.getInstruction<FiveRegisterInstruction>(urlIndex).registerD
                    method.addInstructionsWithLabels(
                        urlIndex,
                        """
                        invoke-static { v$urlReg }, $HELPER->interceptTimedtextUrl(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v$urlReg
                        """,
                    )
                    urlHooks++
                } catch (_: Exception) {
                    // call site is not a simple 4-register invoke; skip it
                }
            }
        }
        if (urlHooks == 0)
            throw PatchException("Could not find any CronetEngine.newUrlRequestBuilder call site")

        // ── Injections inside the CC panel class ──────────────────────────────
        val subtitleSheetClassDef = subtitleMenuSheetFingerprint.classDefOrNull
        if (subtitleSheetClassDef != null) {

            // ── Injection 1: inject Hebrew footer item ────────────────────────
            //
            // Hooked in oju.N() (onCreateView), BEFORE YouTube's addFooterView.
            // isSelectable=false means our footer never gets a position in onItemClick,
            // so track item positions are not affected.
            // p0 = oju instance, v$listViewReg = ListView.
            try {
                subtitleMenuSheetFingerprint.match(subtitleSheetClassDef).method.apply {
                    val footerIdx   = indexOfAddFooterViewInstruction()
                    val listViewReg = getInstruction<FiveRegisterInstruction>(footerIdx).registerC
                    addInstruction(
                        footerIdx,
                        "invoke-static { p0, v$listViewReg }, $HELPER->injectHebrewOption(Ljava/lang/Object;Landroid/widget/ListView;)V",
                    )
                }
            } catch (_: Exception) {}

        }
    }
}

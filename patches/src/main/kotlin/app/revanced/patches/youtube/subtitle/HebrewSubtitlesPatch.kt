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

@Suppress("DEPRECATION")
private val transcriptUrlFingerprint = fingerprint {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returns("L")
    custom { method, _ ->
        method.indexOfNewUrlRequestBuilderInstruction() >= 0 &&
        method.indexOfBuildInstruction() >= 0
    }
}

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
    "Adds a Hebrew option to the CC panel; switches via flag-based URL interception.",
) {
    compatibleWith("com.google.android.youtube" to (null as Set<String>?))

    extendWith("hebrew-helper.dex")

    execute {

        // ── Injection 0: URL interceptor (flag-based) ─────────────────────────
        //
        // Saves request params AND conditionally appends &tlang=iw.
        // interceptTimedtextUrl() only modifies the URL when the Hebrew flag is
        // set — so English/other tracks are never affected.
        val urlClassDef = transcriptUrlFingerprint.classDefOrNull
            ?: throw PatchException("Could not find CronetEngine.newUrlRequestBuilder call site")

        transcriptUrlFingerprint.match(urlClassDef).method.apply {
            val urlIndex = indexOfNewUrlRequestBuilderInstruction()
            val invoke   = getInstruction<FiveRegisterInstruction>(urlIndex)
            val cronetReg   = invoke.registerC
            val urlReg      = invoke.registerD
            val callbackReg = invoke.registerE
            val executorReg = invoke.registerF

            addInstructionsWithLabels(
                urlIndex,
                """
                invoke-static { v$cronetReg, v$urlReg, v$callbackReg, v$executorReg }, $HELPER->saveTimedtextRequest(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V
                invoke-static { v$urlReg }, $HELPER->interceptTimedtextUrl(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v$urlReg
                """,
            )
        }

        // ── Injections inside the CC panel class ──────────────────────────────
        val subtitleSheetClassDef = subtitleMenuSheetFingerprint.classDefOrNull
        if (subtitleSheetClassDef != null) {

            // ── Injection 1: inject Hebrew footer item ────────────────────────
            //
            // Hooked in oju.N() (onCreateView), BEFORE YouTube's addFooterView.
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

            // ── Injection 2: disable Hebrew when user picks another CC track ──
            //
            // oju.onItemClick is called when the user selects any adapter item
            // (i.e., any NORMAL track such as English or Auto-translate).
            // Our "עברית" footer uses a direct OnClickListener so it is NOT
            // routed through onItemClick, which means this hook fires only when
            // the user explicitly switches AWAY from Hebrew.
            val onItemClickMethod = subtitleSheetClassDef.methods.firstOrNull { m ->
                m.parameterTypes.size == 4 &&
                m.parameterTypes[0].contains("AdapterView")
            }
            if (onItemClickMethod != null) {
                try {
                    onItemClickMethod.apply {
                        addInstruction(0, "invoke-static { }, $HELPER->onCcItemSelected()V")
                    }
                } catch (_: Exception) {}
            }
        }
    }
}

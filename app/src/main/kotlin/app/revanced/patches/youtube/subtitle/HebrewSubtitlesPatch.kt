package app.revanced.patches.youtube.subtitle

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
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

private const val NATIVE_CAPTION_FOOTER =
    "Landroid/widget/ListView;->addFooterView(Landroid/view/View;Ljava/lang/Object;Z)V"
private const val MORPHE_CAPTION_FOOTER =
    "Lapp/morphe/extension/youtube/patches/HidePlayerFlyoutMenuPatch;->" +
        "hideCaptionsOldBottomSheetFooter(Landroid/widget/ListView;Landroid/view/View;Ljava/lang/Object;Z)V"

private fun Method.indexOfCaptionFooterInstruction() =
    findInstructionIndex { instruction ->
        val target = (instruction as? ReferenceInstruction)?.reference?.toString()
        // Both calls pass the ListView as their first register. The official
        // flyout patch replaces the virtual call with this static wrapper.
        (instruction.opcode == Opcode.INVOKE_VIRTUAL && target == NATIVE_CAPTION_FOOTER) ||
            (instruction.opcode == Opcode.INVOKE_STATIC && target == MORPHE_CAPTION_FOOTER)
    }

// ── Patch ─────────────────────────────────────────────────────────────────────

@Suppress("unused", "DEPRECATION")
val hebrewSubtitlesPatch = bytecodePatch(
    "Hebrew auto-translated subtitles",
    "Adds a Hebrew option to the CC panel using direct track selection with URL interception fallback.",
) {
    compatibleWith("com.google.android.youtube" to setOf("21.07.247", "21.13.164"))

    extendWith("hebrew-helper.dex")

    execute {
        // The runtime native-row adapter is deliberately scoped to this model.
        val model = when (packageMetadata.versionName) {
            "21.07.247" -> Triple("Losm;", "Lanyg;", "o")
            "21.13.164" -> Triple("Loxg;", "Laolf;", "p")
            else -> throw PatchException("Unsupported YouTube version: ${packageMetadata.versionName}")
        }
        val row = classDefBy(model.first)
        val track = classDefBy(model.second)
        if (row.fields.none { it.name == "a" && it.type == model.second } ||
            track.fields.none { it.name == "a" && it.type == "Ljava/lang/String;" } ||
            track.fields.none { it.name == model.third && it.type == "Ljava/lang/CharSequence;" }) {
            throw PatchException("Unexpected subtitle model for YouTube ${packageMetadata.versionName}")
        }


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
        classDefForEach { classDef ->
            if (classDef.methods.none { it.indexOfNewUrlRequestBuilderInstruction() >= 0 }) return@classDefForEach
            mutableClassDefBy(classDef).methods.forEach { method ->
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

        // Both caption bottom sheets exist in this APK. Patch each matching
        // implementation instead of using the first fingerprint match only.
        var menuHooks = 0
        classDefForEach { classDef ->
            val isCaptionMenu = classDef.methods.any { method ->
                method.implementation?.instructions?.any { instruction ->
                    (instruction.opcode == Opcode.CONST_STRING ||
                     instruction.opcode == Opcode.CONST_STRING_JUMBO) &&
                    ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                        "SUBTITLE_MENU_BOTTOM_SHEET_FRAGMENT"
                } == true
            }
            if (!isCaptionMenu) return@classDefForEach
            mutableClassDefBy(classDef).methods.forEach { method ->
                val footerIdx = method.indexOfCaptionFooterInstruction()
                if (footerIdx < 0) return@forEach
                val listViewReg = method.getInstruction<FiveRegisterInstruction>(footerIdx).registerC
                method.addInstruction(footerIdx,
                    "invoke-static { p0, v$listViewReg }, $HELPER->injectHebrewOption(Ljava/lang/Object;Landroid/widget/ListView;)V")
                menuHooks++
            }
        }
        if (menuHooks != 2) throw PatchException(
            "Expected 2 caption menus, found $menuHooks (checked native and Morphe flyout footer calls)"
        )
        println("Hebrew subtitles: installed $urlHooks URL hooks and $menuHooks menu hooks")
    }
}

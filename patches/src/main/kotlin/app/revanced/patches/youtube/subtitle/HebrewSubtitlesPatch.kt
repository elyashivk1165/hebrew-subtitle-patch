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
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
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

private fun Method.indexOfViewStubInflate() =
    findInstructionIndex { instr ->
        instr.opcode == Opcode.INVOKE_VIRTUAL &&
        (instr as? ReferenceInstruction)?.reference?.toString() ==
            "Landroid/view/ViewStub;->inflate()Landroid/view/View;"
    }

private fun Method.indexOfAddFooterViewInstruction() =
    findInstructionIndex { instr ->
        instr.opcode == Opcode.INVOKE_VIRTUAL &&
        (instr as? ReferenceInstruction)?.reference?.toString()
            ?.contains("Landroid/widget/ListView;->addFooterView") == true
    }

private fun Method.indexOfSetTranslationY() =
    findInstructionIndex { instr ->
        instr.opcode == Opcode.INVOKE_VIRTUAL &&
        (instr as? ReferenceInstruction)?.reference?.toString()
            ?.contains("setTranslationY") == true
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
 * Fingerprint for the player controls overlay animated-visibility method.
 *
 * Identifying characteristics:
 * - PRIVATE + FINAL
 * - Returns void
 * - Exactly two boolean parameters (visible: Z, animated: Z)
 * - Non-trivial body (> 10 instructions)
 * - Its class also contains a no-param method that calls ViewStub.inflate()
 *   (the bottom-controls inflate method lives in the same class)
 *
 * This single fingerprint is the anchor for ALL button injections:
 * the inflate method is found by searching within the matched class,
 * and the motion-event method is also searched within the same class.
 */
@Suppress("DEPRECATION")
private val controlsOverlayVisibilityFingerprint = fingerprint {
    accessFlags(AccessFlags.PRIVATE, AccessFlags.FINAL)
    returns("V")
    parameters("Z", "Z")
    custom { method, classDef ->
        (method.implementation?.instructions?.count() ?: 0) > 10 &&
        classDef.methods.any { m ->
            m.parameterTypes.isEmpty() &&
            m.indexOfViewStubInflate() >= 0
        }
    }
}

/**
 * Fingerprint for the MotionEvent handler that calls setTranslationY.
 * Searched within the same class as controlsOverlayVisibilityFingerprint.
 */
@Suppress("DEPRECATION")
private val motionEventFingerprint = fingerprint {
    returns("V")
    parameters("Landroid/view/MotionEvent;")
    custom { method, _ ->
        method.indexOfSetTranslationY() >= 0
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
 * Injection: right after addFooterView, pass the ListView register to
 * `injectHebrewOption(ListView)`.  Using addHeaderView(view, null, false)
 * inside the helper keeps the item non-selectable via the adapter's
 * onItemClick (which casts adapter items to oix), so no ClassCastException.
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
    "Injects &tlang=iw into YouTube's timedtext URLs and adds a player button to toggle it.",
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

        // ── Button injections — all anchored to controlsOverlayVisibilityFingerprint ──
        //
        // This single fingerprint identifies the player-controls overlay class.
        // From that class we extract:
        //   1. The no-param / returns-Object inflate method  → initializeButton
        //   2. The private-final void(Z,Z) visibility method → setVisibility
        //   3. The void(MotionEvent) motion handler           → setVisibilityNegatedImmediate
        //
        val visibilityClassDef = controlsOverlayVisibilityFingerprint.classDefOrNull
        if (visibilityClassDef != null) {

            // ── Injection 1: initializeButton ─────────────────────────────────────
            //
            // Find the inflate method within this class (no params, calls ViewStub.inflate()).
            // It is the bottom-controls container inflate method.
            val inflateMethod = visibilityClassDef.methods.firstOrNull { m ->
                m.parameterTypes.isEmpty() &&
                m.indexOfViewStubInflate() >= 0
            }
            if (inflateMethod != null) {
                inflateMethod.apply {
                    val inflateIdx = indexOfViewStubInflate()
                    // inflateIdx+0 : invoke-virtual ..ViewStub..inflate()
                    // inflateIdx+1 : move-result-object vN  ← vN = inflated View
                    // inflateIdx+2 : ← our injection point (before whatever uses vN)
                    val inflatedReg =
                        getInstruction<OneRegisterInstruction>(inflateIdx + 1).registerA
                    addInstruction(
                        inflateIdx + 2,
                        "invoke-static { v$inflatedReg }, $HELPER->initializeButton(Landroid/view/View;)V",
                    )
                }
            }

            // ── Injection 2: setVisibility(visible, animated) ─────────────────────
            //
            // Injected at index 0 of the private final void(Z,Z) method.
            // p1 = visible (Z), p2 = animated (Z).
            controlsOverlayVisibilityFingerprint.match(visibilityClassDef).method.apply {
                addInstruction(
                    0,
                    "invoke-static { p1, p2 }, $HELPER->setVisibility(ZZ)V",
                )
            }

            // ── Injection 4: setVisibilityNegatedImmediate ────────────────────────
            //
            // The MotionEvent handler calls setTranslationY when hiding controls on touch.
            // We insert our call immediately after that setTranslationY call.
            try {
                motionEventFingerprint.match(visibilityClassDef).method.apply {
                    val translationIdx = indexOfSetTranslationY()
                    if (translationIdx >= 0) {
                        addInstruction(
                            translationIdx + 1,
                            "invoke-static { }, $HELPER->setVisibilityNegatedImmediate()V",
                        )
                    }
                }
            } catch (_: Exception) {
                // MotionEvent method not found in this class — injection 4 is skipped.
            }
        }

        // ── Injection 5: CC panel Hebrew option ──────────────────────────────────
        //
        // Identified by RE: the real CC panel is oju.java
        // (SubtitleMenuBottomSheetFragment), matched by the unique string constant
        // "SUBTITLE_MENU_BOTTOM_SHEET_FRAGMENT".  We hook oju.N() right after
        // addFooterView and pass the ListView to injectHebrewOption(), which uses
        // addHeaderView(view, null, false) so the item is not selectable through
        // the adapter's onItemClick (preventing the ClassCastException to oix).
        //
        // Fallback: the ViewTreeObserver probe registered in initializeButton
        // catches the panel via GlobalLayout events if this fingerprint misses.
        val subtitleSheetClassDef = subtitleMenuSheetFingerprint.classDefOrNull
        if (subtitleSheetClassDef != null) {
            try {
                subtitleMenuSheetFingerprint.match(subtitleSheetClassDef).method.apply {
                    val footerIdx = indexOfAddFooterViewInstruction()
                    val listViewReg = getInstruction<FiveRegisterInstruction>(footerIdx).registerC
                    // Inject BEFORE YouTube's addFooterView so our item appears
                    // immediately below the track list, above YouTube's settings footer.
                    addInstruction(
                        footerIdx,
                        "invoke-static { v$listViewReg }, $HELPER->injectHebrewOption(Landroid/widget/ListView;)V",
                    )
                }
            } catch (_: Exception) {
                // Method match failed — ViewTreeObserver fallback still active.
            }
        }
    }
}

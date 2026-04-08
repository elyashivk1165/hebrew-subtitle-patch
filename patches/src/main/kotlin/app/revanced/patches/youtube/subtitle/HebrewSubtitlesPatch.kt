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

/**
 * Returns the index of the first INVOKE_VIRTUAL that calls ViewStub.inflate().
 * This is used in two fingerprints: bottom-controls inflate and top-controls inflate.
 */
private fun Method.indexOfViewStubInflate() =
    findInstructionIndex { instr ->
        instr.opcode == Opcode.INVOKE_VIRTUAL &&
            (instr as? ReferenceInstruction)?.reference?.toString() ==
            "Landroid/view/ViewStub;->inflate()Landroid/view/View;"
    }

/**
 * Returns the index of the first INVOKE_VIRTUAL that calls setTranslationY on any object.
 * Used to locate the insertion point for setVisibilityNegatedImmediate.
 */
private fun Method.indexOfSetTranslationY() =
    findInstructionIndex { instr ->
        instr.opcode == Opcode.INVOKE_VIRTUAL &&
            (instr as? ReferenceInstruction)?.reference?.toString()
                ?.contains("setTranslationY") == true
    }

// ── Fingerprints ──────────────────────────────────────────────────────────────

/**
 * Fingerprint for the CronetEngine.newUrlRequestBuilder call site.
 * This is the method where we intercept the timedtext URL and append &tlang=iw.
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
 * Fingerprint for the method that inflates youtube_controls_bottom_ui_container via ViewStub.
 *
 * Distinguishing characteristics (verified against RVX source):
 *   - Returns Ljava/lang/Object;  (the inflated View is returned as Object, not void)
 *   - No parameters
 *   - Contains a call to ViewStub.inflate()
 *
 * The top-controls inflate method also calls ViewStub.inflate() but returns void — so
 * the return-type constraint uniquely identifies the bottom-controls one.
 */
@Suppress("DEPRECATION")
private val playerBottomControlsInflateFingerprint = fingerprint {
    returns("Ljava/lang/Object;")
    custom { method, _ ->
        method.parameterTypes.isEmpty() &&
            method.indexOfViewStubInflate() >= 0
    }
}

/**
 * Fingerprint for the player controls overlay animated-visibility method.
 *
 * Distinguishing characteristics (from RVX controlsOverlayVisibilityFingerprint):
 *   - PRIVATE FINAL
 *   - Returns void
 *   - Exactly two boolean parameters (visible: Z, animated: Z)
 *   - Non-trivial body (> 10 instructions) — rules out tiny stubs
 *   - Its class also contains a no-arg method that calls ViewStub.inflate()
 *     (i.e. the top-controls inflate method is in the same class)
 *
 * The last constraint is the key one: the player-controls overlay class manages
 * both top-control inflation and visibility transitions, so requiring that the
 * class also contains a ViewStub.inflate() call uniquely pins us to that class.
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
 * This is the method where player controls hide on a touch event.
 * We search for it within the same class as controlsOverlayVisibilityFingerprint
 * (the player controls overlay class) to avoid false positives.
 */
@Suppress("DEPRECATION")
private val motionEventFingerprint = fingerprint {
    returns("V")
    parameters("Landroid/view/MotionEvent;")
    custom { method, _ ->
        method.indexOfSetTranslationY() >= 0
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
        //
        // Before CronetEngine.newUrlRequestBuilder(url, …) is called we:
        //   1. Ask HebrewSubtitlesHelper.isEnabled(context) — reads SharedPreferences.
        //   2. Check the URL contains "timedtext" (subtitle request).
        //   3. Check the URL does NOT already contain "tlang=" (user picked a language).
        //   4. If all pass: append "&tlang=iw" to force Hebrew auto-translation.
        //
        // Register strategy:
        //   - urlRegister  : the register that holds the URL string (invoke arg D).
        //   - tempReg      : a free register not clobbered by the invoke (chosen from 0–15).
        //     All SharedPreferences work reuses tempReg sequentially — no register expansion
        //     needed because isEnabled(Context) takes only 1 arg.
        //
        val urlClassDef = transcriptUrlFingerprint.classDefOrNull
            ?: throw PatchException("Could not find CronetEngine.newUrlRequestBuilder call site")

        transcriptUrlFingerprint.match(urlClassDef).method.apply {
            val urlIndex = indexOfNewUrlRequestBuilderInstruction()
            val invoke   = getInstruction<FiveRegisterInstruction>(urlIndex)
            val urlReg   = invoke.registerD

            // Pick any register not used by the 5-register invoke (C, D, E, F, G).
            val usedRegs = setOf(invoke.registerC, invoke.registerD,
                                 invoke.registerE, invoke.registerF)
            val tempReg  = (0..15).first { it !in usedRegs }

            addInstructionsWithLabels(
                urlIndex,
                """
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

        // ── Injection 1: initializeButton ────────────────────────────────────────
        //
        // After ViewStub.inflate() returns the bottom-controls container (an Object),
        // we pass that View to HebrewSubtitlesHelper.initializeButton().
        // The Java code walks the hierarchy to find a FrameLayout parent, creates a
        // TextView toggle, and attaches it with Gravity.BOTTOM|END.
        //
        val bottomClassDef = playerBottomControlsInflateFingerprint.classDefOrNull
        if (bottomClassDef != null) {
            playerBottomControlsInflateFingerprint.match(bottomClassDef).method.apply {
                val inflateIdx = indexOfViewStubInflate()
                if (inflateIdx >= 0) {
                    // inflateIdx     : invoke-virtual vX, Landroid/view/ViewStub;->inflate()
                    // inflateIdx + 1 : move-result-object vN   ← vN = inflated View
                    // inflateIdx + 2 : ← our injection point
                    val inflatedReg =
                        getInstruction<OneRegisterInstruction>(inflateIdx + 1).registerA
                    addInstruction(
                        inflateIdx + 2,
                        "invoke-static { v$inflatedReg }, $HELPER->initializeButton(Landroid/view/View;)V",
                    )
                }
            }
        }

        // ── Injections 2 & 4: visibility callbacks ───────────────────────────────
        //
        // We resolve both within the player-controls overlay class
        // (the class that controlsOverlayVisibilityFingerprint matched).
        // Searching within the same class avoids false positives.
        //
        val visibilityClassDef = controlsOverlayVisibilityFingerprint.classDefOrNull
        if (visibilityClassDef != null) {

            // Injection 2: setVisibility(visible, animated)
            // Inserted at index 0 of the private final void(Z,Z) method.
            // p1 = visible (boolean), p2 = animated (boolean).
            controlsOverlayVisibilityFingerprint.match(visibilityClassDef).method.apply {
                addInstruction(
                    0,
                    "invoke-static { p1, p2 }, $HELPER->setVisibility(ZZ)V",
                )
            }

            // Injection 4: setVisibilityNegatedImmediate()
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
                // Button will remain visible after controls hide, but won't crash.
            }
        }
    }
}

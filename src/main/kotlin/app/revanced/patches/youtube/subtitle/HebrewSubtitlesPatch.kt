package app.revanced.patches.youtube.subtitle

import app.revanced.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.InstructionExtensions.getInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.util.smali.ExternalLabel
import app.revanced.util.fingerprint.legacyFingerprint
import app.revanced.util.fingerprint.matchOrThrow
import app.revanced.util.getReference
import app.revanced.util.indexOfFirstInstruction
import app.revanced.util.or
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private val transcriptUrlFingerprint = legacyFingerprint(
    name = "transcriptUrlFingerprint",
    returnType = "L",
    accessFlags = AccessFlags.PUBLIC or AccessFlags.FINAL,
    customFingerprint = { method, _ ->
        indexOfNewUrlRequestBuilderInstruction(method) >= 0 &&
        indexOfBuildInstruction(method) >= 0 &&
        indexOfUploadDataProvidersInstruction(method) >= 0
    }
)

private fun indexOfNewUrlRequestBuilderInstruction(method: Method) =
    method.indexOfFirstInstruction {
        opcode == Opcode.INVOKE_VIRTUAL &&
        getReference<MethodReference>().toString() ==
            "Lorg/chromium/net/CronetEngine;->newUrlRequestBuilder(Ljava/lang/String;Lorg/chromium/net/UrlRequest\$Callback;Ljava/util/concurrent/Executor;)Lorg/chromium/net/UrlRequest\$Builder;"
    }

private fun indexOfBuildInstruction(method: Method) =
    method.indexOfFirstInstruction {
        opcode == Opcode.INVOKE_VIRTUAL &&
        getReference<MethodReference>().toString() ==
            "Lorg/chromium/net/UrlRequest\$Builder;->build()Lorg/chromium/net/UrlRequest;"
    }

private fun indexOfUploadDataProvidersInstruction(method: Method) =
    method.indexOfFirstInstruction {
        opcode == Opcode.INVOKE_STATIC &&
        getReference<MethodReference>().toString() ==
            "Lorg/chromium/net/UploadDataProviders;->create(Ljava/nio/ByteBuffer;)Lorg/chromium/net/UploadDataProvider;"
    }

@Suppress("unused")
val hebrewSubtitlesPatch = bytecodePatch(
    "Hebrew auto-translated subtitles",
    "Automatically adds Hebrew as the translation language for video subtitles.",
) {
    compatibleWith("com.google.android.youtube")

    execute {
        transcriptUrlFingerprint.matchOrThrow().method.apply {
            val urlIndex = indexOfNewUrlRequestBuilderInstruction(this)
            val urlRegister = getInstruction<FiveRegisterInstruction>(urlIndex).registerD

            addInstructionsWithLabels(
                urlIndex,
                """
                    const-string v0, "timedtext"
                    invoke-virtual { v$urlRegister, v0 }, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
                    move-result v0
                    if-eqz v0, :skip
                    const-string v0, "tlang="
                    invoke-virtual { v$urlRegister, v0 }, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
                    move-result v0
                    if-nez v0, :skip
                    const-string v0, "&tlang=iw"
                    invoke-virtual { v$urlRegister, v0 }, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$urlRegister
                """,
                ExternalLabel("skip", getInstruction(urlIndex))
            )
        }
    }
}

package com.aos.chatbot.parsers

/**
 * Thrown when a document cannot be parsed due to format-level problems
 * (corruption, encryption, unsupported format variant, etc.).
 *
 * [reason] carries a machine-readable discriminator; [fileType] is the
 * extension that was attempted (e.g. "docx", "pdf").
 */
class UnreadableDocumentException(
    val reason: UnreadableReason,
    val fileType: String,
    override val cause: Throwable? = null
) : RuntimeException("Unreadable $fileType document: ${reason.code}", cause)

enum class UnreadableReason(val code: String) {
    CORRUPTED("corrupted"),
    PASSWORD_PROTECTED("password_protected"),
    UNSUPPORTED_VERSION("unsupported_version"),
    OLE2_INSTEAD_OF_OOXML("ole2_instead_of_ooxml"),
    ENCRYPTED("encrypted"),
    INVALID_FORMAT("invalid_format")
}

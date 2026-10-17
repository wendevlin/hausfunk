package dev.wendelin.hausfunk.data

/**
 * Device names are labels only, never used for trust. They are capped on input and on
 * receipt from the network, and treated as plain text everywhere.
 */
object DeviceName {
    const val MAX_LENGTH = 32

    /** Removes control characters and caps the length without splitting a surrogate pair. */
    fun sanitize(input: String): String {
        val cleaned = input.filterNot { Character.isISOControl(it) }
        if (cleaned.length <= MAX_LENGTH) return cleaned
        val end = if (Character.isHighSurrogate(cleaned[MAX_LENGTH - 1])) MAX_LENGTH - 1 else MAX_LENGTH
        return cleaned.substring(0, end)
    }

    /**
     * [name] as an mDNS service name: at most 63 UTF-8 bytes (a DNS label), cut at a whole
     * character; "Hausfunk" if nothing is left.
     */
    fun serviceName(name: String): String {
        val clean = sanitize(name).trim()
        val out = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < clean.length) {
            val cp = clean.codePointAt(i)
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > MAX_SERVICE_NAME_BYTES) break
            out.appendCodePoint(cp)
            bytes += size
            i += Character.charCount(cp)
        }
        return out.toString().ifBlank { "Hausfunk" }
    }

    private const val MAX_SERVICE_NAME_BYTES = 63

    /** A name is usable once it has visible content after trimming. */
    fun isValid(name: String): Boolean = sanitize(name).isNotBlank()
}

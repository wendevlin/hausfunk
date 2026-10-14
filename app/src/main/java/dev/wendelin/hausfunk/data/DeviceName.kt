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

    /** A name is usable once it has visible content after trimming. */
    fun isValid(name: String): Boolean = sanitize(name).isNotBlank()
}

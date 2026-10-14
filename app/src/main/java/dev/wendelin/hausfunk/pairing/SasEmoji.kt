package dev.wendelin.hausfunk.pairing

/** One entry of the SAS emoji table: the emoji with its English and German name. */
data class SasEmoji(val emoji: String, val english: String, val german: String)

/**
 * The 64 emojis of the Matrix SAS verification (index = table position), with the English
 * description and the German translation. Copied verbatim from `sas-emoji.json` in the npm package
 * `@matrix-org/spec` 1.16.0 (Apache License 2.0). Never reorder: the index is what both phones
 * derive.
 */
object SasEmojis {
    val all: List<SasEmoji> = listOf(
        SasEmoji("🐶", "Dog", "Hund"),
        SasEmoji("🐱", "Cat", "Katze"),
        SasEmoji("🦁", "Lion", "Löwe"),
        SasEmoji("🐎", "Horse", "Pferd"),
        SasEmoji("🦄", "Unicorn", "Einhorn"),
        SasEmoji("🐷", "Pig", "Schwein"),
        SasEmoji("🐘", "Elephant", "Elefant"),
        SasEmoji("🐰", "Rabbit", "Hase"),
        SasEmoji("🐼", "Panda", "Panda"),
        SasEmoji("🐓", "Rooster", "Hahn"),
        SasEmoji("🐧", "Penguin", "Pinguin"),
        SasEmoji("🐢", "Turtle", "Schildkröte"),
        SasEmoji("🐟", "Fish", "Fisch"),
        SasEmoji("🐙", "Octopus", "Oktopus"),
        SasEmoji("🦋", "Butterfly", "Schmetterling"),
        SasEmoji("🌷", "Flower", "Blume"),
        SasEmoji("🌳", "Tree", "Baum"),
        SasEmoji("🌵", "Cactus", "Kaktus"),
        SasEmoji("🍄", "Mushroom", "Pilz"),
        SasEmoji("🌏", "Globe", "Globus"),
        SasEmoji("🌙", "Moon", "Mond"),
        SasEmoji("☁️", "Cloud", "Wolke"),
        SasEmoji("🔥", "Fire", "Feuer"),
        SasEmoji("🍌", "Banana", "Banane"),
        SasEmoji("🍎", "Apple", "Apfel"),
        SasEmoji("🍓", "Strawberry", "Erdbeere"),
        SasEmoji("🌽", "Corn", "Mais"),
        SasEmoji("🍕", "Pizza", "Pizza"),
        SasEmoji("🎂", "Cake", "Kuchen"),
        SasEmoji("❤️", "Heart", "Herz"),
        SasEmoji("😀", "Smiley", "Lächeln"),
        SasEmoji("🤖", "Robot", "Roboter"),
        SasEmoji("🎩", "Hat", "Hut"),
        SasEmoji("👓", "Glasses", "Brille"),
        SasEmoji("🔧", "Spanner", "Schraubenschlüssel"),
        SasEmoji("🎅", "Santa", "Weihnachtsmann"),
        SasEmoji("👍", "Thumbs Up", "Daumen Hoch"),
        SasEmoji("☂️", "Umbrella", "Regenschirm"),
        SasEmoji("⌛", "Hourglass", "Sanduhr"),
        SasEmoji("⏰", "Clock", "Uhr"),
        SasEmoji("🎁", "Gift", "Geschenk"),
        SasEmoji("💡", "Light Bulb", "Glühbirne"),
        SasEmoji("📕", "Book", "Buch"),
        SasEmoji("✏️", "Pencil", "Bleistift"),
        SasEmoji("📎", "Paperclip", "Büroklammer"),
        SasEmoji("✂️", "Scissors", "Schere"),
        SasEmoji("🔒", "Lock", "Schloss"),
        SasEmoji("🔑", "Key", "Schlüssel"),
        SasEmoji("🔨", "Hammer", "Hammer"),
        SasEmoji("☎️", "Telephone", "Telefon"),
        SasEmoji("🏁", "Flag", "Flagge"),
        SasEmoji("🚂", "Train", "Zug"),
        SasEmoji("🚲", "Bicycle", "Fahrrad"),
        SasEmoji("✈️", "Aeroplane", "Flugzeug"),
        SasEmoji("🚀", "Rocket", "Rakete"),
        SasEmoji("🏆", "Trophy", "Pokal"),
        SasEmoji("⚽", "Ball", "Ball"),
        SasEmoji("🎸", "Guitar", "Gitarre"),
        SasEmoji("🎺", "Trumpet", "Trompete"),
        SasEmoji("🔔", "Bell", "Glocke"),
        SasEmoji("⚓", "Anchor", "Anker"),
        SasEmoji("🎧", "Headphones", "Kopfhörer"),
        SasEmoji("📁", "Folder", "Ordner"),
        SasEmoji("📌", "Pin", "Stecknadel"),
    )
}

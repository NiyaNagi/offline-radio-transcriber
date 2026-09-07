package org.ort.lexicon

/**
 * The closed ~36-unit phonetic vocabulary Pass C spots and Pass D parses (functional spec
 * §7.4, technical design §9.1): the 26 letters, the 10 digits, and the `/` separator that
 * joins reciprocal and modifier forms.
 *
 * Digits are named `N0`..`N9` because a bare `0` is not a legal Kotlin identifier; [symbol]
 * carries the character each unit stands for. This is deliberately *not* a phoneme inventory —
 * it is the alphabet a callsign is spelled in.
 */
public enum class PhoneticUnit(public val symbol: Char) {
    A('A'),
    B('B'),
    C('C'),
    D('D'),
    E('E'),
    F('F'),
    G('G'),
    H('H'),
    I('I'),
    J('J'),
    K('K'),
    L('L'),
    M('M'),
    N('N'),
    O('O'),
    P('P'),
    Q('Q'),
    R('R'),
    S('S'),
    T('T'),
    U('U'),
    V('V'),
    W('W'),
    X('X'),
    Y('Y'),
    Z('Z'),
    N0('0'),
    N1('1'),
    N2('2'),
    N3('3'),
    N4('4'),
    N5('5'),
    N6('6'),
    N7('7'),
    N8('8'),
    N9('9'),
    STROKE('/'),
    ;

    public val isLetter: Boolean get() = symbol in 'A'..'Z'
    public val isDigit: Boolean get() = symbol in '0'..'9'
    public val isSeparator: Boolean get() = this == STROKE

    public companion object {
        /** The 26 letters, in order. */
        public val LETTERS: List<PhoneticUnit> = entries.filter { it.isLetter }

        /** The 10 digits, in order. */
        public val DIGITS: List<PhoneticUnit> = entries.filter { it.isDigit }

        /** The unit for a callsign character (`'A'`..`'Z'`, `'0'`..`'9'`, `'/'`), or null. */
        public fun fromSymbol(c: Char): PhoneticUnit? {
            val up = c.uppercaseChar()
            return entries.firstOrNull { it.symbol == up }
        }

        /** Spell a callsign string into units, or throw if it contains an unrepresentable character. */
        public fun spell(text: String): List<PhoneticUnit> = text.map {
            fromSymbol(it) ?: throw IllegalArgumentException("'$it' in \"$text\" is not a phonetic unit")
        }
    }
}

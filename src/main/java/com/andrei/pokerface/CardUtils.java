package com.andrei.pokerface;

/* Helper functions for manipulations with deck cards.
 * Contains helpers to compute card ranks & suits, and to display cards
 * as human-readable strings.
 */
public final class CardUtils {
    private CardUtils() {}

    /* Card Methods */

    public static int getRank(int card) {
        // return the rank of this card
        if (card < 0 || card > 51) {
            throw new IllegalArgumentException("Card does not exist.");
        }

        return (card / 4) + 1;
    }

    public static int getSuit(int card) {
        // return the suit of this card
        if (card < 0 || card > 51) {
            throw new IllegalArgumentException("Card does not exist.");
        }

        return (card % 4) + 1;
    }

    public static int pokerValue (int card) {
        // Return the poker value of this card.
        // Poker value == card rank when the card is not an ace. 
        // Ace has poker value 14, to make it win in a high card situation.
        int rank = getRank(card);
        return (rank == 1) ? 14 : rank;
    }

    /**
     * Converts a card ID (0-51) to a string like "Ac", "Kh", "2d", etc.
     */
    public static String cardToString(int card) {
        if (card < 0 || card > 51) {
            throw new IllegalArgumentException("Card does not exist.");
        }
        int rank = getRank(card), suit = getSuit(card);
        char rankChar;
        switch (rank) {
            case 1: rankChar = 'A'; break;
            case 10: rankChar = 'T'; break;
            case 11: rankChar = 'J'; break;
            case 12: rankChar = 'Q'; break;
            case 13: rankChar = 'K'; break;
            default: rankChar = (char)('0' + rank); break;
        }
        char suitChar;
        switch (suit) {
            case 1: suitChar = 'c'; break;
            case 2: suitChar = 'd'; break;
            case 3: suitChar = 'h'; break;
            case 4: suitChar = 's'; break;
            default: suitChar = '?'; break;
        }
        return "" + rankChar + suitChar;
    }

    /**
     * Converts an array of card IDs to a comma‑separated string.
     * Example: "Ac, Kh, Qd, Js, Tc"
     */
    public static String handToString(int[] cards) {
        if (cards == null || cards.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cards.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(cardToString(cards[i]));
        }
        return sb.toString();
    }

    /**
     * Converts only the first {@code count} cards of an array to a string.
     */
    public static String handToString(int[] cards, int count) {
        if (cards == null || cards.length == 0 || count <= 0) {
            return "";
        }
        int limit = Math.min(count, cards.length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(", ");
            sb.append(cardToString(cards[i]));
        }
        return sb.toString();
    }
}
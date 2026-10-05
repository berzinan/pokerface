package com.andrei.pokerface;
import java.util.Random;

/* A deck of cards used throughout the game.
 * Integer array storing 52 cards as integers in [0, 51]
 * Given an integer c:
 *      - rank = c (mod 13)
 *      - suit = c (mod 4)
 * Supports shuffling and dealing
 */
public class Deck {
    private final int[] cards;
    private int topIndex = 51;

    public Deck() {
        // initialize cards variable and populate with ints [0, 51]
        cards = new int[52];
        for (int i = 0; i < 52; i++) {
            cards[i] = i;
        }
    }

    /* Deck Methods */

    public void shuffle(int seed) {
        // rebuild deck in canonical order
        for (int i = 0; i < 52; i++) {
            cards[i] = i;
        }
        topIndex = 51;

        // shuffle the cards using the Fisher-Yates shuffle
        Random numgen = new Random(seed);
        for (int i = cards.length - 1; i > 0; i--) {
            int j = numgen.nextInt(i+1);
            int temp = cards[i];
            cards[i] = cards[j];
            cards[j] = temp;
        }
    }

    public int deal() {
        // Return the card at topIndex, and decrement topIndex by 1.
        if (topIndex < 0) {
            throw new IllegalStateException("Cannot deal from an empty deck");
        }
        int out = cards[topIndex];
        topIndex -= 1;
        return out;
    }

    /**
     * Discards the top card without returning it. Equivalent to
     * calling deal() and ignoring the result, but named separately so
     *  a burned card is never even exposed to a caller.
     */
    public void burn() {
        if (topIndex < 0) {
            throw new IllegalStateException("Cannot burn from an empty deck");
        }
        topIndex -= 1;
    }

    public int remainingCards() {
        // return the number of remaining cards. 
        return topIndex + 1;
    }
    
}
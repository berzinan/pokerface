package com.andrei.pokerface;

import java.util.Arrays;

/**
 * Scores a poker hand of 5 or more cards (7 in normal play: 2 hole + 5 board).
 *
 * Everything derives from a single pass over the cards, captured in Counts:
 * how many cards of each rank, which ranks are present as a bitmask, and the
 * same mask per suit. Straight detection is then a shift-and-compare over the
 * mask rather than a search, flushes are a popcount, and every made-hand check
 * is a descending scan of a 15-element array.
 *
 * The previous implementation rebuilt a HashMap<Integer,Integer> of rank
 * counts inside each of the nine evaluateX methods, and evaluateBestHand calls
 * up to all nine -- so one scored hand built as many as seven rank maps and
 * two suit maps, each boxing every key and value, all computing exactly the
 * same thing from exactly the same input. Measured at roughly 1.1-5 us per
 * evaluation, which made it the dominant cost of a MonteCarlo rollout and
 * therefore of the whole benchmark pipeline.
 *
 * The nine public evaluateX(int[]) methods are kept with unchanged signatures
 * and unchanged return formats. They are now thin wrappers that count once and
 * delegate; evaluateBestHand and describeBestHand count once for the whole
 * dispatch chain instead of once per step.
 *
 * Returned rank arrays are ordered most-significant first, ready for
 * encodeScore, and an empty array is the "this hand type is not present"
 * sentinel. Fewer than five cards is handled by returning a shorter array
 * rather than throwing: encodeScore pads with zeros, which scores identically
 * to the old zero-padded behaviour.
 */
public class HandEvaluator {

    /* -------------------------------------------------------------- */
    /* Single-pass derivation                                          */
    /* -------------------------------------------------------------- */

    /**
     * Everything the evaluators need, computed once.
     *
     * rankCounts is indexed by poker value (2..14), so index 0, 1 and any
     * unused slot stay zero. suitRankMask is indexed by suit (1..4); a suit
     * cannot hold two cards of the same rank, so the bit count of its mask is
     * also its card count.
     */
    private static final class Counts {
        final int[] rankCounts = new int[15];
        final int[] suitRankMask = new int[5];
        int rankMask;    // bit r set when poker value r is present in any suit
        int flushSuit;   // 1..4, or 0 when no suit has five or more cards

        Counts(int[] cards) {
            for (int card : cards) {
                int value = CardUtils.pokerValue(card);
                int suit = CardUtils.getSuit(card);
                rankCounts[value]++;
                rankMask |= 1 << value;
                suitRankMask[suit] |= 1 << value;
            }
            for (int suit = 1; suit <= 4; suit++) {
                if (Integer.bitCount(suitRankMask[suit]) >= 5) {
                    flushSuit = suit;
                    break; // seven cards cannot produce two five-card suits
                }
            }
        }
    }

    /** Highest poker value appearing at least minCount times, or 0 if none does. */
    private static int highestWithCount(int[] rankCounts, int minCount) {
        for (int rank = 14; rank >= 2; rank--) {
            if (rankCounts[rank] >= minCount) {
                return rank;
            }
        }
        return 0;
    }

    /**
     * The n highest card values, descending, counting duplicates, skipping the
     * two excluded ranks. Pass -1 to exclude nothing. Returns a shorter array
     * when fewer than n cards qualify.
     *
     * Duplicates count because that is what the hand rules require: the
     * kickers beside a pair in AAKKQ are K, K, Q, not K, Q and then a third
     * distinct rank.
     */
    private static int[] topValues(int[] rankCounts, int n, int exclude1, int exclude2) {
        int[] out = new int[n];
        int found = 0;
        for (int rank = 14; rank >= 2 && found < n; rank--) {
            if (rank == exclude1 || rank == exclude2) {
                continue;
            }
            for (int i = 0; i < rankCounts[rank] && found < n; i++) {
                out[found++] = rank;
            }
        }
        return found == n ? out : Arrays.copyOf(out, found);
    }

    /** The n highest ranks present in a rank bitmask, descending. No duplicates possible. */
    private static int[] topFromMask(int mask, int n) {
        int[] out = new int[n];
        int found = 0;
        for (int rank = 14; rank >= 2 && found < n; rank--) {
            if ((mask & (1 << rank)) != 0) {
                out[found++] = rank;
            }
        }
        return found == n ? out : Arrays.copyOf(out, found);
    }

    /**
     * Highest card of the best straight in a rank bitmask, or 0 if there is
     * none.
     *
     * The ace-low wheel is handled by mirroring the ace into a virtual bit at
     * position 1, so A-2-3-4-5 becomes the ordinary window 1..5 and needs no
     * special case. Scanning from 14 downwards returns the best straight
     * first, which also means a 6-high straight is found before the wheel it
     * contains.
     */
    private static int straightHigh(int rankMask) {
        int mask = rankMask;
        if ((mask & (1 << 14)) != 0) {
            mask |= 1 << 1; // ace plays low
        }
        for (int high = 14; high >= 5; high--) {
            int window = 0b11111 << (high - 4);
            if ((mask & window) == window) {
                return high;
            }
        }
        return 0;
    }

    /* -------------------------------------------------------------- */
    /* Hand-type checks (operate on precomputed Counts)                */
    /* -------------------------------------------------------------- */

    private static int[] highCardOf(Counts c) {
        return topValues(c.rankCounts, 5, -1, -1);
    }

    private static int[] pairOf(Counts c) {
        int pair = highestWithCount(c.rankCounts, 2);
        if (pair == 0) {
            return new int[0];
        }
        int[] kickers = topValues(c.rankCounts, 3, pair, -1);
        int[] result = new int[1 + kickers.length];
        result[0] = pair;
        System.arraycopy(kickers, 0, result, 1, kickers.length);
        return result;
    }

    private static int[] twoPairOf(Counts c) {
        int highPair = highestWithCount(c.rankCounts, 2);
        if (highPair == 0) {
            return new int[0];
        }
        int lowPair = 0;
        for (int rank = highPair - 1; rank >= 2; rank--) {
            if (c.rankCounts[rank] >= 2) {
                lowPair = rank;
                break;
            }
        }
        if (lowPair == 0) {
            return new int[0];
        }
        // Highest card outside both pairs. Zero when none exists (quads plus
        // trips, say) -- the old code threw NoSuchElementException there.
        int kicker = 0;
        for (int rank = 14; rank >= 2; rank--) {
            if (rank != highPair && rank != lowPair && c.rankCounts[rank] > 0) {
                kicker = rank;
                break;
            }
        }
        return new int[]{highPair, lowPair, kicker};
    }

    private static int[] tripleOf(Counts c) {
        int trip = highestWithCount(c.rankCounts, 3);
        if (trip == 0) {
            return new int[0];
        }
        int[] kickers = topValues(c.rankCounts, 2, trip, -1);
        int[] result = new int[1 + kickers.length];
        result[0] = trip;
        System.arraycopy(kickers, 0, result, 1, kickers.length);
        return result;
    }

    private static int[] straightOf(Counts c) {
        int high = straightHigh(c.rankMask);
        return high == 0 ? new int[0] : new int[]{high};
    }

    private static int[] flushOf(Counts c) {
        if (c.flushSuit == 0) {
            return new int[0];
        }
        return topFromMask(c.suitRankMask[c.flushSuit], 5);
    }

    private static int[] fullHouseOf(Counts c) {
        int trip = highestWithCount(c.rankCounts, 3);
        if (trip == 0) {
            return new int[0];
        }
        int pair = 0;
        for (int rank = 14; rank >= 2; rank--) {
            if (rank != trip && c.rankCounts[rank] >= 2) {
                pair = rank;
                break;
            }
        }
        return pair == 0 ? new int[0] : new int[]{trip, pair};
    }

    private static int[] quadOf(Counts c) {
        int quad = highestWithCount(c.rankCounts, 4);
        if (quad == 0) {
            return new int[0];
        }
        int kicker = 0;
        for (int rank = 14; rank >= 2; rank--) {
            if (rank != quad && c.rankCounts[rank] > 0) {
                kicker = rank;
                break;
            }
        }
        return new int[]{quad, kicker};
    }

    private static int[] straightFlushOf(Counts c) {
        if (c.flushSuit == 0) {
            return new int[0];
        }
        int high = straightHigh(c.suitRankMask[c.flushSuit]);
        return high == 0 ? new int[0] : new int[]{high};
    }

    /* -------------------------------------------------------------- */
    /* Public per-hand-type API (unchanged signatures and formats)     */
    /* -------------------------------------------------------------- */

    /** The five highest card values, descending. */
    public static int[] evaluateHighCard(int[] cards) {
        return highCardOf(new Counts(cards));
    }

    /** {pairRank, k1, k2, k3} descending, or empty if no pair. */
    public static int[] evaluatePair(int[] cards) {
        return pairOf(new Counts(cards));
    }

    /** {highPair, lowPair, kicker}, or empty if fewer than two pairs. */
    public static int[] evaluateTwoPair(int[] cards) {
        return twoPairOf(new Counts(cards));
    }

    /** {tripRank, k1, k2} descending, or empty if no three of a kind. */
    public static int[] evaluateTriple(int[] cards) {
        return tripleOf(new Counts(cards));
    }

    /** {highCard}, 5 for the ace-low wheel, or empty if no straight. */
    public static int[] evaluateStraight(int[] cards) {
        return straightOf(new Counts(cards));
    }

    /** The five highest values of the flush suit, descending, or empty if no flush. */
    public static int[] evaluateFlush(int[] cards) {
        return flushOf(new Counts(cards));
    }

    /** {tripRank, pairRank}, or empty if no full house. */
    public static int[] evaluateFullHouse(int[] cards) {
        return fullHouseOf(new Counts(cards));
    }

    /** {quadRank, kicker}, or empty if no four of a kind. */
    public static int[] evaluateQuad(int[] cards) {
        return quadOf(new Counts(cards));
    }

    /** {highCard} of the best straight flush, or empty if none. */
    public static int[] evaluateStraightFlush(int[] cards) {
        return straightFlushOf(new Counts(cards));
    }

    /* -------------------------------------------------------------- */
    /* Scoring                                                          */
    /* -------------------------------------------------------------- */

    /**
     * Packs a hand type and up to five tiebreak ranks into one comparable int:
     * type in bits 20+, then five 4-bit nibbles descending in significance.
     * Missing ranks pad with zero, which is why a short ranks array scores the
     * same as a zero-padded one.
     */
    static int encodeScore(int handType, int[] ranks) {
        int score = handType << 20;
        for (int i = 0; i < 5; i++) {
            int rank = (i < ranks.length) ? ranks[i] : 0;
            score |= (rank << (4 * (4 - i)));
        }
        return score;
    }

    /**
     * The hand's score: higher always beats lower, across every hand type.
     * Counts the cards once and walks the hand types in descending value,
     * returning at the first match.
     */
    public static int evaluateBestHand(int[] cards) {
        Counts c = new Counts(cards);

        int[] sf = straightFlushOf(c);
        if (sf.length > 0) return encodeScore(8, sf);

        int[] quad = quadOf(c);
        if (quad.length > 0) return encodeScore(7, quad);

        int[] fh = fullHouseOf(c);
        if (fh.length > 0) return encodeScore(6, fh);

        int[] flush = flushOf(c);
        if (flush.length > 0) return encodeScore(5, flush);

        int[] straight = straightOf(c);
        if (straight.length > 0) return encodeScore(4, straight);

        int[] trip = tripleOf(c);
        if (trip.length > 0) return encodeScore(3, trip);

        int[] twoPair = twoPairOf(c);
        if (twoPair.length > 0) return encodeScore(2, twoPair);

        int[] pair = pairOf(c);
        if (pair.length > 0) return encodeScore(1, pair);

        return encodeScore(0, highCardOf(c));
    }

    /**
     * Human-readable description of the best hand, e.g. "Full House, Aces full
     * of Kings". Same dispatch order as evaluateBestHand, over the same single
     * count.
     */
    public static String describeBestHand(int[] cards) {
        Counts c = new Counts(cards);

        int[] sf = straightFlushOf(c);
        if (sf.length > 0) return "Straight Flush, " + rankName(sf[0]) + " High";

        int[] quad = quadOf(c);
        if (quad.length > 0) return "Four of a Kind, " + rankNamePlural(quad[0]);

        int[] fh = fullHouseOf(c);
        if (fh.length > 0) return "Full House, " + rankNamePlural(fh[0]) + " full of " + rankNamePlural(fh[1]);

        int[] flush = flushOf(c);
        if (flush.length > 0) return "Flush, " + rankName(flush[0]) + " High";

        int[] straight = straightOf(c);
        if (straight.length > 0) return "Straight, " + rankName(straight[0]) + " High";

        int[] trip = tripleOf(c);
        if (trip.length > 0) return "Three of a Kind, " + rankNamePlural(trip[0]);

        int[] twoPair = twoPairOf(c);
        if (twoPair.length > 0) return "Two Pair, " + rankNamePlural(twoPair[0]) + " and " + rankNamePlural(twoPair[1]);

        int[] pair = pairOf(c);
        if (pair.length > 0) return "Pair of " + rankNamePlural(pair[0]);

        int[] high = highCardOf(c);
        return "High Card, " + (high.length > 0 ? rankName(high[0]) : "none");
    }

    private static String rankName(int pokerValue) {
        return switch (pokerValue) {
            case 14 -> "Ace";
            case 13 -> "King";
            case 12 -> "Queen";
            case 11 -> "Jack";
            case 10 -> "Ten";
            case 9 -> "Nine";
            case 8 -> "Eight";
            case 7 -> "Seven";
            case 6 -> "Six";
            case 5 -> "Five";
            case 4 -> "Four";
            case 3 -> "Three";
            case 2 -> "Two";
            default -> String.valueOf(pokerValue);
        };
    }

    private static String rankNamePlural(int pokerValue) {
        return switch (pokerValue) {
            case 14 -> "Aces";
            case 13 -> "Kings";
            case 12 -> "Queens";
            case 11 -> "Jacks";
            case 10 -> "Tens";
            case 9 -> "Nines";
            case 8 -> "Eights";
            case 7 -> "Sevens";
            case 6 -> "Sixes";
            case 5 -> "Fives";
            case 4 -> "Fours";
            case 3 -> "Threes";
            case 2 -> "Twos";
            default -> pokerValue + "s";
        };
    }
}
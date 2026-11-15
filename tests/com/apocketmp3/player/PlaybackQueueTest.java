package com.apocketmp3.player;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public final class PlaybackQueueTest {
    private static final class CountingRandom extends Random {
        int draws;
        CountingRandom() { super(42); }
        @Override public int nextInt(int bound) { draws++; return super.nextInt(bound); }
    }
    private static final class SequenceRandom extends Random {
        private final int[] sequence;
        private int cursor;
        SequenceRandom(int... sequence) { this.sequence = sequence; }
        @Override public int nextInt(int bound) {
            int value = sequence[cursor++];
            if (value < 0 || value >= bound) throw new AssertionError("Random choice outside bound");
            return value;
        }
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        Track a = new Track("a", "One", "Artist", 1000);
        Track b = new Track("b", "Two", "Artist", 2000);
        Track c = new Track("c", "Three", "Artist", 3000);
        PlaybackQueue q = new PlaybackQueue(new Random(42));
        check(q.current() == null && !q.next(true) && !q.previous(), "Empty queue");
        q.set(Arrays.asList(a, b, c), 0);
        check(q.next(true) && q.current() == b, "Advance after completion");
        check(q.next(true) && q.current() == c, "Advance to last song");
        check(!q.next(true) && q.current() == c, "Stop at end with repeat off");
        q.repeat = 1;
        check(q.next(true) && q.current() == a, "Repeat all wraps");
        q.repeat = 2;
        check(q.next(true) && q.current() == a, "Repeat one replays");
        check(q.next(false) && q.current() == b, "Manual next bypasses repeat one");
        q.shuffle = true;
        q.repeat = 0;
        for (int i = 0; i < 100; i++) {
            Track before = q.current();
            check(q.next(true) && q.current() != before, "Shuffle avoids immediate repeats");
        }
        q.set(Collections.singletonList(a), 0);
        check(!q.next(true), "Single shuffled song stops without repeat");
        q.repeat = 1;
        check(q.next(true) && q.current() == a, "Single song repeats");
        q.shuffle = false;
        q.set(Arrays.asList(a, b, c), 0);
        check(q.previous() && q.current() == c, "Previous wraps");
        check(q.next(false) && q.current() == a, "Next returns from sequential Previous");
        check(q.previous() && q.current() == c, "Sequential history remains navigable");
        try {
            q.set(Collections.emptyList(), 0);
            throw new AssertionError("Reject empty queue");
        } catch (IllegalArgumentException expected) { }
        check(q.current() == c, "Invalid selection preserves queue");

        PlaybackQueue saved = new PlaybackQueue();
        check(saved.currentIndex() == -1 && saved.snapshot().isEmpty(), "Empty queue has no saved selection");
        saved.set(Arrays.asList(c, a, b), 0);
        check(saved.next(false) && saved.current() == a, "Select a song before saving the queue");
        List<Track> snapshot = saved.snapshot();
        int savedIndex = saved.currentIndex();
        saved.set(Collections.singletonList(b), 0);
        check(snapshot.equals(Arrays.asList(c, a, b)) && snapshot.get(savedIndex) == a,
                "A saved queue retains its order and selection after the original queue changes");

        PlaybackQueue restored = new PlaybackQueue();
        restored.set(snapshot, savedIndex);
        snapshot.clear();
        check(restored.current() == a && restored.currentIndex() == 1,
                "A fresh queue restores the selected track independently of the snapshot list");
        check(restored.next(false) && restored.current() == b && restored.currentIndex() == 2,
                "Next follows saved queue order after restoration");
        check(restored.previous() && restored.current() == a && restored.currentIndex() == 1,
                "Previous returns to the restored track after Next");
        check(restored.previous() && restored.current() == c,
                "Sequential Previous can reach the song before the restored selection");
        check(restored.next(false) && restored.current() == a, "Restored queue stays navigable");
        List<Track> intact = restored.snapshot();
        for (int invalid : new int[]{-1, intact.size()}) {
            try {
                restored.set(intact, invalid);
                throw new AssertionError("Reject an invalid restored selection");
            } catch (IllegalArgumentException expected) { }
            check(restored.current() == a && restored.currentIndex() == 1 && restored.snapshot().equals(intact),
                    "Invalid restoration preserves the current selection and queue order");
        }
        check(restored.previous() && restored.current() == c,
                "Invalid restoration preserves the listening history");

        for (boolean automatic : new boolean[]{false, true}) {
            for (int selected = 0; selected < intact.size(); selected++) {
                for (int draw = 0; draw < intact.size() - 1; draw++) {
                    PlaybackQueue restoredShuffle = new PlaybackQueue(new SequenceRandom(draw));
                    restoredShuffle.shuffle = true;
                    restoredShuffle.set(intact, selected);
                    Track lastPlayed = restoredShuffle.current();
                    check(!restoredShuffle.previous() && restoredShuffle.current() == lastPlayed,
                            "Restoration does not invent shuffled listening history");
                    check(restoredShuffle.next(automatic) && restoredShuffle.current() != lastPlayed,
                            "Every first shuffled choice excludes the restored current song");
                    check(restoredShuffle.previous() && restoredShuffle.current() == lastPlayed,
                            "Shuffled Previous returns to the restored song after the first skip");
                }
            }
        }

        for (boolean automatic : new boolean[]{false, true}) {
            PlaybackQueue example = new PlaybackQueue(new SequenceRandom(0, 1));
            example.shuffle = true;
            example.set(Arrays.asList(a, b, c), 0);
            check(example.next(false) && example.current() == b, "Example A -> B");
            check(example.previous() && example.current() == a, "Example Previous returns to A");
            check(example.next(automatic) && example.current() == c, "Example shuffles freshly from A to C");
            check(example.previous() && example.current() == a, "C's previous song is the replayed A");
            check(example.previous() && example.current() == b, "B is retained in A -> B -> A -> C history");
            check(example.previous() && example.current() == a, "Original A is retained");
            check(!example.previous(), "Stop rewinding at history start");
        }

        CountingRandom random = new CountingRandom();
        PlaybackQueue shuffled = new PlaybackQueue(random);
        shuffled.shuffle = true;
        shuffled.set(Arrays.asList(a, b, c), 0);
        check(!shuffled.previous() && shuffled.current() == a, "No invented song before shuffled history");
        ArrayList<Track> heard = new ArrayList<>(); heard.add(a);
        for (int i = 0; i < 100; i++) {
            Track before = shuffled.current();
            check(shuffled.next(i % 2 == 0), "Fresh shuffle advances for manual and automatic next");
            check(shuffled.current() != before, "Fresh shuffle avoids immediate repeats");
            heard.add(shuffled.current());
        }
        int draws = random.draws;
        for (int i = heard.size() - 2; i >= 0; i--) {
            check(shuffled.previous() && shuffled.current() == heard.get(i), "Previous retraces actual listening order");
        }
        check(!shuffled.previous() && shuffled.current() == a, "History start keeps the current song");
        check(random.draws == draws, "Previous never draws a random song");
        check(shuffled.next(false) && random.draws == draws + 1 && shuffled.current() != a,
                "Next shuffles freshly after rewinding, without replaying a stored future");

        Track latest = shuffled.current();
        check(shuffled.previous(), "Back before repeat-one test");
        Track previous = shuffled.current();
        shuffled.repeat = 2;
        check(shuffled.next(true) && shuffled.current() == previous, "Repeat one stays on the history cursor");
        draws = random.draws;
        check(shuffled.next(false) && random.draws == draws + 1 && shuffled.current() != previous,
                "Manual next makes a fresh shuffle under repeat one");
        latest = shuffled.current();
        shuffled.repeat = 0;
        check(shuffled.previous(), "Back before shuffle toggle");
        shuffled.shuffle = false;
        check(shuffled.next(false) && shuffled.current() == latest, "Toggling shuffle preserves already-heard future");
        shuffled.shuffle = true;
        shuffled.set(Arrays.asList(a, b, c), 1);
        check(shuffled.current() == b && !shuffled.previous(), "New selection clears old listening history");
        shuffled.next(false);
        try {
            shuffled.set(Arrays.asList(a, b, c), 3);
            throw new AssertionError("Reject out-of-range selection");
        } catch (IllegalArgumentException expected) { }
        check(shuffled.previous() && shuffled.current() == b, "Invalid selection preserves listening history");
        draws = random.draws;
        check(shuffled.next(false) && random.draws == draws + 1 && shuffled.current() != b,
                "Invalid selection preserves fresh shuffle after Previous");

        ArrayList<Track> source = new ArrayList<>(Arrays.asList(a, b, c));
        shuffled.set(source, 0); source.clear();
        check(shuffled.next(false) && shuffled.current() != a, "UI filtering cannot mutate the service queue");
        for (int i = 0; i < 600; i++) shuffled.next(false);
        int backwards = 0;
        while (shuffled.previous()) backwards++;
        check(backwards == 511, "Listening history remains bounded for long sessions");
        draws = random.draws;
        for (int i = 0; i < backwards; i++) {
            Track before = shuffled.current();
            check(shuffled.next(false) && shuffled.current() != before, "Shuffle remains fresh after reaching history limit");
        }
        check(random.draws == draws + backwards, "Every shuffled Next makes a new draw after going back");
        check(new Track("x", "", "<unknown>", -1).duration == 0, "Metadata fallback");
        System.out.println("PlaybackQueue: all checks passed");
    }
}

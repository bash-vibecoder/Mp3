package com.pocketmp3.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Queue decisions are independent of Android and MediaPlayer state. */
public final class PlaybackQueue {
    private static final int HISTORY_LIMIT = 512;
    private final ArrayList<Track> tracks = new ArrayList<>();
    private final ArrayList<Integer> history = new ArrayList<>();
    private final ArrayList<Integer> revisited = new ArrayList<>();
    private final Random random;
    private int index = -1;
    private int historyCursor = -1;
    public boolean shuffle;
    /** 0 = off, 1 = repeat all, 2 = repeat one. */
    public int repeat;

    public PlaybackQueue() { this(new Random()); }
    PlaybackQueue(Random random) { this.random = random; }

    public void set(List<Track> source, int selected) {
        if (source.isEmpty() || selected < 0 || selected >= source.size())
            throw new IllegalArgumentException("Select a track in a nonempty queue");
        tracks.clear();
        tracks.addAll(source);
        index = selected;
        history.clear();
        revisited.clear();
        history.add(index);
        historyCursor = 0;
    }

    public Track current() { return index < 0 ? null : tracks.get(index); }
    public int currentIndex() { return index; }
    public List<Track> snapshot() { return new ArrayList<>(tracks); }

    public boolean next(boolean completed) {
        if (tracks.isEmpty()) return false;
        if (completed && repeat == 2) return true;
        if (shuffle && tracks.size() > 1) {
            // Commit the songs visited while going back: A, B, Previous(A), Next(C)
            // leaves a listening history of A, B, A, C rather than losing B.
            for (int visited : revisited) remember(visited);
            revisited.clear();
            index = (index + 1 + random.nextInt(tracks.size() - 1)) % tracks.size();
            remember(index);
            return true;
        }
        revisited.clear();
        if (historyCursor + 1 < history.size()) {
            index = history.get(++historyCursor);
            return true;
        }
        if (index + 1 < tracks.size()) { index++; remember(index); return true; }
        if (!completed || repeat == 1) { index = 0; remember(index); return true; }
        return false;
    }

    public boolean previous() {
        if (tracks.isEmpty()) return false;
        if (historyCursor > 0) {
            index = history.get(--historyCursor);
            if (shuffle) {
                revisited.add(index);
                if (revisited.size() > HISTORY_LIMIT) revisited.remove(0);
            } else revisited.clear();
            return true;
        }
        // At the start of shuffled history, there is no previous song to invent.
        if (shuffle) return false;
        index = (index + tracks.size() - 1) % tracks.size();
        history.add(0, index);
        if (history.size() > HISTORY_LIMIT) history.remove(history.size() - 1);
        return true;
    }

    private void remember(int visited) {
        history.add(visited);
        if (history.size() > HISTORY_LIMIT) history.remove(0);
        historyCursor = history.size() - 1;
    }
}

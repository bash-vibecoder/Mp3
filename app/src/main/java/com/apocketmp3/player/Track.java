package com.apocketmp3.player;

public final class Track {
    public final String uri, title, artist;
    public final long duration;

    public Track(String uri, String title, String artist, long duration) {
        this.uri = uri;
        this.title = title == null || title.trim().isEmpty() ? "Untitled MP3" : title;
        this.artist = artist == null || artist.trim().isEmpty() || "<unknown>".equals(artist)
                ? "Unknown artist" : artist;
        this.duration = Math.max(0, duration);
    }
}

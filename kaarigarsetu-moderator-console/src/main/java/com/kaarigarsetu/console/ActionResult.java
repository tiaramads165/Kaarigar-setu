package com.kaarigarsetu.console;

/** What happened to a vetting or a ruling: done at once (single-moderator mode) or queued for a second moderator ({@code actionId} names the proposal). */
public record ActionResult(boolean pending, long actionId) {
}

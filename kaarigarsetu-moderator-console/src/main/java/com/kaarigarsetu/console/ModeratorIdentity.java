package com.kaarigarsetu.console;

import java.util.List;

/** Who is signed in, and which actions need a second moderator. */
public record ModeratorIdentity(String username, String displayName, List<String> twoPersonActions) {
}

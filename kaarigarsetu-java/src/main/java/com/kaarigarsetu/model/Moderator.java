package com.kaarigarsetu.model;

import java.time.LocalDateTime;

/** A moderator: a named person with their own login. The password hash and the encrypted phone never leave the server. */
public record Moderator(long moderatorId, String username, String displayName, String passwordHash, byte[] phoneEncrypted, boolean active,
                        LocalDateTime createdAt, String createdBy) {
}

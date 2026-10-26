package com.kaarigarsetu.model;

import java.time.LocalDateTime;

public record Employer(
        Long employerId,
        String name,
        String phone,
        boolean verified,
        LocalDateTime createdAt
) {
}

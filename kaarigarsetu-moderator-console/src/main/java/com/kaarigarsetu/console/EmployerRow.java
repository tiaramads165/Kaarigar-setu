package com.kaarigarsetu.console;

/** An employer as listed for vetting: no phone number, only what the moderator needs to decide. */
public record EmployerRow(long employerId, String name, boolean vetted, String vettedBy, int approvedConfirmations, String registeredAt) {
}

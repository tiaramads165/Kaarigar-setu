package com.kaarigarsetu.security;

/** The caller identity stored in the HTTP session after a successful login. */
public record AuthenticatedUser(Role role, long id) {}

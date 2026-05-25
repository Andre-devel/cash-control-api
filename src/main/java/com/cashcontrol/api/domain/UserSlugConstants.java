package com.cashcontrol.api.domain;

public final class UserSlugConstants {

    private UserSlugConstants() {}

    // account_statuses slugs
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";
    public static final String STATUS_LOCKED = "LOCKED";
    public static final String STATUS_PENDING_VERIFICATION = "PENDING_VERIFICATION";

    // auth_origins slugs
    public static final String ORIGIN_LOCAL = "LOCAL";
    public static final String ORIGIN_GOOGLE = "GOOGLE";
    public static final String ORIGIN_MIXED = "MIXED";

    // lockout_types slugs
    public static final String LOCKOUT_AUTOMATIC = "AUTOMATIC";
    public static final String LOCKOUT_MANUAL = "MANUAL";
}
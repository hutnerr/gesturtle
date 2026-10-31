package com.gesturtle.model;

import com.gesturtle.filter.Filter;

import java.util.List;

/** A named, saved Gallery query (a "view"); also usable as a practice segment's source. */
public record SavedView(int id, String name, Filter filter, long createdAt) {
    /**
     * Views every library has (favorites, untagged). Negative
     * ids, so they can't collide with saved views and can still be stored as the
     * Gallery's default. They can be loaded and edited into a new view, but not
     * renamed or deleted. Ids -1 and -2 belonged to built-ins since removed; don't reuse
     * them, or a Gallery default stored under them would point at a different view.
     */
    public static final List<SavedView> BUILT_INS = List.of(
            new SavedView(-3, "Favorites", new Filter.FavoritePredicate(), 0),
            new SavedView(-4, "Untagged", new Filter.UntaggedPredicate(), 0));

    /** Library setting: "off" hides the built-in views from the Gallery and Practice. */
    public static final String BUILT_INS_SETTING = "builtin_views";

    public boolean isBuiltIn() {
        return id < 0;
    }

    /** Whether a BUILT_INS_SETTING value means the built-ins are shown (the default). */
    public static boolean builtInsShown(String settingValue) {
        return !"off".equals(settingValue);
    }
}

package com.gesturtle.model;

/**
 * What importing a file that's already in the library does to the existing
 * item. The bytes are identical (same hash), so only metadata changes.
 * UPDATE adds the new tags; OVERWRITE replaces its tags with the new ones and
 * takes the new file's name and source path; SKIP leaves it untouched.
 */
public enum DuplicateAction {
    UPDATE, OVERWRITE, SKIP
}

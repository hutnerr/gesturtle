package com.gesturtle.ui.common;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortcutsTest {

    @AfterEach
    void restoreDefaults() {
        Shortcuts.reset();
    }

    private static KeyEvent press(KeyCode code, boolean ctrl) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, ctrl, false, false);
    }

    @Test
    void defaultsMatchByScope() {
        assertEquals(Shortcuts.Action.GRAYSCALE, Shortcuts.match(press(KeyCode.G, false), Shortcuts.Scope.VIEWER));
        assertEquals(Shortcuts.Action.PAUSE, Shortcuts.match(press(KeyCode.SPACE, false), Shortcuts.Scope.PRACTICE));
        // Pause is practice-only, so Space means nothing in the Gallery viewer.
        assertNull(Shortcuts.match(press(KeyCode.SPACE, false), Shortcuts.Scope.VIEWER));
        assertEquals(Shortcuts.Action.ZOOM_IN, Shortcuts.match(press(KeyCode.EQUALS, false), Shortcuts.Scope.VIEWER));
    }

    @Test
    void modifiersLeaveKeysToTheApp() {
        assertNull(Shortcuts.match(press(KeyCode.V, true), Shortcuts.Scope.PRACTICE));
    }

    @Test
    void rebindingMovesAKeyAndSavesOnlyChanges() {
        Shortcuts.Action displaced = Shortcuts.rebind(Shortcuts.Action.GRAYSCALE, KeyCode.B);
        assertEquals(Shortcuts.Action.BLUR, displaced);
        assertEquals(Shortcuts.Action.GRAYSCALE, Shortcuts.match(press(KeyCode.B, false), Shortcuts.Scope.VIEWER));
        assertNull(Shortcuts.match(press(KeyCode.G, false), Shortcuts.Scope.VIEWER));
        assertEquals("—", Shortcuts.describe(Shortcuts.Action.BLUR));
        assertEquals(Map.of("GRAYSCALE", "B", "BLUR", ""), Shortcuts.overrides());

        Shortcuts.reset();
        Shortcuts.load(Map.of("GRAYSCALE", "B", "BLUR", "", "NOT_AN_ACTION", "Q", "FIT", "NOT_A_KEY"));
        assertEquals(Shortcuts.Action.GRAYSCALE, Shortcuts.match(press(KeyCode.B, false), Shortcuts.Scope.VIEWER));
        assertEquals("0", Shortcuts.describe(Shortcuts.Action.FIT));
    }

    @Test
    void fixedKeysCannotBeBound() {
        assertFalse(Shortcuts.bindable(KeyCode.ESCAPE));
        assertFalse(Shortcuts.bindable(KeyCode.F11));
        assertFalse(Shortcuts.bindable(KeyCode.SHIFT));
        assertTrue(Shortcuts.bindable(KeyCode.Q));
    }
}

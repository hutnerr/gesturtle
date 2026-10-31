package com.gesturtle.ui.settings;

import com.gesturtle.ui.components.Card;
import com.gesturtle.ui.components.ColumnFlow;
import com.gesturtle.ui.components.Page;
import javafx.application.HostServices;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What isn't obvious from the screens themselves, in a few main sections with
 * short subsections; anything a button, tooltip or Settings row already says
 * is left out. The version and credits are on AboutView, and the shortcuts are
 * only listed in Settings, next to the buttons that change them.
 */
public class HelpView extends Page {
    /**
     * JavaFX's launcher rather than AWT Desktop.browse(): on Linux, AWT's GTK
     * init clashes with JavaFX's ("XSetErrorHandler() called with a GDK error
     * trap pushed") and the browser silently never opens.
     */
    private final HostServices hostServices;

    public HelpView(HostServices hostServices) {
        super("Help");
        this.hostServices = hostServices;
        getStyleClass().add("help-view");
        setSubtitle("Learn from the Gesturtle's wisdom.");

        // Each section gets as many columns as it has rows of equal-length topics, so nothing hangs alone under a row.
        setScrollingBody(false,
                section("Getting started", 3,
                        topic("1. Import", "Pick a folder or files on the Import page. Gesturtle copies them into "
                                + "your library, so the originals can stay where they are."),
                        topic("2. Tag", "Select images in the Gallery and tag them by subject, pose, or anything else "
                                + "you'll want to practice from later."),
                        topic("3. Practice", "Choose images by tag on the Practice page, set a timer, and start. Save "
                                + "a setup as a preset to start it from Home.")),
                section("Good to know", 2,
                        topic("Drop or paste anywhere", "Drag files, folders, or an image from your browser onto "
                                + "any page, or press Ctrl+V, to send them to Import. Images from a link are credited to it."),
                        topic("Study without a timer", "Open an image from the Gallery and the practice aids work "
                                + "there too, with no clock running."),
                        topic("Zoom and pan", "Scroll to zoom, drag to pan, and double-click to zoom in on a spot. "
                                + "Double-click an adjustment slider to reset it."),
                        topic("Tag menu", "Right-click a tag in Tags to make it a group that only holds sub-tags, or "
                                + "to allow only one of its sub-tags per image. Drag a tag onto another to nest it."),
                        topic("Undo", "Ctrl+Z undoes whatever the undo bar at the bottom is offering, like a delete "
                                + "or a tag change, as long as you're not typing in a text field."),
                        topic("Hidden filters still apply", "Hiding the filter conditions keeps them on, so a Gallery "
                                + "showing fewer images than expected may just be filtered.")),
                section("Your library on disk", 3,
                        topic("Keeping it in git", "The library folder can be a git repo. Images, tags, and the rest of "
                                + "your library are tracked as small text files. Practice history stays local."),
                        topic("Two machines", "Gesturtle picks up a pull when you return to its window. Edits merge on "
                                + "their own. If the same thing changed on both, fix the conflict in git."),
                        topic("Missing files", "Settings › Library › Check library finds images whose files are gone and "
                                + "restores them from a folder you pick, matching by content.")),
                section("Finding images", 2, buildReferenceSources(), buildReferenceLinks()));
    }

    /** One subsection: a small heading over a short paragraph. */
    private VBox topic(String heading, String body) {
        Label headingLabel = new Label(heading);
        headingLabel.getStyleClass().add("help-topic");
        Label bodyLabel = new Label(body);
        bodyLabel.getStyleClass().add("body-text");
        bodyLabel.setWrapText(true);
        return new VBox(6, headingLabel, bodyLabel);
    }

    /** A main section: its subsections in up to {@code columns} columns, fewer when the window is narrow. */
    private Card section(String heading, int columns, javafx.scene.Node... topics) {
        return new Card(heading).add(new ColumnFlow(340, columns, topics).gaps(32, 20));
    }

    /** Places to gather references from, for building a collection of your own. */
    private VBox buildReferenceSources() {
        VBox box = topic("Reference sources",
                "Places to start a collection of your own, some free and some paid. Save what you like and paste it in.");
        Map<String, String> sites = new LinkedHashMap<>();
        sites.put("Proko", "https://www.proko.com/browse/tools");
        sites.put("Pinterest", "https://www.pinterest.com/");
        sites.put("ArtStation photo references", "https://www.artstation.com/marketplace/game-dev/resources/photography/reference");
        sites.put("Bodies in Motion", "https://bodiesinmotion.photo/");
        sites.put("Pose Library", "https://poselibrary.com/reference-poses-for-artists");
        box.getChildren().add(linkList(sites));
        return box;
    }

    /**
     * For someone who'd rather not build and tag a collection of their own: sites
     * that run timed sessions from references they already host.
     */
    private VBox buildReferenceLinks() {
        VBox box = topic("Ready-made practice",
                "Rather not build a collection yet? These free sites run timed sessions with references of their own.");
        Map<String, String> sites = new LinkedHashMap<>();
        sites.put("Line of Action", "https://line-of-action.com/practice-tools/app/figure-drawing");
        sites.put("Quickposes", "https://quickposes.com/en/gestures/timed");
        sites.put("SketchDaily", "https://www.sketchdaily.net/en");
        box.getChildren().add(linkList(sites));
        return box;
    }

    private VBox linkList(Map<String, String> sites) {
        VBox links = new VBox(2);
        sites.forEach((label, url) -> links.getChildren().add(hyperlinkTo(label, url)));
        return links;
    }

    private Hyperlink hyperlinkTo(String label, String url) {
        Hyperlink link = new Hyperlink(label);
        link.setOnAction(e -> hostServices.showDocument(url));
        return link;
    }
}

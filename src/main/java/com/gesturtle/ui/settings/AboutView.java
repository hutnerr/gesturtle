package com.gesturtle.ui.settings;

import com.gesturtle.AppLog;
import com.gesturtle.AppVersion;
import com.gesturtle.ui.components.AppButton;
import com.gesturtle.ui.components.Card;
import com.gesturtle.ui.components.ColumnFlow;
import com.gesturtle.ui.components.FormRow;
import com.gesturtle.ui.components.Logo;
import com.gesturtle.ui.components.Page;
import com.gesturtle.ui.components.ResponsiveRow;
import javafx.application.HostServices;
import javafx.geometry.Pos;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

/**
 * What Gesturtle is: the version, where the project lives, a way to support it, and the third-party work it ships with.
 */
public class AboutView extends Page {
    private static final String REPO = "https://github.com/hutnerr/gesturtle";
    private static final String KOFI = "https://ko-fi.com/hutner";

    /** JavaFX's launcher rather than AWT Desktop.browse(), for the reason in HelpView. */
    private final HostServices hostServices;

    public AboutView(HostServices hostServices) {
        super("About");
        this.hostServices = hostServices;
        setSubtitle("Inside the Gesturtle's shell.");

        ResponsiveRow top = new ResponsiveRow(900, ResponsiveRow.weight(buildOverview(), 2.0), buildMark());
        ResponsiveRow bottom = new ResponsiveRow(900, ResponsiveRow.weight(buildCredits(), 4.0), buildSupport());
        setScrollingBody(false, top, bottom);
    }

    private Card buildOverview() {
        Label version = new Label(AppVersion.display());
        version.getStyleClass().add("body-text");
        VBox links = new VBox(2,
                hyperlinkTo("Source code", REPO),
                hyperlinkTo("Releases", REPO + "/releases"),
                hyperlinkTo("Report a bug or ask for a feature", REPO + "/issues/new"));
        // No automatic check: Gesturtle makes no network requests of its own. The releases page lists the newest.
        AppButton updates = AppButton.secondary("Check for updates", () -> hostServices.showDocument(REPO + "/releases"))
                .icon("refresh");
        HBox versionRow = new HBox(12, version, updates);
        versionRow.setAlignment(Pos.CENTER_LEFT);
        // The GPL asks an interactive program to show its copyright, the lack of warranty, and where the license is.
        Label licenseText = new Label("Copyright (C) 2026 Hunter Baker. Free software under the GNU GPL v3 or later, "
                + "with no warranty.");
        licenseText.getStyleClass().add("body-text");
        licenseText.setWrapText(true);
        VBox license = new VBox(2, licenseText, hyperlinkTo("Read the license", REPO + "/blob/main/LICENSE"));
        AppButton logs = AppButton.secondary("Open log folder", this::openLogFolder).icon("folder");
        Label logsHint = new Label("Errors are written here. Include the newest file when you report a bug.");
        logsHint.getStyleClass().add("body-text");
        logsHint.setWrapText(true);
        return new Card("Gesturtle", "Timed drawing practice from your own tagged library of reference images.")
                .add(
                new FormRow("Version", versionRow),
                new FormRow("Links", links),
                new FormRow("License", license),
                new FormRow("Logs", new VBox(8, logs, logsHint)));
    }

    /** The mark on its own, centered in the overview's smaller neighbor. */
    private Card buildMark() {
        StackPane mark = new StackPane(new Logo(8));
        mark.setAlignment(Pos.CENTER);
        VBox.setVgrow(mark, javafx.scene.layout.Priority.ALWAYS);
        return new Card().add(mark);
    }

    private void openLogFolder() {
        java.nio.file.Path dir = AppLog.logDir();
        try {
            java.nio.file.Files.createDirectories(dir);
        } catch (java.io.IOException ignored) {
            // showDocument reports nothing either way; the path below at least says where to look.
        }
        hostServices.showDocument(dir.toUri().toString());
    }

    private Card buildSupport() {
        Label pitch = new Label("Support me and the things I make!");
        pitch.getStyleClass().add("body-text");
        pitch.setWrapText(true);
        AppButton kofi = AppButton.primary("Support on Ko-fi", () -> hostServices.showDocument(KOFI));
        return new Card("Support Gesturtle").add(new VBox(14, pitch, kofi));
    }

    /** The third-party work Gesturtle ships with and where it came from. */
    private Card buildCredits() {
        return new Card("Credits").add(new ColumnFlow(340, 2,
                credit(text("Sound effects are "),
                        hyperlinkTo("\u201cUI SFX Vol.1 - Lite\u201d", "https://inaruna3565.itch.io/ui-sfx-vol-1-lite"),
                        text(" by inaruna3565, Minimal theme. Royalty-free.")),
                credit(text("The color palette is "),
                        hyperlinkTo("Gruvbox", "https://github.com/morhetz/gruvbox"),
                        text(" by Pavel Pertsev (morhetz), MIT.")),
                credit(text("The typeface is "),
                        hyperlinkTo("Inter", "https://rsms.me/inter/"),
                        text(" by Rasmus Andersson, SIL Open Font License.")),
                credit(text("Icons are from "),
                        hyperlinkTo("SVG Repo", "https://www.svgrepo.com"),
                        text(", "),
                        hyperlinkTo("Lucide", "https://lucide.dev"),
                        text(" (ISC), "),
                        hyperlinkTo("Material Icons", "https://fonts.google.com/icons"),
                        text(" by Google (Apache 2.0), and 16pxls by Paul Mackenzie (CC BY-SA 4.0).")))
                .gaps(32, 12));
    }

    /** A sentence with its link inline, rather than a bare URL under it. */
    private TextFlow credit(javafx.scene.Node... parts) {
        TextFlow flow = new TextFlow(parts);
        flow.getStyleClass().add("body-text-flow");
        return flow;
    }

    /** A bare Text has no style class of its own, so the body-text color has nothing to match without one. */
    private static Text text(String content) {
        Text text = new Text(content);
        text.getStyleClass().add("body-text-part");
        return text;
    }

    private Hyperlink hyperlinkTo(String label, String url) {
        Hyperlink link = new Hyperlink(label);
        link.setOnAction(e -> hostServices.showDocument(url));
        return link;
    }
}

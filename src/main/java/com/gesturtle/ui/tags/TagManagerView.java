package com.gesturtle.ui.tags;

import com.gesturtle.library.LibraryService;
import com.gesturtle.model.Tag;
import com.gesturtle.model.TagColor;
import com.gesturtle.ui.common.Dialogs;
import com.gesturtle.ui.common.Refreshable;
import com.gesturtle.ui.components.AppButton;
import com.gesturtle.ui.components.Card;
import com.gesturtle.ui.components.EmptyState;
import com.gesturtle.ui.components.Page;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Tag CRUD, hierarchy management (drag to reparent), and merge. The right-click menu is TagActions, shared with the docked tag pickers. */
public class TagManagerView extends Page implements Refreshable {
    private final LibraryService library;
    private final TreeView<Tag> treeView = new TreeView<>();
    private final TagActions actions;
    private final EmptyState emptyState = new EmptyState("No tags yet",
            "Organize references, e.g. figure › hands.");

    public TagManagerView(LibraryService library) {
        super("Tags");
        this.library = library;
        this.actions = new TagActions(library, this::refresh);
        getStyleClass().add("tag-manager-view");
        setSubtitle("Drag to reparent, right-click for options.");
        setActions(AppButton.primary("New tag", () -> actions.createChild(null)).icon("plus"));

        treeView.setShowRoot(false);
        GuideTreeCell.install(treeView);
        treeView.setCellFactory(tv -> new TagTreeCell());
        // TreeView has no placeholder support, so the empty state sits on top of it instead.
        StackPane treeStack = new StackPane(treeView, emptyState);
        VBox.setVgrow(treeStack, Priority.ALWAYS);
        setBody(new Card().flush().add(treeStack));

        refresh();
    }

    @Override
    public void onShown() {
        refresh();
    }

    private void refresh() {
        List<Tag> all = library.tags().listAll();
        Map<Integer, List<Tag>> byParent = all.stream()
                .filter(t -> t.parentId() != null)
                .collect(Collectors.groupingBy(Tag::parentId));
        List<Tag> roots = all.stream().filter(t -> t.parentId() == null).toList();

        TreeItem<Tag> hiddenRoot = new TreeItem<>();
        for (Tag root : roots) {
            hiddenRoot.getChildren().add(buildNode(root, byParent));
        }
        hiddenRoot.setExpanded(true);
        treeView.setRoot(hiddenRoot);
        emptyState.setVisible(roots.isEmpty());
    }

    private TreeItem<Tag> buildNode(Tag tag, Map<Integer, List<Tag>> byParent) {
        TreeItem<Tag> item = new TreeItem<>(tag);
        item.setExpanded(true);
        for (Tag child : byParent.getOrDefault(tag.id(), List.of())) {
            item.getChildren().add(buildNode(child, byParent));
        }
        return item;
    }

    private static Label flag(String text, String tooltip) {
        Label label = new Label(text);
        label.getStyleClass().add("tag-flag");
        label.setTooltip(new javafx.scene.control.Tooltip(tooltip));
        HBox.setMargin(label, new Insets(0, 8, 0, 0));
        return label;
    }

    private class TagTreeCell extends GuideTreeCell<Tag> {
        TagTreeCell() {
            setOnDragDetected(e -> {
                if (getItem() == null) return;
                Dragboard db = startDragAndDrop(TransferMode.MOVE);
                ClipboardContent content = new ClipboardContent();
                content.putString(String.valueOf(getItem().id()));
                db.setContent(content);
                e.consume();
            });
            setOnDragOver(e -> {
                if (getItem() != null && e.getDragboard().hasString()) {
                    e.acceptTransferModes(TransferMode.MOVE);
                }
                e.consume();
            });
            setOnDragDropped(e -> {
                if (getItem() == null) {
                    e.setDropCompleted(false);
                    e.consume();
                    return;
                }
                try {
                    int draggedId = Integer.parseInt(e.getDragboard().getString());
                    library.tags().reparent(draggedId, getItem().id());
                    com.gesturtle.ui.common.Sounds.play(com.gesturtle.ui.common.Sounds.Sound.DROP);
                    refresh();
                    e.setDropCompleted(true);
                } catch (IllegalStateException | NumberFormatException ex) {
                    Dialogs.error("Could not move tag", ex.getMessage());
                    e.setDropCompleted(false);
                }
                e.consume();
            });
        }

        @Override
        protected void updateItem(Tag tag, boolean empty) {
            super.updateItem(tag, empty);
            if (empty || tag == null) {
                setContextMenu(null);
                return;
            }

            setContextMenu(actions.menu(tag));
        }

        @Override
        protected List<javafx.scene.Node> content(Tag tag) {
            Label name = new Label(tag.name());
            name.getStyleClass().add("tree-tag-name");
            TagColor color = TagColor.parse(tag.color());
            if (color != null) {
                Region dot = new Region();
                dot.getStyleClass().addAll("tag-swatch", color.styleClass());
                name.setGraphic(dot);
                name.setGraphicTextGap(8);
            }
            int direct = library.tags().countDirect(tag.id());
            int total = library.tags().countIncludingDescendants(tag.id());
            Label counts = new Label(direct + (direct == 1 ? " image" : " images")
                    + (total != direct ? " · " + total + " with sub-tags" : ""));
            counts.getStyleClass().add("hint-label");
            HBox.setMargin(name, new Insets(0, 12, 0, 6));
            List<javafx.scene.Node> nodes = new java.util.ArrayList<>(List.of(name));
            if (tag.groupOnly()) nodes.add(flag("group", "Holds sub-tags. Never applied to images."));
            if (tag.exclusiveChildren()) nodes.add(flag("pick one", "An image can have only one of its children."));
            nodes.add(counts);
            if (tag.description() != null) {
                Label description = new Label("·  " + tag.description().replaceAll("\\s+", " "));
                description.getStyleClass().addAll("hint-label", "tree-tag-description");
                description.setMinWidth(0);
                HBox.setMargin(description, new Insets(0, 0, 0, 10));
                nodes.add(description);
                Tooltip.install(name, new Tooltip(tag.description()));
            }
            return nodes;
        }
    }
}

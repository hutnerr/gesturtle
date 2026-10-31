package com.gesturtle.ui.tags;

import com.gesturtle.library.LibraryService;
import com.gesturtle.library.UndoCapture;
import com.gesturtle.model.Tag;
import com.gesturtle.model.TagColor;
import com.gesturtle.ui.common.Dialogs;
import com.gesturtle.ui.common.Refreshable;
import com.gesturtle.ui.common.UndoBar;
import com.gesturtle.ui.components.AppButton;
import com.gesturtle.ui.components.Card;
import com.gesturtle.ui.components.EmptyState;
import com.gesturtle.ui.components.Icon;
import com.gesturtle.ui.components.Page;
import javafx.geometry.Insets;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
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
import java.util.Optional;
import java.util.stream.Collectors;

/** Tag CRUD, hierarchy management (drag to reparent), and merge. */
public class TagManagerView extends Page implements Refreshable {
    private final LibraryService library;
    private final TreeView<Tag> treeView = new TreeView<>();
    private final EmptyState emptyState = new EmptyState("No tags yet",
            "Organize references, e.g. figure › hands.");

    public TagManagerView(LibraryService library) {
        super("Tags");
        this.library = library;
        getStyleClass().add("tag-manager-view");
        setSubtitle("Drag to reparent, right-click for options.");
        setActions(AppButton.primary("New tag", () -> createChild(null)).icon("plus"));

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

    private void createChild(Tag parent) {
        Dialogs.prompt("New tag", parent == null ? "Tag name" : "Child of \"" + parent.name() + "\"", "")
                .ifPresent(name -> {
                    try {
                        library.tags().create(name, parent == null ? null : parent.id());
                        refresh();
                    } catch (IllegalStateException ex) {
                        Dialogs.error("Could not create tag", ex.getMessage());
                    }
                });
    }

    private void rename(Tag tag) {
        Dialogs.prompt("Rename tag", "New name", tag.name()).ifPresent(name -> {
            try {
                library.tags().rename(tag.id(), name);
                refresh();
            } catch (IllegalStateException ex) {
                Dialogs.error("Could not rename tag", ex.getMessage());
            }
        });
    }

    /** Asks first; a tag with children offers to delete them too, instead of moving them up a level. */
    private void delete(Tag tag) {
        int directCount = library.tags().countDirect(tag.id());
        int descendants = library.tags().countDescendants(tag.id());
        String title = "Delete \"" + tag.name() + "\"";
        String message = directCount == 0 ? null : directCount + (directCount == 1 ? " image loses" : " images lose") + " this tag.";
        boolean withChildren;
        if (descendants == 0) {
            if (!Dialogs.confirmDanger(title, message, "Delete")) return;
            withChildren = false;
        } else {
            int allCount = library.tags().countIncludingDescendants(tag.id());
            Optional<Boolean> choice = Dialogs.confirmDangerWithOption(title, message, "Delete",
                    "Also delete its " + descendants + (descendants == 1 ? " child tag" : " child tags"),
                    "Unchecked, they move up a level instead."
                            + (allCount > 0 ? " Checked, " + allCount + (allCount == 1 ? " image loses" : " images lose")
                            + " tags." : ""));
            if (choice.isEmpty()) return;
            withChildren = choice.get();
        }
        try {
            UndoCapture undo = captureTagChange();
            if (withChildren) library.tags().deleteWithDescendants(tag.id());
            else library.tags().deleteReassignChildren(tag.id());
            UndoBar.offer("Deleted tag “" + tag.name() + "”" + (withChildren ? " and its children" : ""), undo.after());
        } catch (RuntimeException ex) {
            Dialogs.error("Could not delete tag", ex.getMessage());
        }
        refresh();
    }

    /** Everything a tag delete or merge can change: tags, which images carry them, and filters naming them. */
    private UndoCapture captureTagChange() {
        return library.captureForUndo(
                UndoCapture.Table.all("tag", "id"),
                UndoCapture.Table.all("image_tag"),
                UndoCapture.Table.all("saved_view", "id"),
                UndoCapture.Table.all("segment", "id"));
    }

    private void mergeInto(Tag source) {
        // Not its own children: merging a tag into one of them would leave nowhere to put the rest.
        // Listed by full path (unique, unlike names), so a tag record never shows through as its toString.
        java.util.Map<String, Tag> byPath = new java.util.TreeMap<>(
                String.CASE_INSENSITIVE_ORDER.thenComparing(java.util.Comparator.naturalOrder()));
        for (Tag t : library.tags().listAll()) {
            if (t.id() != source.id() && !library.tags().isDescendant(source.id(), t.id())) {
                byPath.put(library.tags().fullPath(t.id()), t);
            }
        }
        if (byPath.isEmpty()) {
            Dialogs.error("Merge tag", "No other tags to merge into.");
            return;
        }
        List<String> paths = List.copyOf(byPath.keySet());
        ChoiceDialog<String> dialog = new ChoiceDialog<>(paths.get(0), paths);
        dialog.setTitle("Merge tag");
        dialog.setHeaderText(null);
        dialog.setContentText("Merge \"" + source.name() + "\" into:");
        Dialogs.prepare(dialog);
        Optional<Tag> target = dialog.showAndWait().map(byPath::get);
        target.ifPresent(t -> {
            try {
                UndoCapture undo = captureTagChange();
                library.tags().merge(source.id(), t.id());
                UndoBar.offer("Merged “" + source.name() + "” into “" + t.name() + "”", undo.after());
            } catch (RuntimeException ex) {
                Dialogs.error("Could not merge tags", ex.getMessage());
            }
            refresh();
        });
    }

    /** A playlist of the tag's images (its children's too); keeping the tag makes it a copy. */
    private void convertToPlaylist(Tag tag) {
        int count = library.tags().countIncludingDescendants(tag.id());
        String message = count + (count == 1 ? " image" : " images")
                + " with this tag or one under it go into a new playlist, oldest import first.";
        Dialogs.promptWithOption("Convert to playlist", message, "Playlist name", tag.name(),
                "Keep the tag (copy instead of convert)", true, "Convert").ifPresent(choice -> {
            try {
                UndoCapture undo = library.captureForUndo(
                        UndoCapture.Table.all("tag", "id"),
                        UndoCapture.Table.all("image_tag"),
                        UndoCapture.Table.all("playlist", "id"),
                        UndoCapture.Table.all("playlist_item"));
                com.gesturtle.library.PlaylistTagConversion.tagToPlaylist(library, tag.id(), choice.name(), choice.option());
                UndoBar.offer((choice.option() ? "Copied" : "Converted") + " “" + tag.name() + "” to playlist “"
                        + choice.name() + "”", undo.after());
            } catch (RuntimeException ex) {
                Dialogs.error("Could not convert tag", ex.getMessage());
            }
            refresh();
        });
    }

    private void editDescription(Tag tag) {
        javafx.scene.control.TextArea area = new javafx.scene.control.TextArea(tag.description() == null ? "" : tag.description());
        area.setWrapText(true);
        area.setPrefRowCount(4);
        area.setPrefColumnCount(36);
        area.setPromptText("What belongs under this tag, or what to look for in its images");
        javafx.scene.control.Dialog<javafx.scene.control.ButtonType> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("Describe \"" + tag.name() + "\"");
        Label heading = new Label("Description for \"" + tag.name() + "\"");
        heading.getStyleClass().add("dialog-title");
        dialog.getDialogPane().setContent(new VBox(10, heading, area));
        dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.CANCEL, javafx.scene.control.ButtonType.OK);
        Dialogs.prepare(dialog);
        javafx.application.Platform.runLater(area::requestFocus);
        if (dialog.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL) != javafx.scene.control.ButtonType.OK) return;
        library.tags().setDescription(tag.id(), area.getText());
        refresh();
    }

    private Menu colorMenu(Tag tag) {
        Menu menu = new Menu("Color");
        ToggleGroup group = new ToggleGroup();
        TagColor current = TagColor.parse(tag.color());
        RadioMenuItem none = new RadioMenuItem("None");
        none.setToggleGroup(group);
        none.setSelected(current == null);
        none.setOnAction(e -> {
            library.tags().setColor(tag.id(), null);
            refresh();
        });
        menu.getItems().add(none);
        for (TagColor color : TagColor.values()) {
            Region swatch = new Region();
            swatch.getStyleClass().addAll("tag-swatch", color.styleClass());
            RadioMenuItem item = new RadioMenuItem(color.label(), swatch);
            item.setToggleGroup(group);
            item.setSelected(color == current);
            item.setOnAction(e -> {
                library.tags().setColor(tag.id(), color.name());
                refresh();
            });
            menu.getItems().add(item);
        }
        return menu;
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

            ContextMenu menu = new ContextMenu();
            MenuItem addChild = new MenuItem("New child tag…", new Icon("plus", 14));
            addChild.setOnAction(e -> createChild(tag));
            MenuItem renameItem = new MenuItem("Rename…", new Icon("pencil", 14));
            renameItem.setOnAction(e -> rename(tag));
            MenuItem deleteItem = new MenuItem("Delete", new Icon("trash", 14));
            deleteItem.setOnAction(e -> delete(tag));
            MenuItem mergeItem = new MenuItem("Merge into…");
            mergeItem.setOnAction(e -> mergeInto(tag));
            MenuItem toPlaylist = new MenuItem("Convert to playlist…", new Icon("queue", 14));
            toPlaylist.setOnAction(e -> convertToPlaylist(tag));
            MenuItem moveToRoot = new MenuItem("Move to top level", new Icon("arrow-up", 14));
            moveToRoot.setOnAction(e -> {
                try {
                    library.tags().reparent(tag.id(), null);
                    refresh();
                } catch (IllegalStateException ex) {
                    Dialogs.error("Could not move tag", ex.getMessage());
                }
            });
            CheckMenuItem groupOnly = new CheckMenuItem("Group only (not applied to images)");
            groupOnly.setSelected(tag.groupOnly());
            groupOnly.setOnAction(e -> {
                library.tags().setGroupOnly(tag.id(), groupOnly.isSelected());
                refresh();
            });
            CheckMenuItem exclusive = new CheckMenuItem("Only one child per image");
            exclusive.setSelected(tag.exclusiveChildren());
            exclusive.setOnAction(e -> {
                library.tags().setExclusiveChildren(tag.id(), exclusive.isSelected());
                refresh();
            });
            MenuItem describe = new MenuItem(tag.description() == null ? "Add description…" : "Edit description…");
            describe.setOnAction(e -> editDescription(tag));
            menu.getItems().addAll(addChild, renameItem, moveToRoot, mergeItem, toPlaylist, deleteItem,
                    new SeparatorMenuItem(), colorMenu(tag), describe,
                    new SeparatorMenuItem(), groupOnly, exclusive);
            setContextMenu(menu);
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

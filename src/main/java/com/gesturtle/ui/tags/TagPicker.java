package com.gesturtle.ui.tags;

import com.gesturtle.library.LibraryService;
import com.gesturtle.model.Tag;
import com.gesturtle.ui.common.Dialogs;
import com.gesturtle.ui.components.AppButton;
import com.gesturtle.ui.components.Card;
import com.gesturtle.ui.components.Spacer;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reusable tag tree selector: single or multi-select, with optional
 * create-on-the-fly. Used
 * by import batch tagging, the filter builder, bulk tagging, and per-image
 * tagging; only the filter builder (picking an existing tag to filter by,
 * not managing the vocabulary) disables tag creation. Starts fully expanded,
 * with a button to collapse/expand the whole tree.
 *
 * Multi-select uses a checkbox per row rather than relying on the TreeView's
 * own MULTIPLE selection mode (which needs Ctrl/Shift+click and isn't
 * discoverable, a plain click would just replace the whole selection).
 */
public class TagPicker extends VBox {
    private final LibraryService library;
    private final boolean multiSelect;
    private final boolean allowCreate;
    private final TreeView<Tag> treeView = new TreeView<>();
    private final TextField newTagField = new TextField();
    private final AppButton collapseToggle = AppButton.ghost("Collapse all", null).small();
    private boolean collapsed = false;
    private Runnable onChange;
    private boolean quiet;

    public TagPicker(LibraryService library, boolean multiSelect) {
        this(library, multiSelect, true);
    }

    /** allowCreate is false for the filter builder's tag picker: you're choosing an existing tag to filter by, not managing the tag vocabulary. */
    public TagPicker(LibraryService library, boolean multiSelect, boolean allowCreate) {
        this.library = library;
        this.multiSelect = multiSelect;
        this.allowCreate = allowCreate;
        setSpacing(12);
        setPrefWidth(460);

        treeView.setShowRoot(false);
        GuideTreeCell.install(treeView);
        if (multiSelect) {
            treeView.setCellFactory(tv -> new CheckCell());
        } else {
            treeView.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
            treeView.setCellFactory(tv -> new GuideTreeCell<>() {
                @Override
                protected List<javafx.scene.Node> content(Tag tag) {
                    return nameAndFlags(tag);
                }
            });
        }
        treeView.setPrefHeight(380);

        collapseToggle.setOnAction(e -> toggleCollapse());
        javafx.scene.control.Label heading = new javafx.scene.control.Label(
                multiSelect ? "Check all that apply" : "Pick a tag");
        heading.getStyleClass().add("muted-label");
        HBox treeToolbar = new HBox(8, heading, new Spacer(), collapseToggle);
        treeToolbar.setAlignment(Pos.CENTER_LEFT);

        Card treeCard = new Card().flush().add(treeView);
        // Grows when the picker is given more height than its preferred (docked in a side panel).
        VBox.setVgrow(treeCard, javafx.scene.layout.Priority.ALWAYS);
        getChildren().addAll(treeToolbar, treeCard);

        if (allowCreate) {
            newTagField.setPromptText("New tag (under selection)");
            newTagField.setOnAction(e -> createTagUnderSelection());
            HBox.setHgrow(newTagField, javafx.scene.layout.Priority.ALWAYS);
            HBox addRow = new HBox(8, newTagField, AppButton.secondary("Add", this::createTagUnderSelection).icon("plus"));
            getChildren().add(addRow);
        }

        refresh();
    }

    private static Label nameLabel(Tag tag) {
        Label name = new Label(tag.name());
        name.getStyleClass().add("tree-tag-name");
        HBox.setMargin(name, new javafx.geometry.Insets(0, 0, 0, 6));
        com.gesturtle.model.TagColor color = com.gesturtle.model.TagColor.parse(tag.color());
        if (color != null) {
            javafx.scene.layout.Region dot = new javafx.scene.layout.Region();
            dot.getStyleClass().addAll("tag-swatch", color.styleClass());
            name.setGraphic(dot);
            name.setGraphicTextGap(8);
        }
        if (tag.description() != null) name.setTooltip(new javafx.scene.control.Tooltip(tag.description()));
        return name;
    }

    /** Name plus a "pick one" marker on a tag whose children are exclusive, so the unchecking isn't a surprise. */
    private static List<javafx.scene.Node> nameAndFlags(Tag tag) {
        if (!tag.exclusiveChildren()) return List.of(nameLabel(tag));
        Label flag = new Label("pick one");
        flag.getStyleClass().add("tag-flag");
        HBox.setMargin(flag, new javafx.geometry.Insets(0, 0, 0, 10));
        return List.of(nameLabel(tag), flag);
    }

    /**
     * A row with a checkbox bound to its CheckBoxTreeItem. Clicking anywhere
     * on the row except the box itself and the chevron toggles it, so the
     * tag's name is a click target too. A group-only tag gets no checkbox:
     * it's there to hold its children, and can't be applied itself.
     */
    private static class CheckCell extends GuideTreeCell<Tag> {
        private CheckBox checkBox;
        private CheckBoxTreeItem<Tag> boundItem;

        CheckCell() {
            setOnMouseClicked(e -> {
                if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY || boundItem == null) return;
                javafx.scene.Node target = e.getPickResult().getIntersectedNode();
                if (isChevron(target) || isInside(target, checkBox) || boundItem.getValue().groupOnly()) return;
                // fire() rather than setting it, so it's a click like one on the box: it makes the interface sound.
                checkBox.fire();
            });
        }

        @Override
        protected void updateItem(Tag tag, boolean empty) {
            unbind();
            super.updateItem(tag, empty);
        }

        @Override
        protected List<javafx.scene.Node> content(Tag tag) {
            unbind();
            if (tag.groupOnly()) {
                checkBox = null;
                if (getTreeItem() instanceof CheckBoxTreeItem<Tag> item) boundItem = item;
                List<javafx.scene.Node> nodes = new ArrayList<>(nameAndFlags(tag));
                nodes.get(0).getStyleClass().add("tree-tag-group");
                return nodes;
            }
            checkBox = new CheckBox();
            if (getTreeItem() instanceof CheckBoxTreeItem<Tag> item) {
                boundItem = item;
                checkBox.selectedProperty().bindBidirectional(item.selectedProperty());
            }
            HBox.setMargin(checkBox, new javafx.geometry.Insets(0, 0, 0, 4));
            List<javafx.scene.Node> nodes = new ArrayList<>(List.of(checkBox));
            nodes.addAll(nameAndFlags(tag));
            return nodes;
        }

        /** Cells are recycled across rows; drop the old row's binding or one box ends up driving two tags. */
        private void unbind() {
            if (checkBox != null && boundItem != null) {
                checkBox.selectedProperty().unbindBidirectional(boundItem.selectedProperty());
            }
            boundItem = null;
        }

        private static boolean isInside(javafx.scene.Node node, javafx.scene.Node container) {
            for (javafx.scene.Node n = node; n != null && container != null; n = n.getParent()) {
                if (n == container) return true;
            }
            return false;
        }
    }

    public void refresh() {
        List<Tag> all = library.tags().listAll();
        Map<Integer, List<Tag>> byParent = all.stream()
                .filter(t -> t.parentId() != null)
                .collect(Collectors.groupingBy(Tag::parentId));
        List<Tag> roots = all.stream().filter(t -> t.parentId() == null).toList();

        TreeItem<Tag> hiddenRoot = multiSelect ? new CheckBoxTreeItem<>() : new TreeItem<>();
        for (Tag root : roots) {
            hiddenRoot.getChildren().add(buildNode(root, null, byParent));
        }
        treeView.setRoot(hiddenRoot);
        collapsed = false;
        collapseToggle.setText("Collapse all");
    }

    private TreeItem<Tag> buildNode(Tag tag, Tag parent, Map<Integer, List<Tag>> byParent) {
        TreeItem<Tag> item;
        if (multiSelect) {
            CheckBoxTreeItem<Tag> checkItem = new CheckBoxTreeItem<>(tag);
            checkItem.setIndependent(true);
            checkItem.selectedProperty().addListener((o, was, on) -> fireChange());
            if (parent != null && parent.exclusiveChildren()) {
                // Checking one child of a "pick one" tag unchecks the rest.
                checkItem.selectedProperty().addListener((o, was, on) -> {
                    if (!on || checkItem.getParent() == null) return;
                    for (TreeItem<Tag> sibling : checkItem.getParent().getChildren()) {
                        if (sibling != checkItem && sibling instanceof CheckBoxTreeItem<Tag> other) other.setSelected(false);
                    }
                });
            }
            item = checkItem;
        } else {
            item = new TreeItem<>(tag);
        }
        item.setExpanded(true);
        for (Tag child : byParent.getOrDefault(tag.id(), List.of())) {
            item.getChildren().add(buildNode(child, tag, byParent));
        }
        return item;
    }

    /**
     * Collapses/expands every tag's sub-tags, but leaves the root-level tags
     * themselves listed, the hidden tree root (treeView.getRoot(), separate
     * from setShowRoot(false) which only hides its own row) must stay
     * expanded itself, or its children (every root-level tag) disappear too.
     */
    private void toggleCollapse() {
        // Dip the tree out and back in around the change, rather than rows jumping all at once.
        javafx.animation.FadeTransition out = new javafx.animation.FadeTransition(javafx.util.Duration.millis(90), treeView);
        out.setToValue(0);
        out.setOnFinished(e -> {
            applyCollapse();
            com.gesturtle.ui.common.Motion.enter(treeView, collapsed ? -6 : 6, 200);
        });
        out.play();
    }

    private void applyCollapse() {
        collapsed = !collapsed;
        collapseToggle.setText(collapsed ? "Expand all" : "Collapse all");
        TreeItem<Tag> root = treeView.getRoot();
        if (root == null) return;
        for (TreeItem<Tag> child : root.getChildren()) {
            setAllExpanded(child, !collapsed);
        }
    }

    private void setAllExpanded(TreeItem<Tag> node, boolean expanded) {
        if (node == null) return;
        node.setExpanded(expanded);
        for (TreeItem<Tag> child : node.getChildren()) {
            setAllExpanded(child, expanded);
        }
    }

    private void createTagUnderSelection() {
        String name = newTagField.getText() == null ? "" : newTagField.getText().trim();
        if (name.isEmpty()) return;
        TreeItem<Tag> selected = treeView.getSelectionModel().getSelectedItem();
        Integer parentId = selected == null || selected.getValue() == null ? null : selected.getValue().id();
        try {
            int newId = library.tags().create(name, parentId);
            newTagField.clear();
            // Rebuilding the tree drops the checkmarks; put them back, plus the new tag.
            Set<Integer> keep = multiSelect ? new HashSet<>(getSelectedTagIds()) : new HashSet<>();
            keep.add(newId);
            refresh();
            selectTagIds(keep);
            fireChange();
        } catch (IllegalStateException ex) {
            Dialogs.error("Could not create tag", ex.getMessage());
        }
    }

    public Set<Integer> getSelectedTagIds() {
        if (multiSelect) {
            Set<Integer> ids = new HashSet<>();
            collectChecked(treeView.getRoot(), ids);
            return ids;
        }
        Set<Integer> ids = new HashSet<>();
        for (TreeItem<Tag> item : treeView.getSelectionModel().getSelectedItems()) {
            if (item != null && item.getValue() != null) ids.add(item.getValue().id());
        }
        return ids;
    }

    private void collectChecked(TreeItem<Tag> node, Set<Integer> ids) {
        if (node == null) return;
        if (node instanceof CheckBoxTreeItem<Tag> checkItem && checkItem.isSelected() && node.getValue() != null) {
            ids.add(node.getValue().id());
        }
        for (TreeItem<Tag> child : node.getChildren()) {
            collectChecked(child, ids);
        }
    }

    /**
     * Hears each checkbox the user ticks or unticks (and a tag created on the
     * fly), for a picker that applies as you go rather than on OK. Not called
     * for selectTagIds: that's the caller setting the state, not the user.
     */
    public void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    private void fireChange() {
        if (!quiet && onChange != null) onChange.run();
    }

    /** Moves keyboard focus into the tag tree. */
    public void focusTree() {
        treeView.requestFocus();
    }

    public void selectTagIds(Set<Integer> tagIds) {
        List<TreeItem<Tag>> toExpand = new ArrayList<>();
        if (multiSelect) {
            quiet = true;
            try {
                applyCheckedRecursive(treeView.getRoot(), tagIds, toExpand);
            } finally {
                quiet = false;
            }
        } else {
            treeView.getSelectionModel().clearSelection();
            selectRecursive(treeView.getRoot(), tagIds, toExpand);
        }
        for (TreeItem<Tag> item : toExpand) {
            TreeItem<Tag> parent = item.getParent();
            while (parent != null) {
                parent.setExpanded(true);
                parent = parent.getParent();
            }
        }
    }

    private void applyCheckedRecursive(TreeItem<Tag> node, Set<Integer> tagIds, List<TreeItem<Tag>> toExpand) {
        if (node == null) return;
        if (node instanceof CheckBoxTreeItem<Tag> checkItem && node.getValue() != null) {
            boolean shouldBeChecked = tagIds.contains(node.getValue().id());
            checkItem.setSelected(shouldBeChecked);
            if (shouldBeChecked) toExpand.add(node);
        }
        for (TreeItem<Tag> child : node.getChildren()) {
            applyCheckedRecursive(child, tagIds, toExpand);
        }
    }

    private void selectRecursive(TreeItem<Tag> node, Set<Integer> tagIds, List<TreeItem<Tag>> toExpand) {
        if (node == null) return;
        if (node.getValue() != null && tagIds.contains(node.getValue().id())) {
            treeView.getSelectionModel().select(node);
            toExpand.add(node);
        }
        for (TreeItem<Tag> child : node.getChildren()) {
            selectRecursive(child, tagIds, toExpand);
        }
    }

    /** Opens a modal single-tag picker; used by the filter builder to choose one tag per row (no on-the-fly tag creation; you're picking an existing tag to filter by). */
    public static java.util.Optional<Tag> pickOne(LibraryService library) {
        TagPicker picker = new TagPicker(library, false, false);
        javafx.scene.control.Dialog<Tag> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("Choose a tag");
        dialog.getDialogPane().getButtonTypes().addAll(
                javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        dialog.getDialogPane().setContent(picker);
        Dialogs.prepare(dialog);
        dialog.setResultConverter(buttonType -> {
            if (buttonType != javafx.scene.control.ButtonType.OK) return null;
            Set<Integer> selected = picker.getSelectedTagIds();
            if (selected.isEmpty()) return null;
            return library.tags().get(selected.iterator().next()).orElse(null);
        });
        return dialog.showAndWait();
    }

    /** Opens a modal multi-tag picker (checkbox per tag) with nothing pre-checked; used by bulk/batch tagging. */
    public static java.util.Optional<Set<Integer>> pickMany(LibraryService library) {
        return pickMany(library, Set.of());
    }

    /** Opens a modal multi-tag picker with {@code preselected} already checked; used for a single image's tags. */
    public static java.util.Optional<Set<Integer>> pickMany(LibraryService library, Set<Integer> preselected) {
        TagPicker picker = new TagPicker(library, true);
        picker.selectTagIds(preselected);
        javafx.scene.control.Dialog<Set<Integer>> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("Choose tags");
        dialog.getDialogPane().getButtonTypes().addAll(
                javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        dialog.getDialogPane().setContent(picker);
        Dialogs.prepare(dialog);
        dialog.setResultConverter(buttonType ->
                buttonType == javafx.scene.control.ButtonType.OK ? picker.getSelectedTagIds() : null);
        return dialog.showAndWait();
    }
}

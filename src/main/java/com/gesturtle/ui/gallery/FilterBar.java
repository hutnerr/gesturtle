package com.gesturtle.ui.gallery;

import com.gesturtle.ui.common.Motion;
import com.gesturtle.filter.Filter;
import com.gesturtle.library.LibraryService;
import com.gesturtle.ui.components.AppButton;
import com.gesturtle.ui.components.Icon;
import com.gesturtle.ui.filter.ConditionBuilder;
import javafx.geometry.Pos;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/**
 * The shared filter bar: an optional quick text
 * search plus the {@link ConditionBuilder}, which the Filter toggle shows or
 * hides (hidden conditions still apply). Used by the Gallery and by the
 * Practice setup's "Images matching" row, so a query built in one reads the
 * same in the other.
 */
public class FilterBar extends VBox {
    private final boolean showNameSearch;
    private final TextField nameSearch = new TextField();
    private final ConditionBuilder conditions;
    private final AppButton clearButton;
    private final ToggleButton filterToggle = AppButton.withIcon(AppButton.toggle("Filter", AppButton.Variant.SECONDARY), "filter");
    private final FlowPane topRow = new FlowPane(12, 10);
    private boolean openByDefault;
    private Consumer<Filter> onChange = f -> {
    };

    public FilterBar(LibraryService library) {
        this(library, true);
    }

    /** showNameSearch is false for the Practice page's copy; a quick filename box isn't useful there (a "File name" condition still is). */
    public FilterBar(LibraryService library, boolean showNameSearch) {
        this.showNameSearch = showNameSearch;
        getStyleClass().add("filter-bar");
        setSpacing(12);

        conditions = new ConditionBuilder(library);
        conditions.setExpanded(false);
        conditions.setOnChange(this::fireChange);

        filterToggle.setTooltip(new javafx.scene.control.Tooltip("Show or hide the conditions. Hidden ones still apply."));
        // Opening with nothing to show starts the first condition; later ones come from each row's And/Or.
        filterToggle.setOnAction(e -> {
            if (filterToggle.isSelected()) openConditions(true);
            else conditions.setExpanded(false);
        });

        nameSearch.setPromptText("Search names, credit, notes");
        nameSearch.setPrefWidth(280);
        nameSearch.setMinWidth(160);
        nameSearch.textProperty().addListener((o, a, b) -> fireChange());

        clearButton = AppButton.ghost("Clear", conditions::clear).small();
        clearButton.setTooltip(new javafx.scene.control.Tooltip("Remove every condition"));

        // Wraps onto a second line instead of forcing the page wider in a narrow window.
        topRow.setAlignment(Pos.CENTER_LEFT);
        if (showNameSearch) {
            // TextField has no graphic slot: overlay the icon and pad the text past it (.search-field).
            Icon searchIcon = new Icon("search", 15);
            searchIcon.setMouseTransparent(true);
            nameSearch.getStyleClass().add("search-field");
            StackPane search = new StackPane(nameSearch, searchIcon);
            StackPane.setAlignment(searchIcon, Pos.CENTER_LEFT);
            StackPane.setMargin(searchIcon, new javafx.geometry.Insets(0, 0, 0, 14));
            topRow.getChildren().add(search);
        }
        topRow.getChildren().addAll(filterToggle, clearButton);
        updateFilterControls();

        getChildren().addAll(topRow, conditions);
    }

    /**
     * Starts with the conditions showing (a blank first row if there are none), and shows them again after
     * every {@link #loadFilter}. The Practice page uses this, since a filter is what it's there to set;
     * the Gallery starts with them hidden.
     */
    public void setOpenByDefault(boolean open) {
        openByDefault = open;
        if (open) openConditions(false);
    }

    private void openConditions(boolean focus) {
        filterToggle.setSelected(true);
        conditions.setExpanded(true);
        if (conditions.isEmpty()) conditions.addCondition(focus);
    }

    /** Controls before the search box (the Gallery's saved views), on the same wrapping row. */
    public void addToTopRowStart(javafx.scene.Node... nodes) {
        topRow.getChildren().addAll(0, List.of(nodes));
    }

    /** Extra controls after the search and condition buttons (the Gallery's sort and thumbnail size). */
    public void addToTopRow(javafx.scene.Node... nodes) {
        topRow.getChildren().addAll(nodes);
    }

    public void setOnChange(Consumer<Filter> onChange) {
        this.onChange = onChange;
    }

    private void fireChange() {
        updateFilterControls();
        onChange.accept(buildFilter());
    }

    /** The toggle counts the conditions (so hidden ones aren't forgotten) and closes once the last is removed. */
    private void updateFilterControls() {
        boolean empty = conditions.isEmpty();
        Motion.setShown(clearButton, !empty);
        if (empty && filterToggle.isSelected()) {
            filterToggle.setSelected(false);
            conditions.setExpanded(false);
        }
        int count = conditions.conditionCount();
        filterToggle.setText(count == 0 ? "Filter" : "Filter · " + count);
    }

    /** Shows this filter as rows (e.g. a saved preset or view), replacing any quick text search. */
    public void loadFilter(Filter filter) {
        nameSearch.setText("");
        conditions.load(filter);
        if (openByDefault) openConditions(false);
        fireChange();
    }

    public Filter buildFilter() {
        Filter built = conditions.buildFilter();
        String search = showNameSearch ? nameSearch.getText().trim() : "";
        if (search.isEmpty()) return built;
        Filter text = new Filter.Or(List.of(
                new Filter.NamePredicate(Filter.TextMatch.CONTAINS, search),
                new Filter.DetailPredicate(Filter.DetailField.CREDIT, search),
                new Filter.DetailPredicate(Filter.DetailField.NOTES, search)));
        return built instanceof Filter.All ? text : new Filter.And(List.of(text, built));
    }
}

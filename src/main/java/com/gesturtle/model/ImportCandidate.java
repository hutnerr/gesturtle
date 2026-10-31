package com.gesturtle.model;

import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A row in the import staging table; mutable while the batch is reviewed. */
public class ImportCandidate {
    private final Path sourcePath;
    private final SimpleObjectProperty<ImportStatus> status = new SimpleObjectProperty<>();
    private final SimpleStringProperty reason = new SimpleStringProperty("");
    private final SimpleBooleanProperty included = new SimpleBooleanProperty(true);
    private String hash;
    private int width;
    private int height;
    private long byteSize;
    private final List<Integer> tagOverrides = new ArrayList<>();
    /** Playlists the file is added to on import, on the end of each. */
    private final Set<Integer> playlistIds = new LinkedHashSet<>();
    /** Imported as a favorite; an image already in the library is favorited, never unfavorited. */
    private boolean favorite;
    /** Only consulted for ALREADY_IN_LIBRARY rows; the Import page asks before each commit. */
    private DuplicateAction duplicateAction = DuplicateAction.UPDATE;
    /** Filled in as the image's credit on import (the link it came from); null for ordinary files. */
    private String credit;
    /** Filled in as the image's notes on import (the source link, when Settings asks for it); null otherwise. */
    private String notes;

    public ImportCandidate(Path sourcePath) {
        this.sourcePath = sourcePath;
        this.status.set(ImportStatus.NEW);
    }

    public Path sourcePath() {
        return sourcePath;
    }

    public String fileName() {
        return sourcePath.getFileName().toString();
    }

    public SimpleObjectProperty<ImportStatus> statusProperty() {
        return status;
    }

    public SimpleStringProperty reasonProperty() {
        return reason;
    }

    public SimpleBooleanProperty includedProperty() {
        return included;
    }

    public boolean isIncluded() {
        return included.get();
    }

    /** Rows can only be included if the status allows it: unreadable/duplicate rows never can. */
    public boolean isIncludable() {
        return getStatus() == ImportStatus.NEW || getStatus() == ImportStatus.ALREADY_IN_LIBRARY;
    }

    public ImportStatus getStatus() {
        return status.get();
    }

    public void setStatus(ImportStatus value, String reasonText) {
        status.set(value);
        reason.set(reasonText == null ? "" : reasonText);
        included.set(value == ImportStatus.NEW);
    }

    /**
     * Records why the last import of this row failed. Status and the checkbox
     * are left alone, so importing again retries it exactly as before.
     */
    public void markFailed(String reasonText) {
        reason.set("Import failed: " + (reasonText == null ? "unknown error" : reasonText));
    }

    /** Applies a completed probe; call only on the FX thread. */
    public void applyProbe(com.gesturtle.library.ImportService.ProbeOutcome outcome) {
        this.hash = outcome.hash();
        setDimensions(outcome.width(), outcome.height(), outcome.byteSize());
        setStatus(outcome.status(), outcome.reason());
    }

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public long getByteSize() {
        return byteSize;
    }

    public void setDimensions(int width, int height, long byteSize) {
        this.width = width;
        this.height = height;
        this.byteSize = byteSize;
    }

    public DuplicateAction getDuplicateAction() {
        return duplicateAction;
    }

    public void setDuplicateAction(DuplicateAction duplicateAction) {
        this.duplicateAction = duplicateAction;
    }

    public String credit() {
        return credit;
    }

    public void setCredit(String credit) {
        this.credit = credit;
    }

    public String notes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public List<Integer> tagOverrides() {
        return tagOverrides;
    }

    public Set<Integer> playlistIds() {
        return playlistIds;
    }

    public boolean isFavorite() {
        return favorite;
    }

    public void setFavorite(boolean favorite) {
        this.favorite = favorite;
    }

    public String dimensionsText() {
        return width > 0 ? width + " x " + height : "";
    }
}

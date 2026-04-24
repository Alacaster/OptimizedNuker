package dev.firstmage.optimizednuker.modules;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class MiniHudSelectionState {
    private final LinkedHashSet<String> draftTokens = new LinkedHashSet<>();
    private boolean draftLoaded;

    void ensureDraftLoaded(String rawStoredSelection, List<MiniHudRegionApi.ShapeHandle> shapes) {
        if (!draftLoaded) {
            reloadDraftFromStored(rawStoredSelection, shapes);
        }
    }

    void reloadDraftFromStored(String rawStoredSelection, List<MiniHudRegionApi.ShapeHandle> shapes) {
        draftTokens.clear();
        draftTokens.addAll(normalizeTokens(parseStoredTokens(rawStoredSelection), shapes));
        draftLoaded = true;
    }

    void normalizeDraft(List<MiniHudRegionApi.ShapeHandle> shapes) {
        if (!draftLoaded) return;
        LinkedHashSet<String> copy = new LinkedHashSet<>(draftTokens);
        draftTokens.clear();
        draftTokens.addAll(normalizeTokens(copy, shapes));
    }

    boolean hasAllSelectable(List<MiniHudRegionApi.ShapeHandle> shapes) {
        boolean anySelectable = false;
        for (MiniHudRegionApi.ShapeHandle handle : shapes) {
            if (!handle.supported) continue;
            anySelectable = true;
            if (!draftTokens.contains(handle.displayName)) return false;
        }
        return anySelectable;
    }

    void setAllSelectable(List<MiniHudRegionApi.ShapeHandle> shapes, boolean selected) {
        for (MiniHudRegionApi.ShapeHandle handle : shapes) {
            if (!handle.supported) continue;
            setSelected(handle, selected);
        }
    }

    void setSelected(MiniHudRegionApi.ShapeHandle handle, boolean selected) {
        draftTokens.remove(handle.selectionKey);
        draftTokens.remove(handle.displayName);
        if (selected) draftTokens.add(handle.displayName);
    }

    boolean isSelected(MiniHudRegionApi.ShapeHandle handle) {
        return draftTokens.contains(handle.displayName) || draftTokens.contains(handle.selectionKey);
    }

    String draftSelectionString() {
        return String.join("|", draftTokens);
    }

    LinkedHashSet<String> normalizedStoredSelection(String rawStoredSelection, List<MiniHudRegionApi.ShapeHandle> shapes) {
        return normalizeTokens(parseStoredTokens(rawStoredSelection), shapes);
    }

    Set<String> storedSelectionTokens(String rawStoredSelection) {
        return parseStoredTokens(rawStoredSelection);
    }

    private LinkedHashSet<String> parseStoredTokens(String rawStoredSelection) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (rawStoredSelection == null || rawStoredSelection.isBlank()) return out;
        for (String part : rawStoredSelection.split("\\|")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    private LinkedHashSet<String> normalizeTokens(Set<String> tokens, List<MiniHudRegionApi.ShapeHandle> shapes) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (MiniHudRegionApi.ShapeHandle handle : shapes) {
            if (tokens.contains(handle.selectionKey) || tokens.contains(handle.displayName)) {
                normalized.add(handle.displayName);
            }
        }
        return normalized;
    }
}

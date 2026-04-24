package dev.firstmage.optimizednuker.modules;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class MiniHudSelectionState {
    private final LinkedHashSet<String> draftTokens = new LinkedHashSet<>();
    private boolean draftLoaded;

    void ensureDraftLoaded(String rawStoredSelection, List<MiniHudRegionApi.ShapeHandle> shapes) {
        if (!draftLoaded) reloadDraftFromStored(rawStoredSelection, shapes);
    }

    void reloadDraftFromStored(String rawStoredSelection, List<MiniHudRegionApi.ShapeHandle> shapes) {
        draftTokens.clear();
        Set<String> stored = parseStoredTokens(rawStoredSelection);
        draftTokens.addAll(shapes.isEmpty() ? stored : normalizeTokens(stored, shapes));
        draftLoaded = true;
    }

    void normalizeDraft(List<MiniHudRegionApi.ShapeHandle> shapes) {
        if (!draftLoaded || shapes.isEmpty()) return;

        LinkedHashSet<String> normalized = normalizeTokens(draftTokens, shapes);
        draftTokens.clear();
        draftTokens.addAll(normalized);
    }

    boolean hasAllSelectable(List<MiniHudRegionApi.ShapeHandle> shapes) {
        boolean anySelectable = false;
        for (MiniHudRegionApi.ShapeHandle handle : shapes) {
            if (!handle.supported) continue;
            anySelectable = true;
            if (!draftTokens.contains(handle.selectionKey) && !draftTokens.contains(handle.displayName)) return false;
        }
        return anySelectable;
    }

    void setAllSelectable(List<MiniHudRegionApi.ShapeHandle> shapes, boolean selected) {
        for (MiniHudRegionApi.ShapeHandle handle : shapes) {
            if (!handle.supported) continue;
            setSelected(handle, selected);
        }
    }

    void clearVisible(List<MiniHudRegionApi.ShapeHandle> shapes) {
        for (MiniHudRegionApi.ShapeHandle handle : shapes) {
            setSelected(handle, false);
        }
    }

    void setSelected(MiniHudRegionApi.ShapeHandle handle, boolean selected) {
        draftTokens.remove(handle.selectionKey);
        draftTokens.remove(handle.displayName);
        if (selected) draftTokens.add(handle.selectionKey);
    }

    boolean isSelected(MiniHudRegionApi.ShapeHandle handle) {
        return draftTokens.contains(handle.selectionKey) || draftTokens.contains(handle.displayName);
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
                normalized.add(handle.selectionKey);
            }
        }
        return normalized;
    }
}

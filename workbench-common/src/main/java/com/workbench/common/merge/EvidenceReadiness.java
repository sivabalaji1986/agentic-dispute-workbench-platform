package com.workbench.common.merge;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.workbench.common.agui.EvidenceItem;

import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvidenceReadiness(int present, int required, List<String> missingItems, String label) {

    public EvidenceReadiness {
        missingItems = missingItems == null ? List.of() : List.copyOf(missingItems);
    }

    public static EvidenceReadiness compute(List<EvidenceItem> available, List<String> required) {
        List<String> missingItems = new ArrayList<>();
        int presentCount = 0;
        for (String requiredCode : required) {
            String humanReadable = DocumentTypes.humanReadable(requiredCode);
            boolean found = available.stream()
                    .anyMatch(item -> item.present() && item.label().equalsIgnoreCase(humanReadable));
            if (found) {
                presentCount++;
            } else {
                missingItems.add(humanReadable);
            }
        }
        String label = presentCount + " of " + required.size() + " required items present";
        return new EvidenceReadiness(presentCount, required.size(), missingItems, label);
    }
}

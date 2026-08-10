package mage.player.ai.kanna;

import mage.abilities.ActivatedAbility;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Two-way map between short synthetic ids and the legal abilities they stand for.
 * <p>
 * Short ids (act-0, act-1) rather than raw UUIDs: a model echoing back a UUID gets
 * it wrong often enough to matter, and a wrong id is indistinguishable from a
 * hallucinated one.
 *
 * @author Darrell Best
 */
public final class ActionCatalog {

    private final Map<String, ActivatedAbility> byId = new LinkedHashMap<String, ActivatedAbility>();
    private final Map<String, String> labels = new LinkedHashMap<String, String>();
    // identity, not equals: two distinct playable options can compare equal but must stay distinct
    private final Map<ActivatedAbility, String> idByAbility = new IdentityHashMap<ActivatedAbility, String>();

    public void add(ActivatedAbility ability, String label) {
        String id = "act-" + byId.size();
        byId.put(id, ability);
        labels.put(id, label);
        idByAbility.put(ability, id);
    }

    public String idFor(ActivatedAbility ability) {
        return idByAbility.get(ability);
    }

    public ActivatedAbility resolve(String id) {
        if (id == null) {
            return null;
        }
        return byId.get(id);
    }

    public String labelFor(String id) {
        return labels.get(id);
    }

    public List<String> ids() {
        return new ArrayList<String>(byId.keySet());
    }

    public int size() {
        return byId.size();
    }
}

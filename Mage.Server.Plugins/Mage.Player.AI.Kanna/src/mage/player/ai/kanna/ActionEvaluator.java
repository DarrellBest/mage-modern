package mage.player.ai.kanna;

import mage.abilities.ActivatedAbility;
import mage.abilities.costs.Cost;
import mage.abilities.costs.common.DiscardCardCost;
import mage.abilities.costs.common.DiscardHandCost;
import mage.abilities.costs.common.PayLifeCost;
import mage.abilities.costs.common.SacrificeAllCost;
import mage.abilities.costs.common.SacrificeAttachedCost;
import mage.abilities.costs.common.SacrificeAttachmentCost;
import mage.abilities.costs.common.SacrificeSourceCost;
import mage.abilities.costs.common.SacrificeTargetCost;
import mage.abilities.costs.common.SacrificeXTargetCost;
import mage.abilities.costs.common.ExileAttachmentCost;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exact non-combat cost arithmetic. Mirrors CombatEvaluator's role for the rest of
 * Kanna's actions: heuristics compute, the model judges.
 * <p>
 * CombatEvaluator annotates every candidate attack with real math, and the model
 * demonstrably reasons from it. Every other action used to reach the model with bare
 * oracle text and no evaluation of what paying its cost actually does -- observed
 * live as Kanna sacrificing her entire board to Altar of Dementia (a zero-mana
 * ability, legal at every priority window she held a creature) for a handful of
 * milled cards, five times in one game, with no annotation of what the sacrifice
 * itself cost her. This class is the fix: it states the real, computed consequence of
 * paying an ability's costs -- how many creatures are at stake, what life total
 * results, how the hand shrinks -- without ever judging whether paying it is good.
 * <p>
 * Split the same way CreatureView/CombatEvaluator are split: the *Text methods below
 * are pure functions over plain values, unit-testable with no Game at all; annotate()
 * is the thin extraction layer that pulls those values out of a live Ability/Game.
 *
 * @author Darrell Best
 */
public final class ActionEvaluator {

    private static final Pattern PAY_LIFE_AMOUNT = Pattern.compile("pay\\s+(\\d+)\\s+life", Pattern.CASE_INSENSITIVE);

    private ActionEvaluator() {
    }

    // ------------------------------------------------------------------ pure text

    /**
     * The engine resolves *which* permanent gets sacrificed later (target choice), so
     * this cannot name it -- but it can and does state the real cost: how many
     * creatures are on the board now, the smallest and largest by power/toughness, and
     * what the board count becomes. Computed, not judged: it never says whether losing
     * that creature is worth it.
     */
    public static String sacrificeAmongText(List<CreatureView> myCreatures) {
        int count = myCreatures.size();
        if (count == 0) {
            return "sacrifices a creature -- you control none";
        }
        if (count == 1) {
            return "sacrifices a creature -- you control 1 (" + describe(myCreatures.get(0))
                    + "), board 1 -> 0";
        }
        CreatureView smallest = myCreatures.get(0);
        CreatureView largest = myCreatures.get(0);
        for (CreatureView creature : myCreatures) {
            if (creature.power < smallest.power) {
                smallest = creature;
            }
            if (creature.power > largest.power) {
                largest = creature;
            }
        }
        return "sacrifices a creature -- you control " + count + " (smallest " + describe(smallest)
                + ", largest " + describe(largest) + "), board " + count + " -> " + (count - 1);
    }

    /**
     * Unlike sacrificeAmongText, the permanent being given up here is already known
     * (the source itself, or -- for SacrificeAttachedCost -- whatever it is attached
     * to), so this names it exactly instead of reporting board-wide smallest/largest.
     * boardOwner's creature count is still reported for context (e.g. "your last
     * creature" reads very differently from "one of six").
     */
    public static String sacrificeExactText(CreatureView sacrificed, List<CreatureView> boardOwnerCreatures) {
        int count = boardOwnerCreatures.size();
        return "sacrifices " + describe(sacrificed) + " -- board " + count + " -> " + Math.max(0, count - 1);
    }

    /** Paying 0 is not a real cost (MTG rule 118.4) and is not reported. */
    public static String payLifeText(int lifeBefore, int amount) {
        if (amount <= 0) {
            return "";
        }
        return "pays " + amount + " life: " + lifeBefore + " -> " + (lifeBefore - amount);
    }

    /** numberToDiscard <= 0 means nothing is actually given up -- nothing to report. */
    public static String discardText(int handSizeBefore, int numberToDiscard) {
        if (numberToDiscard <= 0) {
            return "";
        }
        int after = Math.max(0, handSizeBefore - numberToDiscard);
        String noun = numberToDiscard == 1 ? "a card" : numberToDiscard + " cards";
        return "discards " + noun + ": hand " + handSizeBefore + " -> " + after;
    }

    private static String describe(CreatureView creature) {
        return creature.name + " " + creature.power + "/" + creature.toughness;
    }

    // ------------------------------------------------------------------ extraction

    /**
     * Short computed-consequence suffix for an activated ability's catalog line, or ""
     * when there is nothing worth saying. Consolidates every annotation this ability
     * earns -- counters on its source (previously rendered only by
     * ComputerPlayerKanna.labelFor calling GameStateFormatter directly) and now cost
     * consequences too -- into one call, so there is exactly one place in the priority
     * prompt path that decides what gets appended to a line.
     */
    public static String annotate(ActivatedAbility ability, Game game, UUID playerId) {
        if (ability == null || game == null) {
            return "";
        }
        Permanent source = game.getPermanent(ability.getSourceId());
        String counters = GameStateFormatter.counterAnnotation(source, ability.toString(), game);

        Player player = game.getPlayer(playerId);
        List<String> costTexts = new ArrayList<String>();
        for (Cost cost : ability.getCosts()) {
            String text = describeCost(cost, game, playerId, player, ability.getSourceId());
            if (text != null && !text.isEmpty()) {
                costTexts.add(text);
            }
        }

        if (costTexts.isEmpty()) {
            return counters;
        }
        return counters + " -- " + join(costTexts, "; ");
    }

    private static String describeCost(Cost cost, Game game, UUID playerId, Player player, UUID abilitySourceId) {
        if (player == null) {
            return "";
        }
        // SacrificeSourceCost/SacrificeAttachedCost give up a specific, already-known
        // permanent -- handled before the generic SacrificeCost branch so they get the
        // more precise "names the creature" text rather than the board-guess text.
        // Neither cost type exposes that permanent publicly (SacrificeSourceCost.pay()
        // and SacrificeAttachedCost.pay() both derive it internally from the
        // *ability's* source id, not anything reachable from the Cost object itself),
        // so it is derived the same way here, from the ability's own source id.
        if (cost instanceof SacrificeSourceCost) {
            Permanent source = game.getPermanent(abilitySourceId);
            return sacrificeExactIfCreature(source, game, playerId);
        }
        if (cost instanceof SacrificeAttachedCost) {
            Permanent aura = game.getPermanent(abilitySourceId);
            Permanent attachedTo = aura == null || aura.getAttachedTo() == null
                    ? null : game.getPermanent(aura.getAttachedTo());
            UUID boardOwnerId = attachedTo == null ? null : attachedTo.getControllerId();
            return sacrificeExactIfCreature(attachedTo, game, boardOwnerId);
        }
        if (cost instanceof SacrificeTargetCost) {
            // Filter is not exposed publicly on SacrificeTargetCost before payment, but
            // its human-readable text is built from the same filter (see
            // SacrificeTargetCost.makeText) -- "sacrifice a creature", "sacrifice a
            // land", etc. Gating on that avoids ever claiming a creature is at stake
            // when the cost is actually about a different permanent type, which this
            // class has no board-stat data for.
            if (!textMentionsCreature(cost)) {
                return "";
            }
            return sacrificeAmongText(controlledCreatures(playerId, game));
        }
        // SacrificeXTargetCost (X unresolved until announced, after this text is
        // built), SacrificeAllCost (filter not exposed publicly, so which permanents
        // -- and how many -- actually leave the board can't be computed here), and
        // SacrificeAttachmentCost/ExileAttachmentCost (give up an Aura/Equipment, not a
        // creature -- board-stat math doesn't apply) are deliberately left unannotated:
        // annotating a wrong count is worse than annotating nothing.
        if (cost instanceof SacrificeXTargetCost || cost instanceof SacrificeAllCost
                || cost instanceof SacrificeAttachmentCost || cost instanceof ExileAttachmentCost) {
            return "";
        }
        if (cost instanceof PayLifeCost) {
            Matcher matcher = PAY_LIFE_AMOUNT.matcher(cost.getText() == null ? "" : cost.getText());
            if (!matcher.find()) {
                // Amount is a DynamicValue (e.g. "pay X life") with no public getter
                // before payment -- can't compute it without inventing a value.
                return "";
            }
            return payLifeText(player.getLife(), Integer.parseInt(matcher.group(1)));
        }
        if (cost instanceof DiscardHandCost) {
            return discardText(player.getHand().size(), player.getHand().size());
        }
        if (cost instanceof DiscardCardCost) {
            int amount = cost.getTargets().isEmpty() ? 1 : cost.getTargets().get(0).getMinNumberOfTargets();
            return discardText(player.getHand().size(), amount);
        }
        return "";
    }

    private static String sacrificeExactIfCreature(Permanent permanent, Game game, UUID boardOwnerId) {
        if (permanent == null || !permanent.isCreature(game) || boardOwnerId == null) {
            return "";
        }
        CreatureView view = CreatureView.from("s-exact", permanent, game);
        return sacrificeExactText(view, controlledCreatures(boardOwnerId, game));
    }

    private static boolean textMentionsCreature(Cost cost) {
        String text = cost.getText();
        return text != null && text.toLowerCase().contains("creature");
    }

    private static List<CreatureView> controlledCreatures(UUID controllerId, Game game) {
        List<CreatureView> views = new ArrayList<CreatureView>();
        if (controllerId == null) {
            return views;
        }
        int index = 0;
        for (Permanent permanent : game.getBattlefield().getAllActivePermanents(controllerId)) {
            if (permanent.isCreature(game)) {
                views.add(CreatureView.from("s-" + index++, permanent, game));
            }
        }
        return views;
    }

    private static String join(List<String> items, String separator) {
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (sb.length() > 0) {
                sb.append(separator);
            }
            sb.append(item);
        }
        return sb.toString();
    }
}

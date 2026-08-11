package mage.player.ai.kanna;

import mage.MageObject;
import mage.Mana;
import mage.abilities.ActivatedAbility;
import mage.abilities.PlayLandAbility;
import mage.abilities.common.PassAbility;
import mage.abilities.costs.mana.ManaCosts;
import mage.abilities.mana.ManaOptions;
import mage.constants.CardType;
import mage.game.Game;
import mage.players.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Orders legal actions so the model sees the few that matter first.
 * <p>
 * Deliberately coarse. Its job is to anchor attention and cut prompt size, not
 * to decide the game -- that is the model's half of the work. The full
 * catalog always stays reachable via show_all_actions, and the hidden count
 * is always stated, so the shortlist can never quietly bury a winning line
 * that is actually in the catalog.
 * <p>
 * DARRELLBEST-FORK: that invariant covers the catalog's contents, not every
 * legal ability in the game -- ComputerPlayerKanna.priority() excludes mana
 * abilities (tap-for-mana) from the catalog entirely, upstream of this class
 * and upstream of ranking, so they are never listed by show_all_actions and
 * never counted in the hidden total. That exclusion is a deliberate design
 * choice made at the filter site (see the comment there), not something
 * ActionRanker enforces or is even aware of.
 * <p>
 * DARRELLBEST-FORK: this used to classify by matching substrings against an
 * action's rendered label ("bolt"/"destroy"/"damage"/... meant "removal";
 * "cast " meant "creature"). That is guesswork on a name, not a fact about the
 * card: Doom Blade, Swords to Plowshares, Pongify and Reality Shift never
 * matched any keyword and fell through unclassified, while an artifact
 * literally named "Kill Switch" would have matched "kill" and been called
 * removal. Replaced with classification from the actual game object the
 * ability's source resolves to (its real card types, mana value, and -- for
 * creatures -- power/toughness), which is exact instead of guessed. Per the
 * project's central design principle (heuristics compute exact facts, the
 * model judges), this deliberately does NOT attempt to classify "removal" --
 * that requires reading card text, which the model already has access to via
 * get_card_text, and a wrong guess is worse than no guess because the model
 * reasons from what this class states as fact.
 *
 * @author Darrell Best
 */
public final class ActionRanker {

    private static final int SCORE_LAND = 100;
    private static final int SCORE_PERMANENT_SPELL = 90;
    private static final int SCORE_OTHER_SPELL = 80;
    private static final int SCORE_PASS = 0;
    // DARRELLBEST-FORK: deliberately BELOW SCORE_PASS, not just below the recognised
    // buckets above it. This bucket is every activated ability that does NOT cast a
    // card from hand (AbilityType.isPlayCardAbility() false) -- i.e. an ability of a
    // permanent already on the battlefield, such as Jar of Eyeballs' "remove eyeball
    // counters: look at X". This ranker cannot judge an arbitrary activated ability's
    // *effect* (only a card being cast has a type/mana-value/P-T worth stating), so
    // recognising the object's card type is not enough to say anything useful here.
    // With this above SCORE_PASS (it used to be 50), once no land or spell was on
    // offer an arbitrary, unevaluated activated ability became the top-ranked
    // suggestion every single turn, and the model took the headline recommendation --
    // this is what drove Kanna to activate Jar of Eyeballs with 0 eyeball counters,
    // burning {3}, five times in one game. A tie with SCORE_PASS is not enough either:
    // Pass is always added to the catalog last, so a stable sort would keep the
    // unrecognised ability sorted ahead of it on insertion order alone. Strictly
    // negative is what actually makes "I have no opinion" rank behind "do nothing" by
    // default, leaving the model to weigh it on the oracle text in the prompt rather
    // than on its position in this list.
    private static final int SCORE_OTHER = -10;

    private ActionRanker() {
    }

    public static List<RankedAction> rank(ActionCatalog catalog, Game game, UUID playerId) {
        List<RankedAction> ranked = new ArrayList<RankedAction>();
        List<String> ids = catalog.ids();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            String label = catalog.labelFor(id);
            ActivatedAbility ability = catalog.resolve(id);
            ActionFacts facts = extractFacts(ability, game, playerId);
            int score = score(facts);
            ranked.add(new RankedAction(id, label, reason(facts), score));
        }
        // stable sort: equal scores keep insertion order, so ranking is reproducible
        Collections.sort(ranked, new Comparator<RankedAction>() {
            @Override
            public int compare(RankedAction a, RankedAction b) {
                return Integer.compare(b.score, a.score);
            }
        });
        return ranked;
    }

    public static List<RankedAction> shortlist(List<RankedAction> ranked, int limit) {
        if (ranked.size() <= limit) {
            return new ArrayList<RankedAction>(ranked);
        }
        return new ArrayList<RankedAction>(ranked.subList(0, limit));
    }

    public static String render(List<RankedAction> shortlist, int totalCount) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < shortlist.size(); i++) {
            RankedAction action = shortlist.get(i);
            sb.append(String.format("%2d. %-8s %s", i + 1, action.id, action.label));
            if (action.reason != null && !action.reason.isEmpty()) {
                sb.append("  (").append(action.reason).append(')');
            }
            sb.append(System.lineSeparator());
        }
        int hidden = totalCount - shortlist.size();
        if (hidden > 0) {
            sb.append("... ").append(hidden)
                    .append(" more options available: call show_all_actions")
                    .append(System.lineSeparator());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ pure scoring

    /**
     * Pure: no Game, no engine callbacks. Unit-testable against hand-constructed
     * ActionFacts.
     */
    public static int score(ActionFacts facts) {
        switch (facts.category) {
            case LAND:
                return SCORE_LAND;
            case PERMANENT_SPELL:
                return SCORE_PERMANENT_SPELL;
            case OTHER_SPELL:
                return SCORE_OTHER_SPELL;
            case PASS:
                return SCORE_PASS;
            case UNCLASSIFIED_ACTIVATED:
            default:
                return SCORE_OTHER;
        }
    }

    /**
     * Pure: no Game, no engine callbacks. States what is -- type, cost, affordability,
     * power/toughness -- never a judgement of whether the action is good.
     */
    public static String reason(ActionFacts facts) {
        switch (facts.category) {
            case LAND:
                return "land drop, adds mana this turn";
            case PASS:
                return "take no action";
            case UNCLASSIFIED_ACTIVATED:
                // Honest, not a real evaluation: this ranker cannot judge an arbitrary
                // activated ability, so it says so rather than staying silent (silence read
                // as "nothing to add" rather than "not evaluated", which invited the model
                // to trust the ranking anyway).
                return "unscored -- judge from the oracle text above, not this ranking";
            case PERMANENT_SPELL:
            case OTHER_SPELL:
                return spellReason(facts);
            default:
                return "";
        }
    }

    private static String spellReason(ActionFacts facts) {
        StringBuilder sb = new StringBuilder();
        if (facts.typeLine != null && !facts.typeLine.isEmpty()) {
            sb.append(facts.typeLine).append(", ");
        }
        sb.append(facts.manaValue).append(facts.manaValue == 1 ? " mana" : " mana");
        sb.append(facts.affordable ? " (affordable)" : " (not currently affordable)");
        if (facts.isCreature) {
            sb.append(", ").append(facts.power).append('/').append(facts.toughness);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ extraction

    /**
     * Game-facing extraction layer: pulls ActionFacts out of a live Ability/Game.
     * Mirrors ActionEvaluator.annotate's split -- this is the thin, not-independently-
     * unit-tested part; score()/reason() above are the pure, tested part.
     */
    private static ActionFacts extractFacts(ActivatedAbility ability, Game game, UUID playerId) {
        if (ability instanceof PassAbility) {
            return new ActionFacts(ActionFacts.Category.PASS, "", 0, false, false, 0, 0);
        }
        if (ability == null || game == null) {
            return new ActionFacts(ActionFacts.Category.UNCLASSIFIED_ACTIVATED, "", 0, false, false, 0, 0);
        }
        if (ability instanceof PlayLandAbility) {
            return new ActionFacts(ActionFacts.Category.LAND, "land", 0, true, false, 0, 0);
        }
        if (ability.getAbilityType() == null || !ability.getAbilityType().isPlayCardAbility()) {
            // Not casting a card -- an activated ability of a permanent already on the
            // battlefield (e.g. Jar of Eyeballs). See SCORE_OTHER's comment for why this
            // stays unclassified rather than guessed at from the source's card type.
            return new ActionFacts(ActionFacts.Category.UNCLASSIFIED_ACTIVATED, "", 0, false, false, 0, 0);
        }

        MageObject source = ability.getSourceObjectIfItStillExists(game);
        if (source == null) {
            source = ability.getSourceObject(game);
        }
        if (source == null) {
            // A real spell cast whose source object cannot be resolved (should not
            // normally happen for a card in hand) -- still a card being cast, so it
            // belongs above Pass, just without the computed detail.
            return new ActionFacts(ActionFacts.Category.OTHER_SPELL, "", 0, false, false, 0, 0);
        }

        boolean isCreature = source.isCreature(game);
        boolean isPermanentType = isCreature || source.isArtifact(game)
                || source.isEnchantment(game) || source.isPlaneswalker(game);
        String typeLine = typeLineOf(source, game);
        int manaValue = source.getManaValue();
        boolean affordable = isAffordable(ability, game, playerId);
        int power = isCreature ? source.getPower().getValue() : 0;
        int toughness = isCreature ? source.getToughness().getValue() : 0;

        ActionFacts.Category category = isPermanentType
                ? ActionFacts.Category.PERMANENT_SPELL
                : ActionFacts.Category.OTHER_SPELL;
        return new ActionFacts(category, typeLine, manaValue, affordable, isCreature, power, toughness);
    }

    private static String typeLineOf(MageObject source, Game game) {
        StringBuilder sb = new StringBuilder();
        for (CardType type : source.getCardType(game)) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(type.toString().toLowerCase());
        }
        return sb.toString();
    }

    /**
     * Best-effort, display-only: ComputerPlayerKanna.priority() only ever puts
     * actions from getPlayable() into the catalog, which already guarantees every
     * entry here is legal (costs included) right now -- so this is expected to always
     * be true in production. Computed independently anyway, against the ability's
     * printed mana cost, rather than hardcoded, so the annotation stays a real fact
     * and not an assumption if that invariant ever changes. Defensive against nulls:
     * falls back to affordable (matching the upstream guarantee) rather than
     * asserting a negative it cannot actually support.
     */
    private static boolean isAffordable(ActivatedAbility ability, Game game, UUID playerId) {
        Player player = game.getPlayer(playerId);
        if (player == null) {
            return true;
        }
        ManaCosts<?> manaCosts = ability.getManaCosts();
        if (manaCosts == null) {
            return true;
        }
        Mana cost = manaCosts.getMana();
        if (cost == null || cost.count() == 0) {
            return true;
        }
        ManaOptions available = player.getManaAvailable(game);
        return available.enough(cost);
    }
}

package mage.cards.v;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.SpellAbility;
import mage.abilities.common.EntersBattlefieldTriggeredAbility;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.cost.CostModificationEffectImpl;
import mage.abilities.triggers.BeginningOfFirstMainTriggeredAbility;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.CostModificationType;
import mage.constants.Duration;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.game.stack.Spell;
import mage.util.CardUtil;
import mage.watchers.common.SpellsCastWatcher;

import java.util.UUID;

public final class VoxChildOfTheCollective extends CardImpl {

    public VoxChildOfTheCollective(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.ARTIFACT, CardType.CREATURE}, "{4}{U}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.BORG);
        this.power = new MageInt(3);
        this.toughness = new MageInt(3);

        // When Vox enters and at the beginning of your first main phase, the next artifact creature spell and the
        // next Spacecraft spell you cast this turn have undaunted.
        EntersBattlefieldTriggeredAbility etb = new EntersBattlefieldTriggeredAbility(new VoxChildOfTheCollectiveEffect());
        etb.setTriggerPhrase("When {this} enters and at the beginning of your first main phase, ");
        etb.addWatcher(new SpellsCastWatcher());
        this.addAbility(etb);
        this.addAbility(new BeginningOfFirstMainTriggeredAbility(new VoxChildOfTheCollectiveEffect()).setRuleVisible(false));
    }

    private VoxChildOfTheCollective(final VoxChildOfTheCollective card) {
        super(card);
    }

    @Override
    public VoxChildOfTheCollective copy() {
        return new VoxChildOfTheCollective(this);
    }
}

class VoxChildOfTheCollectiveEffect extends OneShotEffect {

    VoxChildOfTheCollectiveEffect() {
        super(Outcome.Benefit);
        staticText = "the next artifact creature spell and the next Spacecraft spell you cast this turn have undaunted "
                + "<i>(They cost {1} less to cast for each opponent you have.)</i>";
    }

    private VoxChildOfTheCollectiveEffect(final VoxChildOfTheCollectiveEffect effect) {
        super(effect);
    }

    @Override
    public VoxChildOfTheCollectiveEffect copy() {
        return new VoxChildOfTheCollectiveEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        game.addEffect(new VoxUndauntedEffect(true), source);  // next artifact creature spell
        game.addEffect(new VoxUndauntedEffect(false), source); // next Spacecraft spell
        return true;
    }
}

/** Undaunted ({1} less per opponent) for the next qualifying spell you cast this turn. */
class VoxUndauntedEffect extends CostModificationEffectImpl {

    private final boolean artifactCreature; // else: Spacecraft
    private int spellsCastAtStart = -1;

    VoxUndauntedEffect(boolean artifactCreature) {
        super(Duration.EndOfTurn, Outcome.Benefit, CostModificationType.REDUCE_COST);
        this.artifactCreature = artifactCreature;
    }

    private VoxUndauntedEffect(final VoxUndauntedEffect effect) {
        super(effect);
        this.artifactCreature = effect.artifactCreature;
        this.spellsCastAtStart = effect.spellsCastAtStart;
    }

    @Override
    public VoxUndauntedEffect copy() {
        return new VoxUndauntedEffect(this);
    }

    private boolean matches(Card card, Game game) {
        return artifactCreature
                ? card.isArtifact(game) && card.isCreature(game)
                : card.hasSubtype(SubType.SPACECRAFT, game);
    }

    private long qualifyingCastThisTurn(Game game, Ability source) {
        SpellsCastWatcher watcher = game.getState().getWatcher(SpellsCastWatcher.class);
        return watcher == null ? 0 : watcher.getSpellsCastThisTurn(source.getControllerId()).stream()
                .filter(spell -> matches(spell.getCard(), game)).count();
    }

    @Override
    public void init(Ability source, Game game) {
        super.init(source, game);
        spellsCastAtStart = (int) qualifyingCastThisTurn(game, source);
    }

    @Override
    public boolean apply(Game game, Ability source, Ability abilityToModify) {
        int opponents = game.getOpponents(source.getControllerId()).size();
        if (opponents > 0) {
            CardUtil.reduceCost(abilityToModify, opponents);
        }
        return true;
    }

    @Override
    public boolean applies(Ability abilityToModify, Ability source, Game game) {
        if (!(abilityToModify instanceof SpellAbility) || !abilityToModify.isControlledBy(source.getControllerId())) {
            return false;
        }
        if (qualifyingCastThisTurn(game, source) > spellsCastAtStart) {
            discard(); // the next one has been cast
            return false;
        }
        Card card = ((SpellAbility) abilityToModify).getCharacteristics(game);
        return card != null && matches(card, game);
    }
}

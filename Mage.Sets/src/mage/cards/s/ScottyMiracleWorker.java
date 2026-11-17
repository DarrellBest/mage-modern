package mage.cards.s;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.common.CantBeBeamedUpAbility;
import mage.abilities.costs.common.ReturnToHandChosenControlledPermanentCost;
import mage.abilities.costs.mana.ManaCostsImpl;
import mage.abilities.effects.ContinuousEffectImpl;
import mage.abilities.keyword.CastFromGraveyardAbility;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.Layer;
import mage.constants.Outcome;
import mage.constants.SpellAbilityCastMode;
import mage.constants.SubLayer;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.filter.common.FilterControlledCreaturePermanent;
import mage.filter.predicate.Predicate;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;
import mage.target.common.TargetControlledPermanent;

import java.util.UUID;

public final class ScottyMiracleWorker extends CardImpl {

    public ScottyMiracleWorker(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{1}{U}{U}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.ARTIFICER);
        this.power = new MageInt(2);
        this.toughness = new MageInt(3);

        // During your main phase, each card in your graveyard has beam me up. The beam me up cost is equal to that
        // spell's mana cost. Scotty can't be beamed up to cast a spell this way.
        this.addAbility(new SimpleStaticAbility(new ScottyMiracleWorkerEffect()));
    }

    private ScottyMiracleWorker(final ScottyMiracleWorker card) {
        super(card);
    }

    @Override
    public ScottyMiracleWorker copy() {
        return new ScottyMiracleWorker(this);
    }
}

class ScottyMiracleWorkerEffect extends ContinuousEffectImpl {

    ScottyMiracleWorkerEffect() {
        super(Duration.WhileOnBattlefield, Layer.AbilityAddingRemovingEffects_6, SubLayer.NA, Outcome.AddAbility);
        staticText = "During your main phase, each card in your graveyard has beam me up. The beam me up cost is equal "
                + "to that spell's mana cost. {this} can't be beamed up to cast a spell this way";
    }

    private ScottyMiracleWorkerEffect(final ScottyMiracleWorkerEffect effect) {
        super(effect);
    }

    @Override
    public ScottyMiracleWorkerEffect copy() {
        return new ScottyMiracleWorkerEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player player = game.getPlayer(source.getControllerId());
        if (player == null || !game.isActivePlayer(player.getId()) || !game.isMainPhase()) {
            return false;
        }
        for (Card card : player.getGraveyard().getCards(game)) {
            if (card.isLand(game)) {
                continue; // can't be cast
            }
            Ability ability = new ScottyBeamMeUpAbility(card, source.getSourceId());
            ability.setSourceId(card.getId());
            ability.setControllerId(card.getOwnerId());
            game.getState().addOtherAbility(card, ability);
        }
        return true;
    }
}

/** Beam me up {that spell's mana cost}, except Scotty (the granting permanent) can't be returned to pay it. */
class ScottyBeamMeUpAbility extends CastFromGraveyardAbility {

    ScottyBeamMeUpAbility(Card card, UUID scottyId) {
        super(card, new ManaCostsImpl<>(card.getManaCost().getText()), SpellAbilityCastMode.BEAM_ME_UP);
        FilterControlledCreaturePermanent filter = new FilterControlledCreaturePermanent("creature you control other than Scotty");
        filter.add(new ScottyNotScottyPredicate(scottyId));
        this.addCost(new ReturnToHandChosenControlledPermanentCost(new TargetControlledPermanent(1, 1, filter, true)));
    }

    private ScottyBeamMeUpAbility(final ScottyBeamMeUpAbility ability) {
        super(ability);
    }

    @Override
    public ScottyBeamMeUpAbility copy() {
        return new ScottyBeamMeUpAbility(this);
    }

    @Override
    public String getRule() {
        return "Beam me up " + getManaCosts().getText();
    }
}

class ScottyNotScottyPredicate implements Predicate<Permanent> {

    private final UUID scottyId;

    ScottyNotScottyPredicate(UUID scottyId) {
        this.scottyId = scottyId;
    }

    @Override
    public boolean apply(Permanent input, Game game) {
        return !input.getId().equals(scottyId) && !input.getAbilities(game).containsClass(CantBeBeamedUpAbility.class);
    }
}

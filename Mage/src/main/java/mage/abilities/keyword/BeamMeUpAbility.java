package mage.abilities.keyword;

import mage.abilities.common.CantBeBeamedUpAbility;
import mage.abilities.costs.common.ReturnToHandChosenControlledPermanentCost;
import mage.abilities.costs.mana.ManaCostsImpl;
import mage.cards.Card;
import mage.constants.SpellAbilityCastMode;
import mage.filter.common.FilterControlledCreaturePermanent;
import mage.filter.predicate.Predicate;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.target.common.TargetControlledPermanent;

/**
 * Cast from the graveyard for the listed mana cost and return a creature you
 * control to its owner's hand. Exile the spell when it leaves the stack.
 *
 * @author muz
 */
public class BeamMeUpAbility extends CastFromGraveyardAbility {

    // DARRELLBEST-FORK: permanents that "can't be beamed up" (e.g. enchanted by Marooned) can't pay the cost
    private static final FilterControlledCreaturePermanent beamable = new FilterControlledCreaturePermanent();

    static {
        beamable.add(BeamablePredicate.instance);
    }

    public BeamMeUpAbility(Card card, String manaString) {
        super(card, new ManaCostsImpl<>(manaString), SpellAbilityCastMode.BEAM_ME_UP);
        this.addCost(new ReturnToHandChosenControlledPermanentCost(new TargetControlledPermanent(1, 1, beamable, true)));
    }

    private BeamMeUpAbility(final BeamMeUpAbility ability) {
        super(ability);
    }

    @Override
    public BeamMeUpAbility copy() {
        return new BeamMeUpAbility(this);
    }

    @Override
    public String getRule() {
        return name + " <i>(You may cast this card from your graveyard for " + getManaCosts().getText()
            + " if you also return a creature you control to its owner's hand. Then exile this spell.)</i>";
    }

    enum BeamablePredicate implements Predicate<Permanent> {
        instance;

        @Override
        public boolean apply(Permanent input, Game game) {
            return !input.getAbilities(game).containsClass(CantBeBeamedUpAbility.class);
        }
    }
}

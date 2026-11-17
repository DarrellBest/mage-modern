package mage.cards.b;

import mage.abilities.Ability;
import mage.abilities.effects.OneShotEffect;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.cards.CardsImpl;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.constants.TargetController;
import mage.constants.Zone;
import mage.filter.FilterSpell;
import mage.filter.predicate.Predicates;
import mage.game.Game;
import mage.game.stack.Spell;
import mage.players.Player;
import mage.target.TargetSpell;
import mage.util.CardUtil;

import java.util.UUID;

public final class BrazenBluff extends CardImpl {

    private static final FilterSpell filter = new FilterSpell("noncreature spell an opponent controls");

    static {
        filter.add(Predicates.not(CardType.CREATURE.getPredicate()));
        filter.add(TargetController.OPPONENT.getControllerPredicate());
    }

    public BrazenBluff(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.INSTANT}, "{R}{R}");

        // Choose target noncreature spell an opponent controls. That player may exile it. If they don't, you may cast
        // a noncreature spell from your hand without paying its mana cost.
        this.getSpellAbility().addEffect(new BrazenBluffEffect());
        this.getSpellAbility().addTarget(new TargetSpell(filter));
    }

    private BrazenBluff(final BrazenBluff card) {
        super(card);
    }

    @Override
    public BrazenBluff copy() {
        return new BrazenBluff(this);
    }
}

class BrazenBluffEffect extends OneShotEffect {

    private static final mage.filter.FilterCard noncreature = new mage.filter.common.FilterNonlandCard("noncreature spell");

    static {
        noncreature.add(Predicates.not(CardType.CREATURE.getPredicate()));
    }

    BrazenBluffEffect() {
        super(Outcome.PlayForFree);
        staticText = "choose target noncreature spell an opponent controls. That player may exile it. If they don't, "
                + "you may cast a noncreature spell from your hand without paying its mana cost";
    }

    private BrazenBluffEffect(final BrazenBluffEffect effect) {
        super(effect);
    }

    @Override
    public BrazenBluffEffect copy() {
        return new BrazenBluffEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        Spell spell = game.getSpell(getTargetPointer().getFirst(game, source));
        if (controller == null || spell == null) {
            return false;
        }
        Player spellController = game.getPlayer(spell.getControllerId());
        if (spellController != null && spellController.chooseUse(Outcome.Detriment,
                "Exile " + spell.getLogName() + "? If you don't, " + controller.getName()
                        + " may cast a noncreature spell from their hand for free.", source, game)) {
            spellController.moveCards(spell, Zone.EXILED, source, game);
            return true;
        }
        CardUtil.castSpellWithAttributesForFree(controller, source, game,
                new CardsImpl(controller.getHand()), noncreature);
        return true;
    }
}
